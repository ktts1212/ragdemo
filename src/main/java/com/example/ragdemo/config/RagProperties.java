package com.example.ragdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private Chat chat = new Chat();
    private Embedding embedding = new Embedding();
    private Chunk chunk = new Chunk();
    private Retrieval retrieval = new Retrieval();
    private QueryRewrite queryRewrite = new QueryRewrite();

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
