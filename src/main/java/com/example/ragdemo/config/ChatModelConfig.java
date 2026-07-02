package com.example.ragdemo.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Chat LLM 配置：创建调用 DeepSeek 的 WebClient
 */
@Configuration
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
}
