package com.example.ragdemo.agent.graph;

import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;

/**
 * Agent 图状态的 JSON 序列化器（Step 4）。
 * <p>
 * <b>不再使用 {@code declareTransientAttributes}</b>：Step 3 用它绕过"final record 经 JSON 往返退化成
 * {@code LinkedHashMap}"的问题，但那会让这些字段<b>被序列化器排除</b> ⇒ 写进 PostgreSQL Checkpoint 时
 * 丢失、跨 JVM 读回为空（实测症状：标量字段正确、JSON 字段全空）。
 * <p>
 * Step 4 已把复杂结构改为 {@link AgentStateCodec} 的 JSON <b>字符串</b>（原生类型），
 * 因此无需任何 transient 声明即可完整往返；schemaVersion 门禁见 {@link RagAgentState#schemaCompatible()}。
 * <p>
 * 该 ObjectMapper 只服务本图状态（不注入 Spring、不影响其它模块）。
 */
public class AgentStateSerializer extends JacksonStateSerializer<RagAgentState> {

    public AgentStateSerializer() {
        super(RagAgentState::new);
    }
}
