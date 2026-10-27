package com.example.ragdemo.agent.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.ragdemo.agent.llm.AgentChatClient;
import com.example.ragdemo.agent.model.AgentFinishReason;
import com.example.ragdemo.agent.model.AgentLlmReply;
import com.example.ragdemo.agent.model.AgentMessage;
import com.example.ragdemo.agent.model.AgentToolCall;
import com.example.ragdemo.agent.runtime.AgentLimits;
import com.example.ragdemo.agent.runtime.ToolRegistry;

import lombok.extern.slf4j.Slf4j;

/**
 * 决策节点：把当前协议消息 + 工具规格交给 LLM，得到"继续调用工具"或"给出回答"。
 * <p>
 * 预算检查在<b>调用前</b>完成（轮次/上下文），工具额度在<b>派发前</b>再次确认；
 * 超限即写 {@code BUDGET_EXHAUSTED} 并清空待执行调用 ⇒ 条件边直接进入收敛节点，保证有限步终止。
 * 单次回复中的 0/1/多个 tool calls 都按原样写入状态（多调用由执行节点顺序执行）。
 */
@Slf4j
public class AgentDecisionNode {

    private final AgentChatClient chatClient;
    private final ToolRegistry registry;
    private final AgentLimits limits;
    private final com.example.ragdemo.agent.runtime.AgentLeaseGuard guard;

    public AgentDecisionNode(AgentChatClient chatClient, ToolRegistry registry, AgentLimits limits) {
        this(chatClient, registry, limits, null);
    }

    /** Step 4E-4：注入执行权守卫（{@code null} = 无守卫，内存态/非 Durable 路径）。 */
    public AgentDecisionNode(AgentChatClient chatClient, ToolRegistry registry, AgentLimits limits,
            com.example.ragdemo.agent.runtime.AgentLeaseGuard guard) {
        this.chatClient = chatClient;
        this.registry = registry;
        this.limits = limits;
        this.guard = guard;
    }

    private void assertHeld(String where) {
        if (guard != null) {
            guard.assertHeld(where);
        }
    }

    public Map<String, Object> apply(RagAgentState state) {
        // 执行权守卫：失权者不得启动新的 LLM 决策（抛 409 AGENT_LEASE_LOST，交由上层收敛为失败而非 ANSWERED）
        assertHeld("decide:entry");
        Map<String, Object> delta = new LinkedHashMap<>();

        // 已收敛（预算/循环/错误）：不再调用 LLM，交给条件边进入 finalize
        if (!state.finishReason().isBlank()) {
            return delta;
        }
        if (state.llmTurnsUsed() >= limits.maxLlmTurns()) {
            delta.put(RagAgentState.FINISH_REASON, AgentFinishReason.BUDGET_EXHAUSTED.name());
            delta.put(RagAgentState.PENDING_TOOL_CALLS, AgentStateCodec.encode(List.of()));
            delta.put(RagAgentState.ERRORS, AgentStateCodec.encode(List.of(
                    "BUDGET_EXHAUSTED: LLM 轮次已达上限 " + limits.maxLlmTurns())));
            return delta;
        }
        if (state.contextChars() >= limits.maxContextChars()) {
            delta.put(RagAgentState.FINISH_REASON, AgentFinishReason.BUDGET_EXHAUSTED.name());
            delta.put(RagAgentState.PENDING_TOOL_CALLS, AgentStateCodec.encode(List.of()));
            delta.put(RagAgentState.ERRORS, AgentStateCodec.encode(List.of(
                    "BUDGET_EXHAUSTED: 上下文已达上限 " + limits.maxContextChars() + " 字符")));
            return delta;
        }

        AgentLlmReply reply;
        try {
            reply = chatClient.chat(state.messages(), registry.specifications());
        } catch (RuntimeException e) {
            log.error("[agent] LLM 决策失败: runId={}, type={}", state.runId(), e.getClass().getSimpleName());
            delta.put(RagAgentState.FINISH_REASON, AgentFinishReason.ERROR.name());
            delta.put(RagAgentState.PENDING_TOOL_CALLS, AgentStateCodec.encode(List.of()));
            delta.put(RagAgentState.ERRORS, AgentStateCodec.encode(List.of(
                    "LLM_ERROR: 模型调用失败，无法继续工具决策")));
            return delta;
        }

        if (reply == null) {
            // 边界加固：适配层/供应商可能返回 null（异常实现、被替换的测试桩等）。
            // 当作"空回复"继续走正常收敛，避免 NPE 逃逸到 API 层变成 500。
            reply = new AgentLlmReply("", List.of(), "NULL_REPLY");
        }

        // 长耗时 LLM 返回后、继续处理结果前，再确认一次执行权（心跳可能已失权/被接管）
        assertHeld("decide:after-llm");

        boolean toolBudgetLeft = state.toolCallsUsed() < limits.maxToolCalls();
        if (reply.hasToolCalls() && !toolBudgetLeft) {
            // 模型还想调工具但额度已尽。
            // 协议要求"带 tool_calls 的 assistant 消息之后必须有对应的 tool 消息"，
            // 因此这里**不把该消息写入历史**，直接收敛去作答（否则下一次调用会被供应商拒绝）。
            delta.put(RagAgentState.FINISH_REASON, AgentFinishReason.BUDGET_EXHAUSTED.name());
            delta.put(RagAgentState.PENDING_TOOL_CALLS, AgentStateCodec.encode(List.of()));
            delta.put(RagAgentState.ERRORS, AgentStateCodec.encode(List.of(
                    "BUDGET_EXHAUSTED: 工具调用已达上限 " + limits.maxToolCalls()
                            + "（模型仍请求工具，未执行）")));
            return delta;
        }

        AgentMessage aiMessage = AgentMessage.assistant(reply.text(), reply.toolCalls());
        List<AgentMessage> messages = new java.util.ArrayList<>(state.messages());
        messages.add(aiMessage);

        // Step 4：状态内以 JSON 字符串承载复杂结构（可跨 JVM 完整回读）
        delta.put(RagAgentState.MESSAGES, AgentStateCodec.encode(messages));
        delta.put(RagAgentState.PENDING_TOOL_CALLS, AgentStateCodec.encode(reply.toolCalls()));
        delta.put(RagAgentState.LLM_TURNS_USED, state.llmTurnsUsed() + 1);
        // 只有"没有工具调用的纯文本回复"才可被直接当作最终答案
        delta.put(RagAgentState.LAST_AI_TEXT, reply.hasToolCalls() ? "" : reply.text());
        delta.put(RagAgentState.CONTEXT_CHARS, state.contextChars() + aiMessage.charCount());
        log.debug("[agent] decision: runId={}, turn={}, toolCalls={}, finishReason={}, pending={}",
                state.runId(), state.llmTurnsUsed() + 1, reply.toolCalls().size(), reply.finishReason(),
                reply.toolCalls().stream().map(AgentToolCall::name).toList());
        return delta;
    }
}
