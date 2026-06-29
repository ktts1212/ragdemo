package com.example.ragdemo.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * Chat LLM 配置：创建调用 DeepSeek 的 WebClient
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class ChatModelConfig {

    @Bean
    @Qualifier("chatWebClient")
    public WebClient chatWebClient(RagProperties ragProperties) {
        return WebClient.builder()
                .baseUrl(ragProperties.getChat().getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + ragProperties.getChat().getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Bean
    public ChatLanguageModel chatLanguageModel(RagProperties ragProperties){
        
        return OpenAiChatModel.builder()
        .baseUrl(ragProperties.getChat().getBaseUrl())
        .apiKey(ragProperties.getChat().getApiKey())
        .modelName(ragProperties.getChat().getModel())
        .temperature(ragProperties.getChat().getTemperature())
        .timeout(Duration.ofMinutes(2))
        .build();
    }
}
