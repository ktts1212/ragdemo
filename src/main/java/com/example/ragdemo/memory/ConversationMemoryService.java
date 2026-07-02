package com.example.ragdemo.memory;

import com.example.ragdemo.model.entity.ChatConversation;
import com.example.ragdemo.model.entity.ConversationMessage;

import java.util.List;

/**
 * 会话记忆服务接口（一期：纯滑动窗口）
 */
public interface ConversationMemoryService {

    /**
     * 解析 conversationId：如果为空则创建新会话并入库，否则直接返回。
     *
     * @param conversationId  前端传的会话 ID（可为 null）
     * @param knowledgeBaseId 知识库 ID
     * @param modelKey        模型 key
     * @return 有效的 conversationId
     */
    String resolveConversationId(String conversationId, Long knowledgeBaseId, String modelKey);

    /**
     * 加载当前会话的最近 N 条消息（按时间倒序，在组装 ChatRequest 时反转为正序）
     *
     * @param conversationId 会话 ID
     * @param limit          消息条数上限
     * @return 最近 N 条消息（倒序）
     */
    List<ConversationMessage> loadRecentMessages(String conversationId, int limit);

    /**
     * 保存本轮问答：用户问题 + 助手回答。
     *
     * @param conversationId 会话 ID
     * @param userQuestion   用户原始问题
     * @param assistantAnswer 助手回答
     */
    void saveTurn(String conversationId, String userQuestion, String assistantAnswer);

    /**
     * 获取最近的会话列表（用于前端左侧历史列表）
     *
     * @param limit 最大返回条数
     * @return 会话列表（按更新时间倒序）
     */
    List<ChatConversation> listConversations(int limit);

    /**
     * 滚动摘要：用被挤出窗口的最早一轮问答，与旧摘要合并生成新摘要。
     *
     * @param conversationId  会话 ID
     * @param oldestQuestion  被挤出的用户问题
     * @param oldestAnswer    被挤出的助手回答
     */
    void updateSummary(String conversationId, String oldestQuestion, String oldestAnswer);

    /**
     * 获取会话的滚动摘要（可能为 null）。
     */
    String getSummary(String conversationId);

    /**
     * 统计会话的消息总数。
     */
    long countMessages(String conversationId);

    /**
     * 结构化状态：每轮对话后用本轮问答更新会话状态 JSON。
     * 从第一轮就开始跟踪，用于后续指代消解。
     */
    void updateSessionState(String conversationId, String question, String answer);

    /**
     * 获取会话的结构化状态 JSON 字符串（可能为 null）。
     */
    String getSessionState(String conversationId);

    /**
     * Query Rewrite：结合摘要、状态和最近对话，将指代不清的追问改写为完整独立问题。
     * 改写后的问题用于 embedding 检索，原始问题保留在 UserMessage 中传给 LLM。
     */
    String rewriteQuestion(String summary, String sessionState,
                           List<ConversationMessage> recentMessages, String question);
}
