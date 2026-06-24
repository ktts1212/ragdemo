package com.example.ragdemo.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties({RagProperties.class, MinioProperties.class})
public class AppConfig {

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
    @Qualifier("embeddingWebClient")
    public WebClient embeddingWebClient(RagProperties ragProperties) {
        return WebClient.builder()
                .baseUrl(ragProperties.getEmbedding().getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + ragProperties.getEmbedding().getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Bean
    public MinioClient minioClient(MinioProperties props) {
        return MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
    }
}
