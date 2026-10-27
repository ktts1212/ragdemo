package com.example.ragdemo.workflow.poc.batch;

import java.util.List;
import java.util.Map;

import org.bsc.langgraph4j.state.AgentState;

/**
 * Step 3 批处理图状态（与 Step 2 的 {@code RecoveryTestState} 完全隔离，互不影响）。
 * <p>
 * 模拟 Embedding 分批（每批 32 chunk）的进度状态。
 * <b>不</b>存放向量或批次正文（大对象不得进 Graph State，见 step3 任务书 §三.3）。
 * <p>
 * 字段与 Channel 语义的对应关系见 {@link BatchRecoveryGraph#schema()}：
 * <ul>
 *   <li>last-write（{@code Channels.base}）：{@code taskId / totalItems / totalBatches /
 *       nextBatchIndex / status}</li>
 *   <li>appender（{@code Channels.appender}）：{@code completedBatches / executionLog}</li>
 * </ul>
 */
public class BatchRecoveryState extends AgentState {

    public static final String TASK_ID = "taskId";
    /** 本次要处理的总条目数（模拟 chunk 数），用于算出总批数 */
    public static final String TOTAL_ITEMS = "totalItems";
    public static final String TOTAL_BATCHES = "totalBatches";
    /** 循环游标：下一个要处理的批次下标（从 0 开始）。必须返回【绝对值】 */
    public static final String NEXT_BATCH_INDEX = "nextBatchIndex";
    /** appender：每个批次处理后追加自己的下标；恢复后不得出现重复项 */
    public static final String COMPLETED_BATCHES = "completedBatches";
    public static final String EXECUTION_LOG = "executionLog";
    public static final String STATUS = "status";
    /**
     * 动态中断的"放行"标记。<b>场景 B2 实测结论</b>：batch 节点实现
     * {@code InterruptibleAction} 时，中断条件会在<b>每次</b>进入节点时重新求值；
     * 若只用 {@code GraphInput.resume()}（不带数据），条件恒成立 ⇒ 无限中断、永不推进。
     * 必须用 {@code GraphInput.resume(Map.of(RESUME_APPROVED, true))} 把标记写进状态，
     * 让中断条件失效，才能继续执行。
     */
    public static final String RESUME_APPROVED = "resumeApproved";

    public BatchRecoveryState(Map<String, Object> initData) {
        super(initData);
    }

    public String taskId() {
        return this.<String>value(TASK_ID).orElse("");
    }

    public int totalItems() {
        return this.<Integer>value(TOTAL_ITEMS, 0);
    }

    public int totalBatches() {
        return this.<Integer>value(TOTAL_BATCHES, 0);
    }

    public int nextBatchIndex() {
        return this.<Integer>value(NEXT_BATCH_INDEX, 0);
    }

    public List<Integer> completedBatches() {
        return this.<List<Integer>>value(COMPLETED_BATCHES).orElse(List.of());
    }

    public List<String> executionLog() {
        return this.<List<String>>value(EXECUTION_LOG).orElse(List.of());
    }

    public String status() {
        return this.<String>value(STATUS, "NEW");
    }

    /** 动态中断放行标记，见 {@link #RESUME_APPROVED}。 */
    public boolean resumeApproved() {
        return this.<Boolean>value(RESUME_APPROVED, false);
    }
}
