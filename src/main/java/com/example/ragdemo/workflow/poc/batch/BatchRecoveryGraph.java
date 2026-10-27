package com.example.ragdemo.workflow.poc.batch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.InterruptibleAction;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

/**
 * Step 3 批处理图：{@code START → prepare → batch →(条件边)→ batch / finish → END}。
 * <p>
 * 模拟"Embedding 每批 32 个 chunk"的循环处理，每批写一条结果到
 * {@link BatchResultStore}（测试专用表，幂等），并由 LangGraph4j 在<b>每个节点执行后</b>
 * 写一次 Checkpoint。
 * <p>
 * <b>与 Step 2 的关系</b>：完全不修改 {@code RecoveryTestGraph}（其 6 个测试行为保持不变）；
 * 本图结构上沿用 Step 2 已验证的写法（{@code StateGraph(schema, serializer)} +
 * {@code AsyncNodeAction.node_async} + {@code Channels.base/appender}）。
 * <p>
 * <b>State Channel 语义</b>：
 * <ul>
 *   <li>{@code taskId / totalItems / totalBatches / nextBatchIndex / status} →
 *       {@link Channels#base} <b>last-write</b>；故 {@code nextBatchIndex} 必须返回<b>绝对值</b></li>
 *   <li>{@code completedBatches / executionLog} → {@link Channels#appender} <b>追加</b>；
 *       节点只返回"本次新增"的元素</li>
 * </ul>
 * <b>为什么把崩溃点做进节点</b>：任务书要求区分「节点内崩溃」与「节点完成后崩溃」，
 * 并单独验证「结果已落库但 Checkpoint 未提交」。这两者的差别只在<b>崩溃发生在节点返回
 * 之前还是之后</b>，因此由 {@link CrashPolicy} 精确控制：
 * <ul>
 *   <li>{@link CrashPolicy.Mode#BEFORE_BATCH}：进入批次、<b>尚未落库</b>就 halt
 *       ⇒ 等价于"上一批已完成且 Checkpoint 已提交、本批还没开始"</li>
 *   <li>{@link CrashPolicy.Mode#AFTER_RESULT_PERSIST}：结果<b>已落库</b>、但节点尚未返回
 *       ⇒ Checkpoint 未提交，恢复后本批必然被重跑（幂等场景）</li>
 * </ul>
 * {@code halt} 只允许在独立子进程（{@code BatchCrashMain}）里启用；Spring 装配用 {@code none()}。
 */
public class BatchRecoveryGraph {

    public static final String NODE_PREPARE = "prepare";
    public static final String NODE_BATCH = "batch";
    public static final String NODE_FINISH = "finish";

    /** 模拟 Embedding 的批大小（任务书指定的 32） */
    public static final int BATCH_SIZE = 32;

    private final BatchResultStore store;
    private final CrashPolicy crashPolicy;
    /**
     * 动态中断点：{@code >=0} 时，batch 节点会在处理<b>该下标</b>批次之前中断
     * （通过 {@code InterruptibleAction.interrupt(nodeId, state)} 实现，{@code -1}=不中断）。
     * 与 {@code CompileConfig.interruptBefore("batch")} 的"每批都停"形成对照实验。
     */
    private final int interruptBeforeBatch;

    public BatchRecoveryGraph(BatchResultStore store) {
        this(store, CrashPolicy.none(), -1);
    }

    public BatchRecoveryGraph(BatchResultStore store, CrashPolicy crashPolicy) {
        this(store, crashPolicy, -1);
    }

    public BatchRecoveryGraph(BatchResultStore store, CrashPolicy crashPolicy, int interruptBeforeBatch) {
        this.store = store;
        this.crashPolicy = crashPolicy;
        this.interruptBeforeBatch = interruptBeforeBatch;
    }

    /** state schema（key → Channel，含 Reducer 语义）。 */
    public static Map<String, Channel<?>> schema() {
        Map<String, Channel<?>> schema = new HashMap<>();
        schema.put(BatchRecoveryState.TASK_ID, Channels.<String>base(() -> ""));
        schema.put(BatchRecoveryState.TOTAL_ITEMS, Channels.<Integer>base(() -> 0));
        schema.put(BatchRecoveryState.TOTAL_BATCHES, Channels.<Integer>base(() -> 0));
        schema.put(BatchRecoveryState.NEXT_BATCH_INDEX, Channels.<Integer>base(() -> 0));
        schema.put(BatchRecoveryState.STATUS, Channels.<String>base(() -> "NEW"));
        schema.put(BatchRecoveryState.RESUME_APPROVED, Channels.<Boolean>base(() -> false));
        schema.put(BatchRecoveryState.COMPLETED_BATCHES,
                Channels.<List<Integer>>appender(ArrayList::new));
        schema.put(BatchRecoveryState.EXECUTION_LOG,
                Channels.<List<String>>appender(ArrayList::new));
        return schema;
    }

    public StateGraph<BatchRecoveryState> build() throws GraphStateException {
        StateGraph<BatchRecoveryState> graph =
                new StateGraph<>(schema(), new BatchRecoveryStateSerializer());

        graph.addNode(NODE_PREPARE, AsyncNodeAction.node_async(this::prepare));
        graph.addNode(NODE_BATCH, new BatchNode());
        graph.addNode(NODE_FINISH, AsyncNodeAction.node_async(this::finish));

        graph.addEdge(StateGraph.START, NODE_PREPARE);
        graph.addEdge(NODE_PREPARE, NODE_BATCH);
        // 循环：batch 用条件边决定回到自己还是收尾
        graph.addConditionalEdges(NODE_BATCH, AsyncEdgeAction.edge_async(this::routeAfterBatch),
                Map.of(NODE_BATCH, NODE_BATCH, NODE_FINISH, NODE_FINISH));
        graph.addEdge(NODE_FINISH, StateGraph.END);
        return graph;
    }

    // ==================== 节点（只返回增量 Map） ====================

    private Map<String, Object> prepare(BatchRecoveryState state) {
        int totalBatches = (state.totalItems() + BATCH_SIZE - 1) / BATCH_SIZE;
        System.out.println("[EXEC] node=prepare taskId=" + state.taskId()
                + " totalItems=" + state.totalItems() + " totalBatches=" + totalBatches);

        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put(BatchRecoveryState.TOTAL_BATCHES, totalBatches);
        delta.put(BatchRecoveryState.NEXT_BATCH_INDEX, 0);   // base ⇒ 绝对值
        delta.put(BatchRecoveryState.STATUS, totalBatches == 0 ? "ALL_BATCHES_DONE" : "RUNNING");
        delta.put(BatchRecoveryState.EXECUTION_LOG, List.of("prepare:totalBatches=" + totalBatches));
        return delta;
    }

    /** 处理一个批次：读游标 → 落库（幂等）→ 返回增量 → 由条件边决定下一跳。 */
    private Map<String, Object> batch(BatchRecoveryState state) {
        int index = state.nextBatchIndex();
        int start = index * BATCH_SIZE;
        int itemCount = Math.min(BATCH_SIZE, state.totalItems() - start);
        String taskId = state.taskId();

        if (crashPolicy.atBeforeBatch(index)) {
            halt("BEFORE_BATCH(结果未落库)", taskId, index);
        }

        boolean inserted;
        try {
            inserted = store.insertResult(taskId, index, itemCount);
        } catch (Exception e) {
            throw new IllegalStateException("批次结果落库失败 taskId=" + taskId + " batchIndex=" + index, e);
        }
        // 该行日志是幂等的直接证据：恢复重跑时同一批次会打印 DUPLICATE_SKIPPED
        System.out.println("[EXEC] node=batch taskId=" + taskId + " batchIndex=" + index
                + " items=" + itemCount + " result=" + (inserted ? "INSERTED" : "DUPLICATE_SKIPPED"));

        int next = index + 1;
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put(BatchRecoveryState.NEXT_BATCH_INDEX, next);                  // base ⇒ 绝对值
        delta.put(BatchRecoveryState.COMPLETED_BATCHES, List.of(index));       // appender ⇒ 只追加本次
        delta.put(BatchRecoveryState.STATUS,
                next >= state.totalBatches() ? "ALL_BATCHES_DONE" : "RUNNING");
        delta.put(BatchRecoveryState.EXECUTION_LOG, List.of("batch#" + index + " items=" + itemCount
                + (inserted ? " RESULT_INSERTED" : " RESULT_DUPLICATE_SKIPPED")));

        if (crashPolicy.atAfterResultPersist(index)) {
            halt("AFTER_RESULT_PERSIST(Checkpoint 未提交)", taskId, index);
        }
        return delta;
    }

    private Map<String, Object> finish(BatchRecoveryState state) {
        System.out.println("[EXEC] node=finish taskId=" + state.taskId()
                + " completed=" + state.completedBatches());
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put(BatchRecoveryState.STATUS, "FINISHED");
        delta.put(BatchRecoveryState.EXECUTION_LOG, List.of(
                "finish:completed=" + state.completedBatches().size() + "/" + state.totalBatches()));
        return delta;
    }

    /** 条件边：游标到底则收尾，否则继续下一批。 */
    private String routeAfterBatch(BatchRecoveryState state) {
        return state.nextBatchIndex() >= state.totalBatches() ? NODE_FINISH : NODE_BATCH;
    }

    /**
     * batch 节点：同时实现 {@link AsyncNodeAction}（正常执行一个批次）与
     * {@link InterruptibleAction}（可在<b>指定批次之前</b>请求中断，用于场景 B2）。
     */
    private final class BatchNode
            implements AsyncNodeAction<BatchRecoveryState>, InterruptibleAction<BatchRecoveryState> {

        @Override
        public CompletableFuture<Map<String, Object>> apply(BatchRecoveryState state) {
            return CompletableFuture.completedFuture(batch(state));
        }

        @Override
        public Optional<InterruptionMetadata<BatchRecoveryState>> interrupt(String nodeId,
                BatchRecoveryState state) {
            // 实测要点：该条件在【每次】进入节点时重新求值。
            // 仅 resume()（不带数据）时条件恒成立 ⇒ 无限中断；必须用 resume(Map) 传入放行标记。
            if (interruptBeforeBatch >= 0 && state.nextBatchIndex() == interruptBeforeBatch
                    && !state.resumeApproved()) {
                System.out.println("[INTERRUPT] pause before batchIndex=" + interruptBeforeBatch
                        + " nodeId=" + nodeId + " taskId=" + state.taskId());
                return Optional.of(InterruptionMetadata.builder(nodeId, state)
                        .reason("pause-before-batch-" + interruptBeforeBatch)
                        .build());
            }
            if (interruptBeforeBatch >= 0 && state.nextBatchIndex() == interruptBeforeBatch) {
                System.out.println("[INTERRUPT] pause lifted by resumeApproved=true batchIndex="
                        + interruptBeforeBatch);
            }
            return Optional.empty();
        }
    }

    private static void halt(String where, String taskId, int batchIndex) {
        System.out.println("[CRASH] Runtime.halt(137) at " + where + " taskId=" + taskId
                + " batchIndex=" + batchIndex);
        System.out.flush();
        Runtime.getRuntime().halt(137);
    }

    /** 崩溃注入策略（默认 {@code none()}，仅子进程实验使用）。 */
    public record CrashPolicy(Mode mode, int batchIndex) {

        public enum Mode {
            NONE,
            /** 进入指定批次、结果落库之前崩溃 */
            BEFORE_BATCH,
            /** 指定批次结果已落库、但节点尚未返回（Checkpoint 未提交）时崩溃 */
            AFTER_RESULT_PERSIST
        }

        public static CrashPolicy none() {
            return new CrashPolicy(Mode.NONE, -1);
        }

        public static CrashPolicy beforeBatch(int batchIndex) {
            return new CrashPolicy(Mode.BEFORE_BATCH, batchIndex);
        }

        public static CrashPolicy afterResultPersist(int batchIndex) {
            return new CrashPolicy(Mode.AFTER_RESULT_PERSIST, batchIndex);
        }

        boolean atBeforeBatch(int index) {
            return mode == Mode.BEFORE_BATCH && batchIndex == index;
        }

        boolean atAfterResultPersist(int index) {
            return mode == Mode.AFTER_RESULT_PERSIST && batchIndex == index;
        }
    }
}
