package com.example.ragdemo.agent.llm;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ragdemo.agent.model.AgentLlmReply;
import com.example.ragdemo.agent.model.AgentMessage;
import com.example.ragdemo.agent.model.AgentToolCall;
import com.example.ragdemo.agent.runtime.AgentDiagnostics;
import com.example.ragdemo.agent.runtime.AgentMessageCodec;
import com.example.ragdemo.config.AgentProperties;
import com.example.ragdemo.config.ChatModelFactory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * 真实 LLM 适配（Step 3 §3）：复用既有 {@link ChatModelFactory}（provider/密钥/baseUrl/超时配置不变），
 * 仅按 0.36.2 实测签名调用 {@code ChatLanguageModel.chat(ChatRequest)} 并传入 tool specifications。
 * <p>
 * 不使用"让模型输出工具 JSON"的伪实现；也不改动传统 RAG 的 ChatModel 行为。
 * 日志只记录结构（工具名、数量、finishReason），不记录 AI Key 与完整对话。
 */
@Slf4j
@Component
public class LangChain4jAgentChatClient implements AgentChatClient {

    private final ChatModelFactory chatModelFactory;
    private final AgentProperties agentProperties;

    public LangChain4jAgentChatClient(ChatModelFactory chatModelFactory, AgentProperties agentProperties) {
        this.chatModelFactory = chatModelFactory;
        this.agentProperties = agentProperties;
    }

    @Override
    public AgentLlmReply chat(List<AgentMessage> messages, List<ToolSpecification> toolSpecifications) {
        List<ToolSpecification> specs = toolSpecifications == null ? List.of() : toolSpecifications;
        String modelKey = agentProperties.getModelKey() == null ? "<default>" : agentProperties.getModelKey();

        // 发送前先做协议自检：违反 OpenAI 兼容协议时供应商只会给出难懂的 400/NPE，
        // 这里提前给出明确原因（含脱敏结构），既便于定位也不会把无效请求发出去。
        List<AgentDiagnostics.Violation> violations = AgentDiagnostics.validateProtocol(messages);
        if (!violations.isEmpty()) {
            log.error("[agent-llm] 拒绝发送：消息协议违例 violations={}, modelKey={}, toolSpecs={}\n{}",
                    violations, modelKey, specs.size(), AgentDiagnostics.describeStructure(messages));
            throw new IllegalStateException("AGENT_PROTOCOL_VIOLATION: " + violations);
        }

        ChatLanguageModel model = chatModelFactory.getModel(agentProperties.getModelKey());
        log.debug("[agent-llm] 调用: modelKey={}, modelClass={}, messages={}, toolSpecs={}\n{}",
                modelKey, model.getClass().getSimpleName(), messages.size(), specs.size(),
                AgentDiagnostics.describeStructure(messages));

        try {
            // 注意：请求拼装也必须放在 try 内 —— 真实故障曾发生在这里（供应商返回缺失
            // id/arguments 的 tool call 时，LangChain4j 的 Builder.build() 会 NPE），
            // 放外面会导致堆栈被上层吞掉、只剩一个 type=NullPointerException。
            ChatRequest request = ChatRequest.builder()
                    .messages(AgentMessageCodec.toLangChain4j(messages))
                    .toolSpecifications(specs)
                    .build();
            ChatResponse response = model.chat(request);

            AiMessage aiMessage = response.aiMessage();
            if (aiMessage == null) {
                // 供应商返回空回复：不猜测，交给上层按"无 tool calls、无文本"处理
                log.warn("[agent-llm] 空回复: modelKey={}, finishReason={}", modelKey, response.finishReason());
                return new AgentLlmReply("", List.of(),
                        response.finishReason() == null ? null : response.finishReason().name());
            }
            // 实测边界：纯文本回复时 AiMessage.toolExecutionRequests() 返回 **null**（不是空列表），
            // 直接 .stream() 会 NPE（Step 3B 的故障根因）。此处必须 null-safe。
            List<AgentToolCall> toolCalls = (aiMessage.toolExecutionRequests() == null
                    ? List.<ToolExecutionRequest>of()
                    : aiMessage.toolExecutionRequests()).stream()
                    .map(request1 -> new AgentToolCall(request1.id(), request1.name(), request1.arguments()))
                    .toList();
            log.debug("[agent-llm] 响应: finishReason={}, toolCalls={}, contentShape={}",
                    response.finishReason(),
                    toolCalls.stream().map(AgentToolCall::name).toList(),
                    aiMessage.text() == null ? "null"
                            : (aiMessage.text().isEmpty() ? "empty" : "len=" + aiMessage.text().length()));
            return new AgentLlmReply(aiMessage.text(), toolCalls,
                    response.finishReason() == null ? null : response.finishReason().name());
        } catch (RuntimeException e) {
            // 关键诊断：完整但**脱敏**的堆栈 + 请求结构（不打印正文/句柄/密钥）
            log.error("[agent-llm] 调用失败: modelKey={}, modelClass={}, messages={}, toolSpecs={}, "
                            + "exception={}\nrequest structure:\n{}\nstack:\n{}",
                    modelKey, model.getClass().getSimpleName(), messages.size(), specs.size(),
                    e.getClass().getName(), AgentDiagnostics.describeStructure(messages),
                    AgentDiagnostics.stackSummary(e));
            throw e;
        }
    }
}
