package com.example.ragdemo.agent.model;

import java.util.List;

/**
 * Agent 运行态消息（<b>不</b>使用 LangChain4j 类型，便于 JSON 序列化并留给 Step 4 做 Checkpoint）。
 * <p>
 * 只保留协议必需字段：角色、文本、AI 的 tool calls、工具结果关联的 requestId/toolName、
 * 以及"内容被裁剪"标记 —— 不放大对象（PDF bytes / Canonical 整篇 / 图片）。
 */
public record AgentMessage(AgentMessageRole role, String text, List<AgentToolCall> toolCalls,
        String toolCallId, String toolName, boolean truncated) {

    public AgentMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        text = text == null ? "" : text;
    }

    public static AgentMessage system(String text) {
        return new AgentMessage(AgentMessageRole.SYSTEM, text, List.of(), null, null, false);
    }

    public static AgentMessage user(String text) {
        return new AgentMessage(AgentMessageRole.USER, text, List.of(), null, null, false);
    }

    public static AgentMessage assistant(String text, List<AgentToolCall> toolCalls) {
        return new AgentMessage(AgentMessageRole.AI, text, toolCalls, null, null, false);
    }

    public static AgentMessage tool(String toolCallId, String toolName, String text, boolean truncated) {
        return new AgentMessage(AgentMessageRole.TOOL, text, List.of(), toolCallId, toolName, truncated);
    }

    /** 估算字符数，用于上下文预算。 */
    public int charCount() {
        return text.length();
    }
}
