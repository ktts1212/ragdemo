package com.example.ragdemo.workflow.ingestion;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;

import com.example.ragdemo.model.entity.KnowledgeDocument;
import com.example.ragdemo.workflow.runtime.TaskLeaseService.Lease;

import lombok.extern.slf4j.Slf4j;

/**
 * 入库图对外入口（step4 §7）。
 * <p>
 * <b>start 与 resume 必须由本类明确区分</b>：同一 threadId 若已有 checkpoint，
 * 一律走 {@code GraphInput.resume()}；<b>绝不</b>用 {@code GraphInput.args(...)} 把历史重置掉。
 * <p>
 * threadId 命名空间 {@code ingestion-{taskId}}：与 PoC 的 {@code poc-recovery-}/{@code poc-batch-}
 * 天然隔离（三者状态类型不同、Saver 也各持自己的 Serializer）。
 */
@Slf4j
public class IngestionWorkflowRunner {

    public static final String THREAD_PREFIX = "ingestion-";

    private final CompiledGraph<IngestionState> graph;

    public IngestionWorkflowRunner(CompiledGraph<IngestionState> ingestionGraph) {
        this.graph = ingestionGraph;
    }

    public static String threadIdOf(long taskId) {
        return THREAD_PREFIX + taskId;
    }

    public static RunnableConfig configOf(long taskId) {
        return RunnableConfig.builder().threadId(threadIdOf(taskId)).build();
    }

    /** 首次启动或恢复（按是否已有 checkpoint 自动决定）。 */
    public Optional<IngestionState> startOrResume(KnowledgeDocument document, Long taskId) {
        return startOrResume(document, taskId, null);
    }

    /**
     * Step 5：带执行权（租约）的启动/恢复。
     * <p>
     * <b>关键</b>：恢复时用 {@code GraphInput.resume(leaseVars)} 把<b>当前</b> owner/token
     * 合并进状态，覆盖 checkpoint 里可能已陈旧的 token —— 否则新 Worker 会被自己的 fencing 拒绝。
     *
     * @param lease 为 {@code null} 时退化为 Step 4 的无租约行为（仅历史测试/离线重跑使用）
     */
    public Optional<IngestionState> startOrResume(KnowledgeDocument document, Long taskId, Lease lease) {
        RunnableConfig config = configOf(taskId);
        boolean resume = hasCheckpoint(config);
        Map<String, Object> inputs = resume
                ? leaseVars(lease)
                : withLease(initialArgs(document, taskId), lease);
        log.info("{}: threadId={}, documentId={}, lease={}",
                resume ? "恢复执行入库图" : "首次执行入库图", threadIdOf(taskId), document.getId(),
                lease == null ? "<none>" : "owner=" + lease.owner() + ",token=" + lease.fencingToken());
        return graph.invoke(resume ? GraphInput.resume(inputs) : GraphInput.args(inputs), config);
    }

    private static Map<String, Object> leaseVars(Lease lease) {
        if (lease == null) {
            return Map.of();
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put(IngestionState.FENCING_TOKEN, lease.fencingToken());
        vars.put(IngestionState.WORKER_ID, lease.owner());
        return vars;
    }

    private static Map<String, Object> withLease(Map<String, Object> args, Lease lease) {
        if (lease == null) {
            return args;
        }
        Map<String, Object> merged = new LinkedHashMap<>(args);
        merged.putAll(leaseVars(lease));
        return merged;
    }

    /** 显式恢复（供后续步骤/人工运维使用）。 */
    public Optional<IngestionState> resume(long taskId) {
        return graph.invoke(GraphInput.resume(), configOf(taskId));
    }

    public StateSnapshot<IngestionState> snapshot(long taskId) {
        return graph.getState(configOf(taskId));
    }

    private boolean hasCheckpoint(RunnableConfig config) {
        try {
            StateSnapshot<IngestionState> snapshot = graph.getState(config);
            return snapshot != null && snapshot.state() != null;
        } catch (Exception e) {
            // getState 在无 checkpoint 时会抛（"Missing Checkpoint!"）⇒ 视作首次启动
            log.debug("无可用 checkpoint，按首次启动处理: threadId={}, reason={}",
                    config.threadId().orElse("?"), e.toString());
            return false;
        }
    }

    /** 初始入参：只放"身份 + 引用"，不放任何大对象。 */
    private static Map<String, Object> initialArgs(KnowledgeDocument document, Long taskId) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(IngestionState.TASK_ID, taskId);
        args.put(IngestionState.DB_DOCUMENT_ID, document.getId());
        args.put(IngestionState.KNOWLEDGE_BASE_ID, document.getKnowledgeBaseId());
        args.put(IngestionState.DOCUMENT_VERSION,
                document.getVersion() == null ? 1 : document.getVersion());
        args.put(IngestionState.CHECKSUM, document.getChecksum());
        args.put(IngestionState.SOURCE_KEY, document.getStoragePath());
        args.put(IngestionState.FILE_NAME, document.getFileName());
        args.put(IngestionState.PARSE_MODE, document.getParseMode());
        return args;
    }
}
