package com.example.ragdemo.workflow.runtime;

import java.util.Optional;

import org.bsc.langgraph4j.state.StateSnapshot;

import com.example.ragdemo.mapper.IngestionTaskMapper;
import com.example.ragdemo.model.entity.IngestionTask;
import com.example.ragdemo.model.entity.KnowledgeDocument;
import com.example.ragdemo.rag.ingest.IngestionStatusUpdater;
import com.example.ragdemo.workflow.ingestion.IngestionState;
import com.example.ragdemo.workflow.ingestion.IngestionWorkflowRunner;

import lombok.extern.slf4j.Slf4j;

/**
 * 三态对账（Step 5 §3-A3 / §9）：{@code documents.status}、{@code ingestion_tasks.status}、
 * <b>Graph END</b> 是三个独立状态，不能互相替代。
 * <p>
 * 本类解决的核心问题：现有 {@code indexed} 短路会在"Persist 事务已提交、但 Graph 尚未 END /
 * task 尚未 success"的情况下<b>永久跳过</b> Finalize。因此命中短路之前必须先用本类对账：
 * <ul>
 *   <li>无 checkpoint ⇒ 首次执行（返回 NO_CHECKPOINT，交给上层正常启动）；</li>
 *   <li>Graph 已 FINISHED ⇒ 已收敛，只需把 task 状态对齐；</li>
 *   <li>Graph 未 FINISHED ⇒ <b>抢占并 resume 补完</b>（持久化已提交，不会重复写 chunk）。</li>
 * </ul>
 * 已 {@code failed} 的任务不会被重复消息自动重跑（永久错误不无限重试）。
 * <p>
 * <b>注意</b>：本类不标 {@code @Service}，只在 {@code IngestionGraphConfig} 里注册为 Bean
 * （与 {@link WorkflowExecutionCoordinator} 同理，避免开关关闭时启动失败）。
 */
@Slf4j
public class WorkflowReconciliationService {

    public enum Outcome {
        /** 无 checkpoint：属于首次执行 */
        NO_CHECKPOINT,
        /** Graph 已 END：三态中 Graph 侧已收敛 */
        GRAPH_ALREADY_ENDED,
        /** 本次对账把未完成的 Graph 补完到 END */
        RESUMED_TO_END,
        /** 执行权在别的 Worker 手里，本次不干预 */
        LEASE_HELD_BY_OTHER,
        /** 补完后仍未 END */
        NOT_COMPLETED,
        /** 任务已是 failed 终态，不自动重跑 */
        TASK_FAILED_NO_RETRY,
        /** 属于首次执行，已由 Coordinator 启动 */
        EXECUTED
    }

    public record Result(Outcome outcome, String detail) {
    }

    private final IngestionWorkflowRunner ingestionWorkflowRunner;
    private final WorkflowExecutionCoordinator coordinator;
    private final IngestionTaskMapper ingestionTaskMapper;
    private final IngestionStatusUpdater statusUpdater;

    public WorkflowReconciliationService(IngestionWorkflowRunner ingestionWorkflowRunner,
            WorkflowExecutionCoordinator coordinator, IngestionTaskMapper ingestionTaskMapper,
            IngestionStatusUpdater statusUpdater) {
        this.ingestionWorkflowRunner = ingestionWorkflowRunner;
        this.coordinator = coordinator;
        this.ingestionTaskMapper = ingestionTaskMapper;
        this.statusUpdater = statusUpdater;
    }

    /**
     * <b>统一恢复/执行入口</b>：Kafka 消费与恢复调度器<b>共用同一入口</b>（step5 §5/§9）。
     * <p>
     * 决策：无 checkpoint ⇒ 首次执行；Graph 未 END ⇒ 抢占并 resume 补完；
     * Graph 已 END ⇒ 仅把 task 状态对齐（幂等）；已 failed ⇒ 不自动重跑。
     */
    public Result recoverOrExecute(KnowledgeDocument document, long taskId) {
        Result result = reconcile(document, taskId);
        switch (result.outcome()) {
            case NO_CHECKPOINT -> {
                coordinator.execute(document, taskId);
                return new Result(Outcome.EXECUTED, "first run via coordinator");
            }
            case GRAPH_ALREADY_ENDED -> {
                statusUpdater.markTaskSuccess(taskId);
                return result;
            }
            default -> {
                return result;
            }
        }
    }

    /** 在 {@code indexed} 短路之前调用。 */
    public Result reconcile(KnowledgeDocument document, long taskId) {
        IngestionTask task = ingestionTaskMapper.selectById(taskId);
        if (task != null && "failed".equals(task.getStatus())) {
            return new Result(Outcome.TASK_FAILED_NO_RETRY, "task already failed");
        }

        StateSnapshot<IngestionState> snapshot = snapshot(taskId);
        if (snapshot == null || snapshot.state() == null) {
            return new Result(Outcome.NO_CHECKPOINT, "no checkpoint for thread="
                    + IngestionWorkflowRunner.threadIdOf(taskId));
        }
        if ("FINISHED".equals(snapshot.state().status())) {
            return new Result(Outcome.GRAPH_ALREADY_ENDED, "nextNode=" + snapshot.nextNodeId()
                    + ", taskStatus=" + (task == null ? "<null>" : task.getStatus()));
        }

        log.info("检测到未完成的入库图，尝试恢复补完: taskId={}, lastNode={}, nextNode={}, status={}",
                taskId, snapshot.node(), snapshot.nextNodeId(), snapshot.state().status());
        Optional<IngestionState> state = coordinator.execute(document, taskId);
        if (state.isEmpty()) {
            return new Result(Outcome.LEASE_HELD_BY_OTHER, "another worker holds the lease");
        }
        String status = state.get().status();
        return "FINISHED".equals(status)
                ? new Result(Outcome.RESUMED_TO_END, "resumed to END")
                : new Result(Outcome.NOT_COMPLETED, "status=" + status);
    }

    private StateSnapshot<IngestionState> snapshot(long taskId) {
        try {
            return ingestionWorkflowRunner.snapshot(taskId);
        } catch (Exception e) {
            log.debug("读取图快照失败（按无 checkpoint 处理）: taskId={}, reason={}", taskId, e.toString());
            return null;
        }
    }
}
