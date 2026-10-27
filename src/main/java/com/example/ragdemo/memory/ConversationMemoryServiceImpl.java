package com.example.ragdemo.memory;

import com.example.ragdemo.config.ChatModelFactory;
import com.example.ragdemo.mapper.ChatConversationMapper;
import com.example.ragdemo.mapper.ConversationMessageMapper;
import com.example.ragdemo.model.entity.ChatConversation;
import com.example.ragdemo.model.entity.ConversationMessage;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ConversationMemoryServiceImpl implements ConversationMemoryService {

    private final ChatConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final ChatModelFactory chatModelFactory;

    /** 滚动摘要提示词模板 */
    @Value("classpath:prompt/summary-prompt.txt")
    private Resource summaryPromptResource;
    private String summaryPromptTemplate;

    @Value("classpath:prompt/state-prompt.txt")
    private Resource statePromptResource;
    private String statePromptTemplate;

    @Value("classpath:prompt/query-rewrite-prompt.txt")
    private Resource rewritePromptResource;
    private String rewritePromptTemplate;

    public ConversationMemoryServiceImpl(ChatConversationMapper conversationMapper,
            ConversationMessageMapper messageMapper,
            ChatModelFactory chatModelFactory) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.chatModelFactory = chatModelFactory;
    }

    @PostConstruct
    private void loadPromptTemplates() throws IOException {
        this.summaryPromptTemplate = summaryPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.statePromptTemplate = statePromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.rewritePromptTemplate = rewritePromptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public String resolveConversationId(String conversationId, Long knowledgeBaseId, String modelKey) {
        // 前端传了 conversationId → 查库校验是否存在
        if (conversationId != null && !conversationId.isBlank()) {
            ChatConversation existing = conversationMapper.selectById(conversationId);
            if (existing != null) {
                log.info("复用会话: conversationId={}", conversationId);
                return conversationId;
            }
            // 不存在 → 降级为新会话（不抛错，用户体验更好）
            log.warn("会话不存在，创建新会话: conversationId={}", conversationId);
        }

        // 新会话
        String newId = UUID.randomUUID().toString();

        ChatConversation conversation = new ChatConversation();
        conversation.setId(newId);
        conversation.setKnowledgeBaseId(knowledgeBaseId);
        conversation.setModelKey(modelKey);
        conversationMapper.insert(conversation);

        log.info("新建会话: conversationId={}, kbId={}, model={}", newId, knowledgeBaseId, modelKey);
        return newId;
    }

    @Override
    public List<ConversationMessage> loadRecentMessages(String conversationId, int limit) {
        return messageMapper.selectRecentMessages(conversationId, limit);
    }

    @Override
    public void saveTurn(String conversationId, String userQuestion, String assistantAnswer) {
        messageMapper.insertMessage(conversationId, "user", userQuestion);
        messageMapper.insertMessage(conversationId, "assistant", assistantAnswer);
        conversationMapper.touch(conversationId);
        log.info("保存问答: conversationId={}", conversationId);
    }

    @Override
    public List<ChatConversation> listConversations(int limit) {
        return conversationMapper.selectRecentConversations(limit);
    }

    /** 结构化状态的固定七字段（与 prompt/state-prompt.txt 一致）。 */
    private static final java.util.List<String> SESSION_STATE_KEYS = java.util.List.of(
            "topic", "method", "dataset", "metric", "finding", "task", "keyTerms");
    private static final String DEFAULT_SESSION_STATE =
            "{\"topic\":null,\"method\":null,\"dataset\":null,\"metric\":null,\"finding\":null,\"task\":null,\"keyTerms\":[]}";
    /** prompt 的目标是 ≤300 字；代码侧兜底上限。 */
    private static final int SUMMARY_MAX_CHARS = 300;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public void updateSummary(String conversationId, String oldestQuestion, String oldestAnswer) {
        try {
            ChatConversation conv = conversationMapper.selectById(conversationId);
            if (conv == null) {
                log.warn("[memory] 摘要更新跳过：会话不存在 conversationId={}", conversationId);
                return;
            }
            String oldSummary = conv.getSummary() != null ? conv.getSummary() : "无";

            String prompt = summaryPromptTemplate
                    .replace("{oldSummary}", oldSummary)
                    .replace("{question}", oldestQuestion)
                    .replace("{answer}", oldestAnswer);

            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(new UserMessage(prompt))
                    .build();
            String newSummary = chatModelFactory.getModel(null).chat(chatRequest).aiMessage().text();
            if (newSummary == null || newSummary.isBlank()) {
                log.warn("[memory] 模型返回空摘要，保留旧摘要: conversationId={}", conversationId);
                return;
            }
            if (newSummary.length() > SUMMARY_MAX_CHARS) {
                // prompt 目标是 ≤300 字；代码侧兜底防膨胀（不影响已有摘要语义）
                newSummary = newSummary.substring(0, SUMMARY_MAX_CHARS);
            }
            conversationMapper.updateSummary(conversationId, newSummary);

            log.info("[memory] 摘要已更新: conversationId={}, length={}", conversationId, newSummary.length());
        } catch (RuntimeException e) {
            // 记忆降级：摘要失败绝不影响已经生成的回答
            log.warn("[memory] 摘要更新失败（已降级，不影响回答）: conversationId={}, type={}", conversationId,
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public String getSummary(String conversationId) {
        ChatConversation conv = conversationMapper.selectById(conversationId);
        return conv != null ? conv.getSummary() : null;
    }

    @Override
    public long countMessages(String conversationId) {
        return messageMapper.countByConversationId(conversationId);
    }

    @Override
    public void updateSessionState(String conversationId, String question, String answer) {
        try {
            ChatConversation conv = conversationMapper.selectById(conversationId);
            if (conv == null) {
                log.warn("[memory] 状态更新跳过：会话不存在 conversationId={}", conversationId);
                return;
            }
            String oldState = conv.getSessionState() != null ? conv.getSessionState() : DEFAULT_SESSION_STATE;

            String prompt = statePromptTemplate
                    .replace("{oldState}", oldState)
                    .replace("{question}", question)
                    .replace("{answer}", answer);
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(new UserMessage(prompt))
                    .build();
            String newState = chatModelFactory.getModel(null).chat(chatRequest).aiMessage().text();
            String validated = validateSessionState(newState, oldState);
            conversationMapper.updateSessionState(conversationId, validated);

            log.info("[memory] 状态已更新: conversationId={}, schemaKeys={}", conversationId, SESSION_STATE_KEYS.size());
        } catch (RuntimeException e) {
            // 记忆降级：状态更新失败不得影响已生成回答（chat_messages 已提交）
            log.warn("[memory] 状态更新失败（已降级，不影响回答）: conversationId={}, type={}", conversationId,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 结构化会话状态校验（对应 prompt/state-prompt.txt 的固定七字段契约）：
     * 必须是 JSON 对象、只保留 topic/method/dataset/metric/finding/task/keyTerms、keyTerms 必须是数组。
     * <p>任何不合法输入 ⇒ <b>保留旧状态</b>（不覆盖、不污染），并记录 warn —— 降级而非抛错。
     */
    static String validateSessionState(String candidate, String previousState) {
        if (candidate == null || candidate.isBlank()) {
            log.warn("[memory] session_state 为空，保留旧状态");
            return previousState;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(candidate);
            if (!node.isObject()) {
                log.warn("[memory] session_state 不是 JSON 对象，保留旧状态");
                return previousState;
            }
            com.fasterxml.jackson.databind.node.ObjectNode out = JSON.createObjectNode();
            for (String key : SESSION_STATE_KEYS) {
                com.fasterxml.jackson.databind.JsonNode value = node.get(key);
                if (value == null || value.isNull()) {
                    out.putNull(key);
                } else {
                    out.set(key, value);
                }
            }
            if (!out.get("keyTerms").isArray()) {
                out.putArray("keyTerms");
            }
            String normalized = JSON.writeValueAsString(out);
            if (!normalized.equals(candidate)) {
                log.info("[memory] session_state 已按固定 schema 归一（丢弃多余字段/修正类型）");
            }
            return normalized;
        } catch (Exception e) {
            log.warn("[memory] session_state 非法 JSON，保留旧状态: type={}", e.getClass().getSimpleName());
            return previousState;
        }
    }

    @Override
    public String getSessionState(String conversationId) {
        ChatConversation conv = conversationMapper.selectById(conversationId);
        return conv != null ? conv.getSessionState() : null;
    }

    @Override
    public String rewriteQuestion(String summary, String sessionState,
                                  List<ConversationMessage> recentMessages, String question) {
        // 格式化最近对话：倒序 → 正序
        StringBuilder msgText = new StringBuilder();
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            ConversationMessage msg = recentMessages.get(i);
            msgText.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }

        String prompt = rewritePromptTemplate
                .replace("{summary}", summary != null ? summary : "无")
                .replace("{sessionState}", sessionState != null ? sessionState : "无")
                .replace("{recentMessages}", msgText.toString())
                .replace("{question}", question);

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(new UserMessage(prompt))
                .build();
        String rewritten = chatModelFactory.getModel(null).chat(chatRequest).aiMessage().text();
        log.info("Query Rewrite: 原始={} → 改写={}", question, rewritten);
        return rewritten;
    }
}
