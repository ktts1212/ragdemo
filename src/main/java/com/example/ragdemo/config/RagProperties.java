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
    private Chunker chunker = new Chunker();
    private Retrieval retrieval = new Retrieval();
    private QueryRewrite queryRewrite = new QueryRewrite();
    private String defaultModel;
    private Map<String, ModelConfig> models;

    /** 文档解析/入库路由配置（skill §9） */
    private Parser parser = new Parser();

    /** 工作流（图化入库）配置（step4 §7）：默认关闭，旧链路行为不变 */
    private Workflow workflow = new Workflow();

    @Data
    public static class Workflow {

        private Ingestion ingestion = new Ingestion();

        @Data
        public static class Ingestion {
            /** 是否启用 ACADEMIC 入库图（false ⇒ 走原链路） */
            private boolean enabled = false;
            /** 是否启用 PostgreSQL checkpoint 持久化（本步骤恒为 true；仅用于对照实验） */
            private boolean checkpointEnabled = true;
            /** Step 5：任务租约 TTL（秒）。须大于单节点最长耗时（Marker 解析），靠续租覆盖 */
            private long leaseTtlSeconds = 120;
            /** Step 5：瞬时错误进入 retry_wait 的退避秒数 */
            private long retryBackoffSeconds = 10;
            /** Step 5：是否启用自动恢复调度器（<b>默认关闭</b>；test profile 打开并缩短间隔） */
            private boolean recoveryEnabled = false;
            /** Step 5：恢复扫描间隔（毫秒） */
            private long recoveryIntervalMs = 30_000L;
            /** Step 5：每轮扫描最多取多少候选任务 */
            private int recoveryBatchSize = 10;
            /** Step 5：恢复并发上限（避免同时启动过多图执行） */
            private int recoveryMaxConcurrency = 2;
        }
    }

    @Data
    public static class Parser {
        /** 解析后端：marker（主）/ grobid（legacy） */
        private String backend = "marker";

        /**
         * 入库解析模式默认值（三级决策的第 2 级）：
         * AUTO（默认）/ ACADEMIC / GENERIC。
         * 上传参数优先级更高；AUTO 时按扩展名/MIME 自动判断。
         */
        private com.example.ragdemo.rag.ingest.ParseMode parseMode =
                com.example.ragdemo.rag.ingest.ParseMode.AUTO;
    }

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
        /** 批量 embedding 的批大小（skill §13，默认 32） */
        private int batchSize = 32;
        /** 可重试错误的最大重试次数（指数退避，默认 2） */
        private int maxRetries = 2;
    }

    @Data
    public static class Chunk {
        private int maxChars;
        private int overlapChars;
    }

    /**
     * SectionAwareChunker 参数（skill §6）。
     * <p>
     * 单位统一为 token（由 {@code ChunkTokenEstimator} 估算），与上方 legacy 的字符口径解耦。
     * {@code maxTokens} 为单块硬顶，{@code targetTokens} 为累积闸门，
     * {@code minTokens} 仅用于 undersized 判定（v1 不据此跨 Section 合并）。
     */
    @Data
    public static class Chunker {
        private int targetTokens = 700;
        private int maxTokens = 1000;
        private int minTokens = 180;
        private int overlapTokens = 100;
        /** v1 默认关闭；开启后仅对同 Section 内相邻 TEXT chunk 生效 */
        private boolean overlapEnabled = false;
        /** embeddingText 是否包含 Document 标题行 */
        private boolean headerIncludeTitle = true;
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
