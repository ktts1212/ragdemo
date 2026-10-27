package com.example.ragdemo.agent.llm;

import java.util.List;

import com.example.ragdemo.agent.model.AgentLlmReply;
import com.example.ragdemo.agent.model.AgentMessage;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * Agent 与 LLM 之间的最小适配层（可替换为假实现做单测，真实实现走 LangChain4j 0.36.2）。
 * <p>
 * 只暴露"带工具规格的一次决策"这一件事：工具规格为空即"不提供工具"（用于收敛阶段强制回答）。
 * 传统 {@code /api/chat} 的 ChatModel 用法不受影响。
 */
public interface AgentChatClient {

    /**
     * 发起一次决策调用。
     *
     * @param messages           本 Run 的协议消息（含工具结果回填）
     * @param toolSpecifications 可用工具规格（空列表表示本轮不提供工具）
     * @return 规范化回复（文本 + tool calls + finishReason）
     */
    AgentLlmReply chat(List<AgentMessage> messages, List<ToolSpecification> toolSpecifications);
}
