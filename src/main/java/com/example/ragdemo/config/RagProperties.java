package com.example.ragdemo.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private Chat chat = new Chat();
    private Embedding embedding = new Embedding();
    private Chunk chunk = new Chunk();
    private Retrieval retrieval = new Retrieval();
    private QueryRewrite queryRewrite = new QueryRewrite();
    private String defaultModel;
    private Map<String, ModelConfig> models;

    @Data
    public static class ModelConfig {
        private String provider; // "openai-compatible" / "ollama"
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private Double temperature;
        private Integer maxOutputTokens;
    }

    @Data
    public static class Chat {
        private String baseUrl;
        private String apiKey;
        private String model;
        private Double temperature;
    }

    @Data
    public static class Embedding {
        private String baseUrl;
        private String apiKey;
        private String model;
        private int dimension;
    }

    @Data
    public static class Chunk {
        private int maxChars;
        private int overlapChars;
    }

    @Data
    public static class Retrieval {
        private int topK;
        private double minScore;
    }

    @Data
    public static class QueryRewrite {
        private Boolean enabled;
    }

}
