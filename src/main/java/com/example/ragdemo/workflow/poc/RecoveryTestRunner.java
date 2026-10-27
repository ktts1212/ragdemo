package com.example.ragdemo.workflow.poc;

import java.util.Map;
import java.util.Optional;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;

/**
 * Step 2 PoC Runner（对外只暴露 start / getState）。
 * <p>
 * threadId 使用独立命名空间 {@code poc-recovery-{taskId}}，与业务无关；
 * 本步骤<b>不</b>实现自动恢复调度器（留给 Step 4）。
 */
public class RecoveryTestRunner {

    /** threadId 命名空间前缀 */
    public static final String THREAD_PREFIX = "poc-recovery-";

    private final CompiledGraph<RecoveryTestState> graph;

    public RecoveryTestRunner(CompiledGraph<RecoveryTestState> pocRecoveryTestGraph) {
        this.graph = pocRecoveryTestGraph;
    }

    public static String threadIdOf(String taskId) {
        return THREAD_PREFIX + taskId;
    }

    /** 用新的 threadId 启动工作流，返回终态。 */
    public Optional<RecoveryTestState> start(String taskId) {
        RunnableConfig config = configOf(taskId);
        return graph.invoke(
                GraphInput.args(Map.of(RecoveryTestState.TASK_ID, taskId)), config);
    }

    /** 重新读取该 thread 已保存的状态（要求已执行过）。 */
    public Optional<RecoveryTestState> getState(String taskId) {
        StateSnapshot<RecoveryTestState> snapshot = graph.getState(configOf(taskId));
        return snapshot == null ? Optional.empty() : Optional.ofNullable(snapshot.state());
    }

    private RunnableConfig configOf(String taskId) {
        return RunnableConfig.builder().threadId(threadIdOf(taskId)).build();
    }
}
