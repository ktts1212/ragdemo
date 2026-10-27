package com.example.ragdemo.agent.graph;

import java.util.List;
import java.util.Map;

import org.bsc.langgraph4j.state.AgentState;

import com.example.ragdemo.agent.model.AgentMessage;
import com.example.ragdemo.agent.model.AgentSource;
import com.example.ragdemo.agent.model.AgentToolCall;
import com.example.ragdemo.agent.model.AgentTraceEntry;

/**
 * 单 Agent 图状态（Step 3 §5 / Step 4 §4）。
 * <p>
 * <b>持久化数据合同</b>：复杂结构（消息、tool calls、来源、trace、错误/警告、循环指纹）以
 * <b>JSON 字符串</b>存放（编解码见 {@link AgentStateCodec}），其余为原生 String/Integer/Long ⇒
 * 任何序列化器（含 {@code PostgresSaverV2}）都能安全往返，不再依赖
 * {@code declareTransientAttributes}（那会导致重启后消息丢失、tool_call_id 配对断裂）。
 * <p>
 * 逐字段合同（frozen）：
 * <pre>
 * agentStateSchemaVersion int          状态格式版本（不兼容时拒绝读取）
 * runId / conversationId / question    String
 * knowledgeBaseId                      Long（仅校验快照，恢复时不得用于创建 Toolkit）
 * messages                             JSON List&lt;AgentMessage(role,text,toolCalls[id,name,arguments],
 *                                                           toolCallId,toolName,truncated)&gt;
 * pendingToolCalls                     JSON List&lt;AgentToolCall&gt;
 * callFingerprints                     JSON List&lt;String&gt;（循环去重）
 * sources                              JSON List&lt;AgentSource&gt;
 * trace                                JSON List&lt;AgentTraceEntry&gt;
 * errors / warnings / handles          JSON List&lt;String&gt;
 * toolCallsUsed / llmTurnsUsed /
 * duplicateCalls / contextChars        Integer
 * lastAiText / finishReason /
 * finalAnswer                          String
 * </pre>
 * 不放 PDF bytes / 图片 bytes / 整篇 Canonical / 句柄密钥；工具实例与 Spring Bean 绝不入状态。
 * <p>
 * Channel 语义：全部 {@code Channels.base}（last-write），节点返回绝对值。
 */
public class RagAgentState extends AgentState {

    /** 状态 schema 版本（与 {@link AgentStateCodec#SCHEMA_VERSION} 对齐）。 */
    public static final String AGENT_STATE_SCHEMA_VERSION = "agentStateSchemaVersion";

    public static final String RUN_ID = "runId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String KNOWLEDGE_BASE_ID = "knowledgeBaseId";
    public static final String QUESTION = "question";
    public static final String MESSAGES = "messages";
    public static final String SOURCES = "sources";
    public static final String HANDLES = "handles";
    public static final String TRACE = "trace";
    public static final String ERRORS = "errors";
    public static final String WARNINGS = "warnings";
    public static final String CALL_FINGERPRINTS = "callFingerprints";
    public static final String PENDING_TOOL_CALLS = "pendingToolCalls";
    public static final String TOOL_CALLS_USED = "toolCallsUsed";
    public static final String LLM_TURNS_USED = "llmTurnsUsed";
    public static final String DUPLICATE_CALLS = "duplicateCalls";
    public static final String CONTEXT_CHARS = "contextChars";
    public static final String LAST_AI_TEXT = "lastAiText";
    public static final String FINISH_REASON = "finishReason";
    public static final String FINAL_ANSWER = "finalAnswer";
    /** Phase 3：新 Run 创建时冻结的 L3 上下文（Resume 不重新检索）。 */
    public static final String L3_CONTEXT = "l3Context";
    public static final String L3_SOURCES = "l3Sources";
    public static final String L3_APPLIED = "l3Applied";

    public RagAgentState(Map<String, Object> initData) {
        super(initData);
    }

    // ==================== 版本门禁 ====================

    /** 状态格式版本；缺失视为 0（调用方据此拒绝旧格式）。 */
    public int schemaVersion() {
        return this.<Number>value(AGENT_STATE_SCHEMA_VERSION, 0).intValue();
    }

    public boolean schemaCompatible() {
        return schemaVersion() == AgentStateCodec.SCHEMA_VERSION;
    }

    // ==================== 标量 ====================

    public String runId() {
        return this.<String>value(RUN_ID).orElse("");
    }

    public String conversationId() {
        return this.<String>value(CONVERSATION_ID).orElse(null);
    }

    public long knowledgeBaseId() {
        return this.<Number>value(KNOWLEDGE_BASE_ID, 0L).longValue();
    }

    public String question() {
        return this.<String>value(QUESTION).orElse("");
    }

    public int toolCallsUsed() {
        return this.<Integer>value(TOOL_CALLS_USED, 0);
    }

    public int llmTurnsUsed() {
        return this.<Integer>value(LLM_TURNS_USED, 0);
    }

    public int duplicateCalls() {
        return this.<Integer>value(DUPLICATE_CALLS, 0);
    }

    public int contextChars() {
        return this.<Integer>value(CONTEXT_CHARS, 0);
    }

    public String lastAiText() {
        return this.<String>value(LAST_AI_TEXT, "");
    }

    public String finishReason() {
        return this.<String>value(FINISH_REASON, "");
    }

    public String finalAnswer() {
        return this.<String>value(FINAL_ANSWER, "");
    }

    // ==================== Phase 3：L3 快照（只读；缺失时为空 ⇒ 旧 Checkpoint 兼容） ====================

    public String l3Context() {
        return this.<String>value(L3_CONTEXT, "");
    }

    public String l3Sources() {
        return this.<String>value(L3_SOURCES, "[]");
    }

    public boolean l3Applied() {
        return this.<Integer>value(L3_APPLIED, 0) == 1;
    }

    // ==================== JSON 结构（解码失败由 AgentStateCodec 抛出明确错误，不静默降级） ====================

    public List<AgentMessage> messages() {
        return AgentStateCodec.messages(raw(MESSAGES));
    }

    public List<AgentToolCall> pendingToolCalls() {
        return AgentStateCodec.toolCalls(raw(PENDING_TOOL_CALLS));
    }

    public List<AgentSource> sources() {
        return AgentStateCodec.sources(raw(SOURCES));
    }

    public List<AgentTraceEntry> trace() {
        return AgentStateCodec.trace(raw(TRACE));
    }

    public List<String> handles() {
        return AgentStateCodec.strings(raw(HANDLES));
    }

    public List<String> errors() {
        return AgentStateCodec.strings(raw(ERRORS));
    }

    public List<String> warnings() {
        return AgentStateCodec.strings(raw(WARNINGS));
    }

    public List<String> callFingerprints() {
        return AgentStateCodec.strings(raw(CALL_FINGERPRINTS));
    }

    private String raw(String key) {
        return this.<String>value(key, "[]");
    }
}
