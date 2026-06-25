package com.example.ragdemo.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Embedding 模型配置
 */
@Configuration
public class EmbeddingConfig {

    /**
     * 旧版兼容：OpenAiClient 仍使用 WebClient 直调
     */
    @Bean
    @Qualifier("embeddingWebClient")
    public WebClient embeddingWebClient(RagProperties ragProperties) {
        return WebClient.builder()
                .baseUrl(ragProperties.getEmbedding().getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + ragProperties.getEmbedding().getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 新版：LangChain4j EmbeddingModel，供 IngestionServiceImpl 使用
     */
    @Bean
    public EmbeddingModel embeddingModel(RagProperties ragProperties) {
        RagProperties.Embedding emb = ragProperties.getEmbedding();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(emb.getBaseUrl())
                .apiKey(emb.getApiKey())
                .modelName(emb.getModel())
                .timeout(Duration.ofMinutes(2))
                .build();
    }
}
