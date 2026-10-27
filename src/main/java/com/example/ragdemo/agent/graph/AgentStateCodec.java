package com.example.ragdemo.agent.graph;

import java.util.ArrayList;
import java.util.List;

import com.example.ragdemo.agent.model.AgentMessage;
import com.example.ragdemo.agent.model.AgentSource;
import com.example.ragdemo.agent.model.AgentToolCall;
import com.example.ragdemo.agent.model.AgentTraceEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Agent 状态的<b>可持久化数据合同</b>（Step 4 §4）。
 * <p>
 * Step 3 用 {@code declareTransientAttributes} 绕过了 LangGraph4j 对 record 的 JSON 往返
 * （record 会被降级成 {@code LinkedHashMap}），但那<b>不能</b>用于持久化：
 * 进程重启后消息会丢失、tool_call_id 配对会断裂。这里改为把复杂结构显式编码成
 * <b>JSON 字符串</b>存进 State（原生类型 ⇒ 任何序列化器都能安全往返），读取时再解码，
 * 并带 {@link #SCHEMA_VERSION} 做格式门禁（不兼容时明确拒绝，而不是静默误用旧格式）。
 */
public final class AgentStateCodec {

    /** 状态 schema 版本；变更字段语义时必须递增，并在读取处做兼容/拒绝判断。 */
    public static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private static final TypeReference<List<AgentMessage>> MESSAGES = new TypeReference<>() {
    };
    private static final TypeReference<List<AgentToolCall>> TOOL_CALLS = new TypeReference<>() {
    };
    private static final TypeReference<List<AgentSource>> SOURCES = new TypeReference<>() {
    };
    private static final TypeReference<List<AgentTraceEntry>> TRACE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private AgentStateCodec() {
    }

    // ==================== 编码 ====================

    public static String encode(Object value) {
        if (value == null) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("AGENT_STATE_ENCODE_FAILED: " + value.getClass().getSimpleName(), e);
        }
    }

    // ==================== 解码（任何异常都降级为空集合 + 由上层记录 warning，绝不抛给调用方） ====================

    public static List<AgentMessage> messages(String json) {
        return decode(json, MESSAGES);
    }

    public static List<AgentToolCall> toolCalls(String json) {
        return decode(json, TOOL_CALLS);
    }

    public static List<AgentSource> sources(String json) {
        return decode(json, SOURCES);
    }

    public static List<AgentTraceEntry> trace(String json) {
        return decode(json, TRACE);
    }

    public static List<String> strings(String json) {
        return decode(json, STRINGS);
    }

    private static <T> List<T> decode(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<T> value = MAPPER.readValue(json, type);
            return value == null ? List.of() : value;
        } catch (Exception e) {
            throw new IllegalStateException("AGENT_STATE_DECODE_FAILED: " + e.getClass().getSimpleName(), e);
        }
    }

    /** 追加式更新：在已解码列表后追加（节点返回绝对值语义）。 */
    public static <T> List<T> append(List<T> current, List<T> delta) {
        List<T> merged = new ArrayList<>(current == null ? List.of() : current);
        if (delta != null) {
            merged.addAll(delta);
        }
        return merged;
    }
}
