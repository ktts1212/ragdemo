package com.example.ragdemo.workflow.poc.batch;

import java.util.Map;
import java.util.Optional;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;

/**
 * Step 3 批处理图对外入口（与 Step 2 的 {@code RecoveryTestRunner} 隔离）。
 * <p>
 * threadId 命名空间独立：{@code poc-batch-{taskId}}（Step 2 是 {@code poc-recovery-}）。
 * 这一点很关键：{@code PostgresSaverV2} 用<b>自己持有的</b> StateSerializer 解码
 * Checkpoint，两图状态类型不同，靠命名空间隔离才不会互相误读。
 */
public class BatchRecoveryRunner {

    public static final String THREAD_PREFIX = "poc-batch-";

    private final CompiledGraph<BatchRecoveryState> graph;

    public BatchRecoveryRunner(CompiledGraph<BatchRecoveryState> pocBatchRecoveryGraph) {
        this.graph = pocBatchRecoveryGraph;
    }

    public static String threadIdOf(String taskId) {
        return THREAD_PREFIX + taskId;
    }

    public static RunnableConfig configOf(String taskId) {
        return RunnableConfig.builder().threadId(threadIdOf(taskId)).build();
    }

    /** 全新启动：全新 threadId，初始入参只有 taskId 与 totalItems。 */
    public Optional<BatchRecoveryState> start(String taskId, int totalItems) {
        return graph.invoke(GraphInput.args(Map.of(
                BatchRecoveryState.TASK_ID, taskId,
                BatchRecoveryState.TOTAL_ITEMS, totalItems)), configOf(taskId));
    }

    /**
     * 用 {@code GraphInput.resume()} 在<b>同一 threadId</b> 上恢复执行。
     * 真实语义（异常崩溃后是否可用）由 {@code BatchRecoveryGraphTest} 实测记录，
     * 不在此处假设。
     */
    public Optional<BatchRecoveryState> resume(String taskId) {
        return graph.invoke(GraphInput.resume(), configOf(taskId));
    }

    /** 备选恢复方式：{@code noArgs()}（用于与 {@link #resume(String)} 行为对照）。 */
    public Optional<BatchRecoveryState> resumeWithNoArgs(String taskId) {
        return graph.invoke(GraphInput.noArgs(), configOf(taskId));
    }

    /** 读取该 thread 的当前快照（可能为 null，代表无 checkpoint）。 */
    public StateSnapshot<BatchRecoveryState> snapshot(String taskId) {
        return graph.getState(configOf(taskId));
    }

    public CompiledGraph<BatchRecoveryState> graph() {
        return graph;
    }
}
