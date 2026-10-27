package com.example.ragdemo.workflow.poc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

/**
 * Step 2 PoC 图：{@code START → prepare → process → finish → END}。
 * <p>
 * 节点只做状态更新，不触碰 Marker/Kafka/Embedding/数据库业务操作；
 * 每个节点返回<b>状态增量 Map</b>，不直接修改共享状态对象。
 * <p>
 * <b>Channel / Reducer 语义（重要）</b>：
 * <ul>
 *   <li>{@code taskId} / {@code currentStep} / {@code processedCount} 用 {@link Channels#base}
 *       ⇒ <b>最后写入胜出</b>；因此节点返回 {@code processedCount} 时必须是<b>绝对值</b>，
 *       而不是"增量值"（否则永远等于 1）</li>
 *   <li>{@code executionLog} 用 {@link Channels#appender}
 *       ⇒ <b>追加</b>语义；节点只需返回<b>本次新增的日志行</b></li>
 * </ul>
 * 这正是"不能假设集合更新一定是追加语义"的落地：如果日志也用 {@code base}，
 * 后一个节点会<b>覆盖</b>前一个节点的日志。
 */
public class RecoveryTestGraph {

    public static final String NODE_PREPARE = "prepare";
    public static final String NODE_PROCESS = "process";
    public static final String NODE_FINISH = "finish";

    /** state schema（key → Channel，含 Reducer 语义）。 */
    public static Map<String, Channel<?>> schema() {
        Map<String, Channel<?>> schema = new HashMap<>();
        schema.put(RecoveryTestState.TASK_ID, Channels.<String>base(() -> ""));
        schema.put(RecoveryTestState.CURRENT_STEP, Channels.<String>base(() -> "NEW"));
        schema.put(RecoveryTestState.PROCESSED_COUNT, Channels.<Integer>base(() -> 0));
        schema.put(RecoveryTestState.EXECUTION_LOG,
                Channels.<List<String>>appender(ArrayList::new));
        return schema;
    }

    public StateGraph<RecoveryTestState> build() throws GraphStateException {
        StateGraph<RecoveryTestState> graph =
                new StateGraph<>(schema(), new RecoveryTestStateSerializer());

        graph.addNode(NODE_PREPARE, AsyncNodeAction.node_async(this::prepare));
        graph.addNode(NODE_PROCESS, AsyncNodeAction.node_async(this::process));
        graph.addNode(NODE_FINISH, AsyncNodeAction.node_async(this::finish));

        graph.addEdge(StateGraph.START, NODE_PREPARE);
        graph.addEdge(NODE_PREPARE, NODE_PROCESS);
        graph.addEdge(NODE_PROCESS, NODE_FINISH);
        graph.addEdge(NODE_FINISH, StateGraph.END);
        return graph;
    }

    // ==================== 节点（只返回增量 Map） ====================

    private Map<String, Object> prepare(RecoveryTestState state) {
        return Map.of(
                RecoveryTestState.TASK_ID, state.taskId(),
                RecoveryTestState.CURRENT_STEP, "PREPARED",
                RecoveryTestState.PROCESSED_COUNT, 0,
                RecoveryTestState.EXECUTION_LOG,
                List.of("prepare:taskId=" + state.taskId()));
    }

    private Map<String, Object> process(RecoveryTestState state) {
        // 注意：processedCount 是 base(last-write)，必须返回【绝对值】
        int next = state.processedCount() + 1;
        return Map.of(
                RecoveryTestState.CURRENT_STEP, "PROCESSED",
                RecoveryTestState.PROCESSED_COUNT, next,
                RecoveryTestState.EXECUTION_LOG,
                List.of("process:processedCount=" + next));
    }

    private Map<String, Object> finish(RecoveryTestState state) {
        return Map.of(
                RecoveryTestState.CURRENT_STEP, "FINISHED",
                RecoveryTestState.PROCESSED_COUNT, state.processedCount(),
                RecoveryTestState.EXECUTION_LOG,
                List.of("finish:processedCount=" + state.processedCount()));
    }
}
