package com.example.ragdemo.workflow.ingestion.node;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.ragdemo.rag.ingest.IngestionStatusUpdater;
import com.example.ragdemo.workflow.ingestion.IngestionErrorCodes;
import com.example.ragdemo.workflow.ingestion.IngestionState;
import com.example.ragdemo.workflow.testkit.WorkflowFaultInjector;
import com.example.ragdemo.workflow.runtime.TaskLeaseService;
import com.example.ragdemo.workflow.runtime.TaskLeaseService.Lease;

/**
 * 收尾节点：确认已真正落库后再标记任务成功。
 * <p>
 * 顺序与旧链路一致（{@code replaceChunks} 里已把文档置 {@code indexed}，
 * 其后才 {@code markTaskSuccess}），保证"task success 恰好发生在 indexed 之后"（step4 §7）。
 * <p>
 * Step 5：当存在执行权（fencing token）时，终态标记走 owner+token 保护；
 * 执行权已易主则抛出 {@code FENCING_REJECTED}，由 Coordinator 判为"租约丢失"而不污染任务状态。
 */
@Component
public class FinalizeNode {

    private final IngestionStatusUpdater statusUpdater;
    private final TaskLeaseService taskLeaseService;
    private final WorkflowFaultInjector faultInjector;

    public FinalizeNode(IngestionStatusUpdater statusUpdater, TaskLeaseService taskLeaseService,
            WorkflowFaultInjector faultInjector) {
        this.statusUpdater = statusUpdater;
        this.taskLeaseService = taskLeaseService;
        this.faultInjector = faultInjector;
    }

    public Map<String, Object> apply(IngestionState state) {
        if (!state.persisted()) {
            throw new IllegalStateException(IngestionErrorCodes.STATE_MISMATCH
                    + "finalize 之前必须已持久化: taskId=" + state.taskId());
        }
        long fencingToken = state.fencingToken();
        if (fencingToken > 0 && !state.workerId().isBlank()) {
            boolean marked = taskLeaseService.markSucceeded(
                    new Lease(state.taskId(), state.workerId(), fencingToken));
            if (!marked) {
                throw new IllegalStateException("FENCING_REJECTED: finalize 时执行权已失效 taskId="
                        + state.taskId() + " owner=" + state.workerId() + " token=" + fencingToken);
            }
        } else {
            statusUpdater.markTaskSuccess(state.taskId());
        }

        // 故障注入点（生产 no-op）：A5 —— task 已 success 但 Graph 尚未 END
        faultInjector.at(WorkflowFaultInjector.Points.FINALIZE_AFTER_TASK_SUCCESS,
                Map.of("taskId", state.taskId()));

        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put(IngestionState.STATUS, "FINISHED");
        return delta;
    }
}
