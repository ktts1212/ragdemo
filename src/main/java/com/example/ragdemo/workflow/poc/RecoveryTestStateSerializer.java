package com.example.ragdemo.workflow.poc;

import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;

/**
 * PoC 状态的 JSON 文本序列化器。
 * <p>
 * <b>为什么需要这个子类</b>：{@code JacksonStateSerializer} 在 1.9.4 中是
 * <b>abstract</b>（构造器 protected），不能直接实例化，必须提供具体子类。
 * <p>
 * <b>为什么选 Jackson（文本）而不是 ObjectStream（二进制）</b>：
 * PostgresSaverV2 的自带 DDL（jar 内 {@code db/migration/v2.1__init.sql}）把
 * {@code LG4JCheckpoint.state_data} 定义为 <b>TEXT</b>，并把序列化格式记录在
 * {@code state_content_type}。{@link org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer}
 * 产生二进制流，与 TEXT 列语义不符；{@code JacksonStateSerializer} 产出 JSON 字符串，
 * 且 {@code contentType()} 返回 JSON 类型 —— 两者匹配。实际验证见
 * {@code RecoveryTestGraphTest#storedStateIsJsonTextCompatible}。
 */
public class RecoveryTestStateSerializer extends JacksonStateSerializer<RecoveryTestState> {

    public RecoveryTestStateSerializer() {
        super(RecoveryTestState::new);
    }
}
