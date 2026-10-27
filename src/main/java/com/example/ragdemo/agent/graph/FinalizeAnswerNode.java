package com.example.ragdemo.agent.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.ragdemo.agent.llm.AgentChatClient;
import com.example.ragdemo.agent.model.AgentFinishReason;
import com.example.ragdemo.agent.model.AgentMessage;
import com.example.ragdemo.agent.model.AgentSource;
import com.example.ragdemo.agent.runtime.AgentLimits;

import lombok.extern.slf4j.Slf4j;

/**
 * 收敛节点（Step 3 §5/§6）：产出最终答案、终止原因与 warnings。
 * <p>
 * <ul>
 *   <li>若决策节点已给出"无工具调用的纯文本"，直接采用（不再多花一次 LLM 调用）；</li>
 *   <li>否则做一次<b>不提供工具</b>的收敛调用，强制基于已有证据作答；</li>
 *   <li>模型/接口失败时用确定性兜底文案（列出真实来源，不编造）；</li>
 *   <li>输出受 {@code maxOutputChars} 限制；引用编号超出本轮来源即记 warning（不伪造 citation）。</li>
 * </ul>
 */
@Slf4j
public class FinalizeAnswerNode {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,3})]");

    private final AgentChatClient chatClient;
    private final AgentLimits limits;
    private final com.example.ragdemo.agent.runtime.AgentLeaseGuard guard;

    public FinalizeAnswerNode(AgentChatClient chatClient, AgentLimits limits) {
        this(chatClient, limits, null);
    }

    /** Step 4E-4：注入执行权守卫（{@code null} = 无守卫，内存态/非 Durable 路径）。 */
    public FinalizeAnswerNode(AgentChatClient chatClient, AgentLimits limits,
            com.example.ragdemo.agent.runtime.AgentLeaseGuard guard) {
        this.chatClient = chatClient;
        this.limits = limits;
        this.guard = guard;
    }

    private void assertHeld(String where) {
        if (guard != null) {
            guard.assertHeld(where);
        }
    }

    public Map<String, Object> apply(RagAgentState state) {
        // 收敛节点也要守卫：失权者不得产出"可信回答"
        assertHeld("finalize:entry");
        Map<String, Object> delta = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>(state.warnings());
        String finishReason = state.finishReason().isBlank()
                ? AgentFinishReason.ANSWERED.name()
                : state.finishReason();

        String answer = state.lastAiText();
        if (answer == null || answer.isBlank()) {
            boolean budgetExhausted = AgentFinishReason.BUDGET_EXHAUSTED.name().equals(finishReason)
                    || AgentFinishReason.LOOP_DETECTED.name().equals(finishReason)
                    || AgentFinishReason.TOOL_FAILURE.name().equals(finishReason);
            List<AgentMessage> messages = new ArrayList<>(state.messages());
            messages.add(AgentMessage.user(AgentPrompts.finalizeInstruction(budgetExhausted,
                    state.sources().size(), state.toolCallsUsed())));
            try {
                var reply = chatClient.chat(messages, List.of());
                answer = reply == null ? "" : reply.text();
            } catch (RuntimeException e) {
                log.error("[agent] 收敛阶段 LLM 调用失败: runId={}, type={}", state.runId(),
                        e.getClass().getSimpleName());
                warnings.add("LLM_ERROR: 收敛阶段模型调用失败，已改用确定性兜底说明");
                if (AgentFinishReason.ANSWERED.name().equals(finishReason)) {
                    finishReason = AgentFinishReason.ERROR.name();
                }
            }
        }
        if (answer == null || answer.isBlank()) {
            answer = fallbackAnswer(state, finishReason);
        }

        if (answer.length() > limits.maxOutputChars()) {
            answer = answer.substring(0, limits.maxOutputChars());
            warnings.add("ANSWER_TRUNCATED: 回答超过 " + limits.maxOutputChars() + " 字符已截断");
        }
        warnings.addAll(validateCitations(answer, state.sources().size()));

        // 提交终态前再确认执行权：失权绝不能把结果写成可信 ANSWERED
        assertHeld("finalize:before-commit");
        delta.put(RagAgentState.FINAL_ANSWER, answer);
        delta.put(RagAgentState.FINISH_REASON, finishReason);
        delta.put(RagAgentState.WARNINGS, AgentStateCodec.encode(warnings));
        return delta;
    }

    /** 引用校验：文本中的 [n] 必须落在本轮真实来源范围内。 */
    private List<String> validateCitations(String answer, int sourceCount) {
        List<String> warnings = new ArrayList<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index < 1 || index > sourceCount) {
                warnings.add("CITATION_OUT_OF_RANGE: 回答引用了 [" + index + "]，但本轮只有 "
                        + sourceCount + " 条来源");
            }
        }
        return warnings;
    }

    /** 确定性兜底：只陈述真实存在的来源与限制，不生成学术结论。 */
    private String fallbackAnswer(RagAgentState state, String finishReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("未能生成模型回答（原因：").append(finishReason).append("）。");
        List<AgentSource> sources = state.sources();
        if (sources.isEmpty()) {
            sb.append("本次执行没有收集到任何可审计证据，因此无法回答该问题。");
        } else {
            sb.append("本次已收集 ").append(sources.size()).append(" 条可审计来源：");
            int index = 1;
            for (AgentSource source : sources) {
                sb.append("\n- [").append(index++).append("] documentId=").append(source.documentId())
                        .append(", page=").append(source.pageNumber() == null ? "未确定" : source.pageNumber())
                        .append(", sourceType=").append(source.sourceType());
            }
            sb.append("\n请依据上述来源人工核对；本次未做结论性推断。");
        }
        return sb.toString();
    }
}
