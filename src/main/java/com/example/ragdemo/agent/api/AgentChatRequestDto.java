package com.example.ragdemo.agent.api;

/**
 * Agent 聊天请求。
 * <p>
 * <b>授权告知</b>：{@code knowledgeBaseId} 来自 HTTP，<b>不可信</b>；服务端会用
 * {@code rag.agent.allowed-knowledge-base-ids} 白名单校验（当前项目没有用户级 RBAC，
 * 因此生产环境应只配置单一受控知识库，或在受控网络内开放该 API）。
 *
 * @param question       用户问题（必填）
 * @param knowledgeBaseId 目标知识库（可空 ⇒ 取白名单第一个）；不在白名单则拒绝
 * @param conversationId 仅作为日志/trace 元数据，本步<b>不</b>读取跨请求历史
 */
public record AgentChatRequestDto(String question, Long knowledgeBaseId, String conversationId,
        String requestKey) {

    /** 兼容旧调用的三参构造（requestKey 缺省为 null）。 */
    public AgentChatRequestDto(String question, Long knowledgeBaseId, String conversationId) {
        this(question, knowledgeBaseId, conversationId, null);
    }
}
