package com.example.ragdemo.service.serviceImpl;

import com.example.ragdemo.config.ChatModelFactory;
import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.mapper.DocumentChunkMapper;
import com.example.ragdemo.memory.ConversationMemoryService;
import com.example.ragdemo.model.dto.ChatRequestDto;
import com.example.ragdemo.model.dto.ChatResponseDto;
import com.example.ragdemo.model.dto.RetrievedChunkDto;
import com.example.ragdemo.model.dto.SourceReference;
import com.example.ragdemo.service.ChatService;
import com.example.ragdemo.model.entity.ConversationMessage;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 智能问答服务实现（V1）。
 * <p>
 * 完整链路：参数校验 → 问题向量化 → pgvector 检索 → minScore 过滤
 * → 上下文拼接 → 严格 Prompt → LLM 生成 → 返回答案 + 来源引用。
 * <p>
 * 依赖：
 * <ul>
 * <li>{@link EmbeddingModel} — 问题向量化（替代手写 OpenAiClient.embed）</li>
 * <li>{@link dev.langchain4j.model.chat.ChatLanguageModel} — 通过
 * ChatModelFactory 动态选择</li>
 * <li>{@link DocumentChunkMapper} — pgvector 相似度检索</li>
 * </ul>
 */
@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    // ==================== 默认值常量 ====================
    private static final Long DEFAULT_KNOWLEDGE_BASE_ID = 1L;
    private static final int MAX_RECENT_MESSAGES = 16; // 8 轮对话 × 2

    // ==================== 依赖注入 ====================
    private final EmbeddingModel embeddingModel;
    private final ChatModelFactory chatModelFactory;
    private final DocumentChunkMapper documentChunkMapper;
    private final ConversationMemoryService conversationMemoryService;
    private final RagProperties ragProperties;

    /** System prompt 模板 */
    private String systemPromptTemplate;

    /** User prompt 模板（含 {summary} {context} {question} 占位符） */
    private String userPromptTemplate;

    @Value("classpath:prompt/system-prompt.txt")
    private Resource systemPromptResource;

    @Value("classpath:prompt/user-prompt.txt")
    private Resource userPromptResource;

    public ChatServiceImpl(EmbeddingModel embeddingModel,
            ChatModelFactory chatModelFactory,
            DocumentChunkMapper documentChunkMapper,
            ConversationMemoryService conversationMemoryService,
            RagProperties ragProperties) {
        this.embeddingModel = embeddingModel;
        this.chatModelFactory = chatModelFactory;
        this.documentChunkMapper = documentChunkMapper;
        this.conversationMemoryService = conversationMemoryService;
        this.ragProperties = ragProperties;
    }

    @PostConstruct
    private void loadPrompts() throws IOException {
        this.systemPromptTemplate = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.userPromptTemplate = userPromptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    // ==================== 主流程 ====================

    @Override
    public ChatResponseDto chat(ChatRequestDto request) {
        validateRequest(request); // 1. 参数校验

        // 2. 提取并填充默认值 — kbId/topK/minScore 为 null 时用默认值
        Long knowledgeBaseId = request.getKnowledgeBaseId() != null
                ? request.getKnowledgeBaseId()
                : DEFAULT_KNOWLEDGE_BASE_ID;
        int topK = request.getTopK() != null ? request.getTopK() : ragProperties.getRetrieval().getTopK();
        double minScore = request.getMinScore() != null ? request.getMinScore()
                : ragProperties.getRetrieval().getMinScore();

        // 2.1 解析 conversationId（前端传了则校验，没传则新建）
        String conversationId = conversationMemoryService.resolveConversationId(
                request.getConversationId(), knowledgeBaseId, request.getModel());

        // 2.2 加载滑动窗口 — 最近 8 轮历史消息（倒序）
        List<ConversationMessage> recentMessages = conversationMemoryService.loadRecentMessages(conversationId,
                MAX_RECENT_MESSAGES);

        // 2.3 加载滚动摘要（null = 未生成）
        String summary = conversationMemoryService.getSummary(conversationId);

        // 2.4 加载结构化状态（第一轮后就有值）
        String sessionState = conversationMemoryService.getSessionState(conversationId);

        // 2.5 Query Rewrite：指代消解后的问题用于向量检索
        String questionForSearch = conversationMemoryService.rewriteQuestion(
                summary, sessionState, recentMessages, request.getQuestion());
        log.info("改写后的问题为:{}", questionForSearch);
        // 3. embeddingModel.embed(question) — 问题向量化（用改写后的问题）
        float[] questionVector = embeddingModel.embed(questionForSearch)
                .content().vector();

        // 4. toPgVectorString(vector) — float[] → "[0.12, -0.34, ...]"
        String questionEmbedding = toPgVectorString(questionVector);

        // 5. documentChunkMapper.searchSimilarChunks() — pgvector 检索 topK
        List<RetrievedChunkDto> retrievedChunks = documentChunkMapper
                .searchSimilarChunks(knowledgeBaseId, questionEmbedding, topK);

        // 6. filter(score >= minScore) — 过滤低相似度 chunk
        List<RetrievedChunkDto> validChunks = retrievedChunks.stream()
                .filter(chunk -> chunk.getScore() != null && chunk.getScore() >= minScore)
                .toList();

        // 7. isEmpty? → return "无法确定" — 无有效资料时拒绝编造
        if (validChunks.isEmpty()) {
            return new ChatResponseDto("根据当前知识库资料无法确定。", List.of(), conversationId);
        }

        // 8. buildContext(chunks) — 拼接【资料1】【资料2】...
        String context = buildContext(validChunks);

        // 9. 组装 ChatRequest
        // 顺序：SystemMessage(规则) → 历史消息(8轮) → UserMessage(摘要+资料+问题+要求)
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPromptTemplate));

        // 历史消息：数据库倒序 → 反转正序注入
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            ConversationMessage msg = recentMessages.get(i);
            if ("user".equals(msg.getRole())) {
                messages.add(UserMessage.from(msg.getContent()));
            } else if ("assistant".equals(msg.getRole())) {
                messages.add(AiMessage.from(msg.getContent()));
            }
        }

        // 构造当前轮 UserMessage：模板替换占位符
        String userMessage = userPromptTemplate
                .replace("{summary}", summary != null && !summary.isBlank()
                        ? "【会话摘要】\n" + summary + "\n\n"
                        : "")
                .replace("{sessionState}", sessionState != null && !sessionState.isBlank()
                        ? "【结构化状态】\n" + sessionState + "\n\n"
                        : "")
                .replace("{context}", context)
                .replace("{question}", request.getQuestion());
        log.info("构造的非历史记录用户消息是:{}", userMessage);
        messages.add(UserMessage.from(userMessage));

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .build();

        // 10. chatModelFactory.getModel().chat(prompt) — 调用 LLM 生成答案
        ChatResponse chatResponse = chatModelFactory.getModel(request.getModel()).chat(chatRequest);

        // 11. 组装 SourceReference 列表
        List<SourceReference> sources = validChunks.stream()
                .map(chunk -> new SourceReference(
                        chunk.getDocumentName(),
                        chunk.getChunkIndex(),
                        chunk.getScore(),
                        truncatePreview(chunk.getContent(), 160),
                        chunk.getContent(),
                        chunk.getPage()))
                .toList();

        String answer = chatResponse.aiMessage().text();

        // 12. 持久化本轮问答
        conversationMemoryService.saveTurn(conversationId, request.getQuestion(), answer);

        // 12.1 每轮更新结构化状态
        conversationMemoryService.updateSessionState(conversationId, request.getQuestion(), answer);

        // 13. 消息总数超过窗口 → 滚动摘要
        if (conversationMemoryService.countMessages(conversationId) > MAX_RECENT_MESSAGES) {
            // recentMessages 是倒序，末尾 2 条 = 被挤出的最早一轮
            ConversationMessage q = recentMessages.get(recentMessages.size() - 2);
            ConversationMessage a = recentMessages.get(recentMessages.size() - 1);
            conversationMemoryService.updateSummary(conversationId, q.getContent(), a.getContent());
        }

        return new ChatResponseDto(answer, sources, conversationId);
    }

    // ==================== 私有方法（待实现） ====================

    /**
     * 校验请求参数。
     * <ul>
     * <li>request 不能为 null</li>
     * <li>question 不能为空</li>
     * <li>topK 必须 > 0</li>
     * <li>minScore 必须在 [0, 1] 区间</li>
     * </ul>
     */
    private void validateRequest(ChatRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        if (request.getTopK() != null && request.getTopK() <= 0) {
            throw new IllegalArgumentException("topK 必须大于 0");
        }
        if (request.getMinScore() != null
                && (request.getMinScore() < 0 || request.getMinScore() > 1)) {
            throw new IllegalArgumentException("minScore 必须在 0 到 1 之间");
        }
    }

    /**
     * 将 float[] 向量转为 pgvector 可识别的字符串格式。
     * <p>
     * 示例：float[]{0.12, -0.34} → "[0.12, -0.34]"
     */
    private String toPgVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 将检索到的 chunk 拼接成上下文文本。
     * <p>
     * 格式：
     * 
     * <pre>
     * 【资料1】
     * 文档名称：xxx.pdf
     * 页码：3
     * 相似度分数：0.86
     * 内容：
     * ...chunk 原文...
     * </pre>
     */
    private String buildContext(List<RetrievedChunkDto> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunkDto chunk = chunks.get(i);
            sb.append("【资料").append(i + 1).append("】\n");
            sb.append("文档名称：").append(chunk.getDocumentName()).append("\n");
            if (chunk.getPage() != null) {
                sb.append("页码：").append(chunk.getPage()).append("\n");
            }
            sb.append("相似度分数：").append(chunk.getScore()).append("\n");
            sb.append("内容：\n");
            sb.append(chunk.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 截取预览文本，用于 SourceReference.preview。
     */
    private String truncatePreview(String content, int maxLen) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxLen ? content : content.substring(0, maxLen) + "...";
    }
}
