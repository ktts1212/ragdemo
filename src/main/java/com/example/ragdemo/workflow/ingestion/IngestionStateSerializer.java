package com.example.ragdemo.workflow.ingestion;

import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;

/**
 * 入库图状态的 JSON 文本序列化器（与 Step 2/3 同构，复用其已验证结论）。
 * <p>
 * 状态里只有字符串/数字/布尔与引用 key，序列化后的 {@code state_data} 始终很小；
 * 大对象（PDF/Marker JSON/Canonical/Chunk 列表/向量）不进 State，见 {@link IngestionState}。
 */
public class IngestionStateSerializer extends JacksonStateSerializer<IngestionState> {

    public IngestionStateSerializer() {
        super(IngestionState::new);
    }
}
