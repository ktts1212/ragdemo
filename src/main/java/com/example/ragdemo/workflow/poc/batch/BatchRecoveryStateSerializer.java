package com.example.ragdemo.workflow.poc.batch;

import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;

/**
 * 批处理状态的 JSON 文本序列化器 —— 与 Step 2 的
 * {@code RecoveryTestStateSerializer} 同构（复用其已验证的结论）。
 * <p>
 * <b>为什么必须另建一个子类而不是共用 Step 2 的</b>：{@code PostgresSaverV2} 用
 * <b>它自己持有的</b> {@code StateSerializer} 解码 {@code LG4JCheckpoint.state_data}，
 * 而两个图的状态类型不同（{@code RecoveryTestState} vs {@link BatchRecoveryState}）。
 * 复用同一个 saver/serializer 会导致解码成错误类型。
 * 因此每个图配一个 saver 实例，并用<b>独立 threadId 命名空间</b>
 * （{@code poc-recovery-} / {@code poc-batch-}）保证互不误读 —— 这也是 step3
 * 任务书要求独立命名空间的根本原因。
 */
public class BatchRecoveryStateSerializer extends JacksonStateSerializer<BatchRecoveryState> {

    public BatchRecoveryStateSerializer() {
        super(BatchRecoveryState::new);
    }
}
