package com.example.ragdemo.service.serviceImpl;

import com.example.ragdemo.config.ChatModelFactory;
import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.memory.ConversationMemoryService;
import com.example.ragdemo.model.dto.ChatRequestDto;
import com.example.ragdemo.model.dto.ChatResponseDto;
import com.example.ragdemo.model.dto.SourceReference;
import com.example.ragdemo.retrieval.DocumentRetrievalService;
import com.example.ragdemo.retrieval.model.RetrievalRequest;
import com.example.ragdemo.retrieval.model.RetrievedChunk;
import com.example.ragdemo.service.ChatService;
import com.example.ragdemo.model.entity.ConversationMessage;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

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
 * <li>{@link com.example.ragdemo.retrieval.DocumentRetrievalService} — 问题向量化 + pgvector 检索
 * （Day 2 Step 1 起从本类内联代码提取为可复用服务，后续 Agent Tools 复用同一实现）</li>
 * <li>{@link dev.langchain4j.model.chat.ChatLanguageModel} — 通过
 * ChatModelFactory 动态选择</li>
 * </ul>
 */
@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    // ==================== 默认值常量 ====================
    private static final Long DEFAULT_KNOWLEDGE_BASE_ID = 1L;
    private static final int MAX_RECENT_MESSAGES = 16; // 8 轮对话 × 2

    /**
     * 与 application.yml 的 {@code rag.query-rewrite.enabled} 对应。
     * <p>Phase 0 审计发现该开关此前**未被任何代码读取**（死配置，Rewrite 无条件执行）⇒ 本处修复。
     * 关闭时检索直接使用原始问题（回答链路仍使用原始问题，不受影响）。
     */
    @org.springframework.beans.factory.annotation.Value("${rag.query-rewrite.enabled:true}")
    private boolean queryRewriteEnabled;

    // ==================== 依赖注入 ====================
    private final ChatModelFactory chatModelFactory;
    private final ConversationMemoryService conversationMemoryService;
    private final RagProperties ragProperties;
    /** Day 2 Step 1：检索能力已解耦为可复用服务（后续 Agent Tools 复用同一实现） */
    private final DocumentRetrievalService documentRetrievalService;

    /**
     * Phase 2A：消息保存与 L3 Outbox 入队的**原子提交**入口（跨 Bean 调用 ⇒ @Transactional 通过代理生效）。
     * <p>用字段注入以保持既有构造器签名不变（旧调用方与单测零改动）；仅在测试中通过反射注入 mock。
     */
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.ragdemo.memory.l3.L3TurnCommitService l3TurnCommitService;

    /**
     * Phase 3：L3 长期记忆（跨会话）。字段注入保持既有构造器签名不变；未启用时返回空快照，
     * 行为与 Phase 1/2 完全一致。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.ragdemo.memory.l3.MemoryL3ContextService l3ContextService;

    /** System prompt 模板 */
    private String systemPromptTemplate;

    /** User prompt 模板（含 {summary} {context} {question} 占位符） */
    private String userPromptTemplate;

    @Value("classpath:prompt/system-prompt.txt")
    private Resource systemPromptResource;

    @Value("classpath:prompt/user-prompt.txt")
    private Resource userPromptResource;

    public ChatServiceImpl(ChatModelFactory chatModelFactory,
            ConversationMemoryService conversationMemoryService,
            RagProperties ragProperties,
            DocumentRetrievalService documentRetrievalService) {
        this.chatModelFactory = chatModelFactory;
        this.conversationMemoryService = conversationMemoryService;
        this.ragProperties = ragProperties;
        this.documentRetrievalService = documentRetrievalService;
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
        String questionForSearch = queryRewriteEnabled
                ? conversationMemoryService.rewriteQuestion(summary, sessionState, recentMessages,
                        request.getQuestion())
                : request.getQuestion();
        log.info("改写后的问题为:{}", questionForSearch);
        // 3~6. 检索 —— Day 2 Step 1 起复用 DocumentRetrievalService
        //      等价性：向量化仍是同一个 EmbeddingModel（经共享网关）；pgvector SQL、minScore 过滤、
        //      距离升序排序均未改变。query rewrite 仍在【本方法内】完成，Retriever 只接收已确定的查询。
        List<RetrievedChunk> validChunks = documentRetrievalService.retrieve(
                new RetrievalRequest(questionForSearch, knowledgeBaseId, topK, minScore));

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
        // Phase 3：L3 在 summary / sessionState 之后注入（历史背景，不替代论文来源引用）
        String stateBlock = sessionState != null && !sessionState.isBlank()
                ? "【结构化状态】\n" + sessionState + "\n\n"
                : "";
        String l3Block = loadL3Block(request.getQuestion());
        if (!l3Block.isBlank()) {
            stateBlock = stateBlock + l3Block + "\n";
        }
        String userMessage = userPromptTemplate
                .replace("{summary}", summary != null && !summary.isBlank()
                        ? "【会话摘要】\n" + summary + "\n\n"
                        : "")
                .replace("{sessionState}", stateBlock)
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
                        chunk.documentName(),
                        chunk.chunkIndex(),
                        chunk.score(),
                        truncatePreview(chunk.content(), 160),
                        chunk.content(),
                        chunk.page()))
                .toList();

        String answer = chatResponse.aiMessage().text();

        ChatResponseDto response = new ChatResponseDto(answer, sources, conversationId);

        // 12. 核心轮次持久化：失败仍返回答案，但在响应中**明确标记**，绝不宣称已落库
        try {
            // Phase 2A：原子提交入口（内部 saveTurn + L3 Outbox 同事务）—— 不要再单独调用 saveTurn，避免双写
            l3TurnCommitService.commitTurnAndEnqueue(conversationId, request.getQuestion(), answer, null);
        } catch (RuntimeException e) {
            log.error("[memory] 会话轮次持久化失败（回答已返回，记忆未落库）: conversationId={}, type={}",
                    conversationId, e.getClass().getSimpleName());
            response.setMemoryDegraded(true);
            response.setMemoryWarning("CONVERSATION_PERSIST_FAILED");
            // 核心轮次没落库 ⇒ 不再做派生更新，避免产生"半套记忆"
            return response;
        }

        // 12.1/13. 派生记忆（结构化状态 + 超窗滚动摘要）：失败只降级，不影响已持久化的核心轮次
        try {
            conversationMemoryService.updateSessionState(conversationId, request.getQuestion(), answer);

            if (conversationMemoryService.countMessages(conversationId) > MAX_RECENT_MESSAGES) {
                // recentMessages 是倒序末尾 2 条 = 窗口内最早的一轮
                ConversationMessage q = recentMessages.get(recentMessages.size() - 2);
                ConversationMessage a = recentMessages.get(recentMessages.size() - 1);
                conversationMemoryService.updateSummary(conversationId, q.getContent(), a.getContent());
            }
        } catch (RuntimeException e) {
            log.error("[memory] 派生记忆更新失败（核心轮次已持久化）: conversationId={}, type={}", conversationId,
                    e.getClass().getSimpleName());
            response.setMemoryDegraded(true);
            response.setMemoryWarning("MEMORY_DERIVED_UPDATE_FAILED");
        }
        return response;
    }

    // ==================== 私有方法（待实现） ====================

    /**
     * Phase 3：读取 L3 块。任何失败都降级为""（回答继续使用 L1+L2），
     * 且**不**改变既有响应的必填字段（向后兼容）。
     */
    private String loadL3Block(String question) {
        if (l3ContextService == null) {
            return "";
        }
        try {
            var snapshot = l3ContextService.load(question, false, null);
            if (snapshot.degraded()) {
                log.warn("[memory] L3 检索失败，已降级为 L1+L2: reason={}", snapshot.reason());
            }
            return snapshot.hasContent() ? snapshot.context() : "";
        } catch (RuntimeException e) {
            log.warn("[memory] L3 检索异常，已降级为 L1+L2: {}", e.toString());
            return "";
        }
    }

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
    private String buildContext(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            sb.append("【资料").append(i + 1).append("】\n");
            sb.append("文档名称：").append(chunk.documentName()).append("\n");
            if (chunk.page() != null) {
                sb.append("页码：").append(chunk.page()).append("\n");
            }
            sb.append("相似度分数：").append(chunk.score()).append("\n");
            sb.append("内容：\n");
            sb.append(chunk.content()).append("\n\n");
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
