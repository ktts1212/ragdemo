package com.example.ragdemo.agent.model;

import java.util.List;

/**
 * 一次 LLM 决策的规范化回复。
 *
 * @param text         文本内容（可能为空，例如纯 tool call）
 * @param toolCalls    本轮的 tool execution requests（可为 0/1/多个）
 * @param finishReason 供应商 finish reason 名称（如 TOOL_EXECUTION / STOP / LENGTH）
 */
public record AgentLlmReply(String text, List<AgentToolCall> toolCalls, String finishReason) {

    public AgentLlmReply {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
