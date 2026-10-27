package com.example.ragdemo.agent.runtime;

import java.util.List;

import com.example.ragdemo.agent.model.AgentSource;
import com.example.ragdemo.agent.model.AgentTraceEntry;

/**
 * 一次 Agent Run 的最终结果（服务层 → API 层）。
 * <p>
 * {@code sources} 只来自本 Run 的真实工具结果；{@code handles} 是可由后续系统使用的受控句柄
 * （PDF / 页面图像），当前<b>没有</b>模型端消费能力，不代表任何视觉核验。
 */
public record AgentRunResult(String runId, String conversationId, long knowledgeBaseId, boolean ok,
        String answer, List<AgentSource> sources, List<String> usedTools, String finishReason,
        List<String> warnings, List<String> errors, List<AgentTraceEntry> trace, List<String> handles,
        int toolCallsUsed, int llmTurnsUsed, long elapsedMs) {

    public AgentRunResult {
        sources = sources == null ? List.of() : List.copyOf(sources);
        usedTools = usedTools == null ? List.of() : List.copyOf(usedTools);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        errors = errors == null ? List.of() : List.copyOf(errors);
        trace = trace == null ? List.of() : List.copyOf(trace);
        handles = handles == null ? List.of() : List.copyOf(handles);
        answer = answer == null ? "" : answer;
    }
}
