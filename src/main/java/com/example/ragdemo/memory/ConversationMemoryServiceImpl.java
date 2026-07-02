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

    @Override
    public void updateSummary(String conversationId, String oldestQuestion, String oldestAnswer) {
        ChatConversation conv = conversationMapper.selectById(conversationId);
        String oldSummary = conv.getSummary() != null ? conv.getSummary() : "无";

        String prompt = summaryPromptTemplate
                .replace("{oldSummary}", oldSummary)
                .replace("{question}", oldestQuestion)
                .replace("{answer}", oldestAnswer);

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(new UserMessage(prompt))
                .build();
        String newSummary = chatModelFactory.getModel(null).chat(chatRequest).aiMessage().text();
        conversationMapper.updateSummary(conversationId, newSummary);

        log.info("更新摘要: conversationId={}, length={}", conversationId, newSummary.length());
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
        ChatConversation conv = conversationMapper.selectById(conversationId);
        String oldState = conv.getSessionState() != null ? conv.getSessionState()
                : "{\"topic\":null,\"method\":null,\"dataset\":null,\"metric\":null,\"finding\":null,\"task\":null,\"keyTerms\":[]}";

        String prompt = statePromptTemplate
                .replace("{oldState}", oldState)
                .replace("{question}", question)
                .replace("{answer}", answer);
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(new UserMessage(prompt))
                .build();
        String newState = chatModelFactory.getModel(null).chat(chatRequest).aiMessage().text();
        conversationMapper.updateSessionState(conversationId, newState);

        log.info("更新状态: conversationId={}, state={}", conversationId, newState);
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
