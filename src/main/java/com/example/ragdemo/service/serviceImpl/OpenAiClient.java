package com.example.ragdemo.service.serviceImpl;

import com.example.ragdemo.config.RagProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class OpenAiClient {

    private final WebClient chatWebClient;
    private final WebClient embeddingWebClient;
    private final RagProperties ragProperties;

    public OpenAiClient(@Qualifier("chatWebClient") WebClient chatWebClient,
                        @Qualifier("embeddingWebClient") WebClient embeddingWebClient,
                        RagProperties ragProperties) {
        this.chatWebClient = chatWebClient;
        this.embeddingWebClient = embeddingWebClient;
        this.ragProperties = ragProperties;
    }

    public float[] embed(String text) {
        ensureEmbeddingConfig();
        Map<String, Object> request = Map.of(
                "model", ragProperties.getEmbedding().getModel(),
                "input", text
        );

        Map response = embeddingWebClient.post()
                .uri("/embeddings")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null || !(response.get("data") instanceof List<?> data) || data.isEmpty()) {
            throw new IllegalStateException("Embedding API 返回为空，请检查 embedding base-url、api-key 和模型配置");
        }

        Object firstObject = data.get(0);
        if (!(firstObject instanceof Map<?, ?> first) || !(first.get("embedding") instanceof List<?> embedding)) {
            throw new IllegalStateException("Embedding API 返回格式不符合 OpenAI-compatible 规范");
        }

        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            Object value = embedding.get(i);
            if (!(value instanceof Number number)) {
                throw new IllegalStateException("Embedding 向量中存在非数字元素");
            }
            vector[i] = number.floatValue();
        }

        int expectedDimension = ragProperties.getEmbedding().getDimension();
        if (vector.length != expectedDimension) {
            throw new IllegalStateException("Embedding 维度不一致：模型返回 "
                    + vector.length + " 维，但配置和数据库表要求 " + expectedDimension + " 维");
        }
        return vector;
    }

    public String chat(String prompt) {
        ensureChatConfig();
        Map<String, Object> request = Map.of(
                "model", ragProperties.getChat().getModel(),
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", "你是一个严谨的 RAG 问答助手。只能根据给定上下文回答；如果上下文不足，请直接说明不知道。"),
                        Map.of("role", "user", "content", prompt)
                )
        );

        Map response = chatWebClient.post()
                .uri("/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null || !(response.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            throw new IllegalStateException("Chat API 返回为空，请检查 chat base-url、api-key 和模型配置");
        }

        Object firstObject = choices.get(0);
        if (!(firstObject instanceof Map<?, ?> first)
                || !(first.get("message") instanceof Map<?, ?> message)
                || !(message.get("content") instanceof String content)) {
            throw new IllegalStateException("Chat API 返回格式不符合 OpenAI-compatible 规范");
        }
        return content;
    }

    private void ensureChatConfig() {
        if (!StringUtils.hasText(ragProperties.getChat().getApiKey())) {
            throw new IllegalStateException("请先配置环境变量 DEEPSEEK_API_KEY，或在 application.yml 中设置 rag.chat.api-key");
        }
        if (!StringUtils.hasText(ragProperties.getChat().getBaseUrl())
                || !StringUtils.hasText(ragProperties.getChat().getModel())) {
            throw new IllegalStateException("请检查 rag.chat.base-url 和 rag.chat.model 配置");
        }
    }

    private void ensureEmbeddingConfig() {
        if (!StringUtils.hasText(ragProperties.getEmbedding().getBaseUrl())
                || !StringUtils.hasText(ragProperties.getEmbedding().getModel())) {
            throw new IllegalStateException("请检查 rag.embedding.base-url 和 rag.embedding.model 配置");
        }
        if (ragProperties.getEmbedding().getDimension() <= 0) {
            throw new IllegalStateException("请检查 rag.embedding.dimension 配置");
        }
    }
}
