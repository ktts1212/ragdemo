package com.example.ragdemo.workflow.ingestion;

import java.util.Map;

import org.bsc.langgraph4j.state.AgentState;

/**
 * Step 4 真实 ACADEMIC 入库图的状态契约（step4 §5）。
 * <p>
 * <b>只放"身份 + 引用 + 游标 + 元数据"</b>，<b>不放</b> PDF byte[]、Marker Raw JSON、
 * {@code AcademicDocument}、{@code AcademicChunk} 列表或任何向量 ——
 * 大对象一律留在 MinIO / 数据库，图状态只持有可稳定序列化的轻量引用（step4 §0.4 / §5）。
 * <p>
 * 所有可被覆盖的 Field 都用 {@code Channels.base}（last-write），节点返回<b>绝对值</b>。
 */
public class IngestionState extends AgentState {

    // ==================== identity ====================
    public static final String TASK_ID = "taskId";
    /** 数据库 documents.id */
    public static final String DB_DOCUMENT_ID = "dbDocumentId";
    public static final String KNOWLEDGE_BASE_ID = "knowledgeBaseId";
    public static final String DOCUMENT_VERSION = "documentVersion";
    public static final String PARSE_MODE = "parseMode";
    /** 领域 documentId = 文件 SHA-256（chunkKey 的稳定前缀） */
    public static final String CHECKSUM = "checksum";
    public static final String SOURCE_KEY = "sourceKey";
    public static final String FILE_NAME = "fileName";
    /** 编排/pipeline 版本，人工维护；变更后不得复用旧 canonical（见 IngestionArtifactService） */
    public static final String WORKFLOW_VERSION = "workflowVersion";

    // ==================== artifact refs ====================
    public static final String MARKER_RAW_KEY = "markerRawKey";
    public static final String CANONICAL_KEY = "canonicalKey";
    public static final String CHUNKS_KEY = "chunksKey";
    public static final String CHUNK_COUNT = "chunkCount";
    public static final String CHUNKS_DIGEST = "chunksDigest";
    public static final String CHUNKER_VERSION = "chunkerVersion";

    // ==================== embedding ====================
    public static final String EMBEDDING_MODEL_ID = "embeddingModelId";
    public static final String EMBEDDING_MODEL_VERSION = "embeddingModelVersion";
    public static final String EMBEDDING_DIMENSION = "embeddingDimension";
    public static final String EMBEDDING_BATCH_SIZE = "embeddingBatchSize";
    /** 模型/参数指纹：参与批次产物 key，模型或参数变化即换目录，避免误用旧向量 */
    public static final String EMBEDDING_FINGERPRINT = "embeddingFingerprint";
    public static final String TOTAL_BATCHES = "totalBatches";
    /** 循环游标（base ⇒ 绝对值） */
    public static final String NEXT_BATCH_INDEX = "nextBatchIndex";
    public static final String EMBEDDING_ARTIFACT_PREFIX = "embeddingArtifactPrefix";

    // ==================== Step 5：执行权（fencing）====================
    /** 本次执行的 fencing token（由 Coordinator 注入；0 = 无租约兼容模式） */
    public static final String FENCING_TOKEN = "fencingToken";
    /** 本次执行的 Worker / lease owner（由 Coordinator 注入） */
    public static final String WORKER_ID = "workerId";

    // ==================== completion ====================
    public static final String PERSISTED = "persisted";
    public static final String STATUS = "status";
    public static final String ERROR_CODE = "errorCode";
    public static final String ERROR_MESSAGE = "errorMessage";

    public IngestionState(Map<String, Object> initData) {
        super(initData);
    }

    public long taskId() {
        return this.<Number>value(TASK_ID, 0L).longValue();
    }

    public long dbDocumentId() {
        return this.<Number>value(DB_DOCUMENT_ID, 0L).longValue();
    }

    public long knowledgeBaseId() {
        return this.<Number>value(KNOWLEDGE_BASE_ID, 0L).longValue();
    }

    public int documentVersion() {
        return this.<Integer>value(DOCUMENT_VERSION, 1);
    }

    public String checksum() {
        return this.<String>value(CHECKSUM).orElse("");
    }

    public String fileName() {
        return this.<String>value(FILE_NAME).orElse("");
    }

    public String sourceKey() {
        return this.<String>value(SOURCE_KEY).orElse("");
    }

    public int workflowVersion() {
        return this.<Integer>value(WORKFLOW_VERSION, 0);
    }

    public String canonicalKey() {
        return this.<String>value(CANONICAL_KEY).orElse("");
    }

    public int chunkCount() {
        return this.<Integer>value(CHUNK_COUNT, 0);
    }

    public String embeddingFingerprint() {
        return this.<String>value(EMBEDDING_FINGERPRINT).orElse("");
    }

    public int embeddingBatchSize() {
        return this.<Integer>value(EMBEDDING_BATCH_SIZE, 0);
    }

    public int embeddingDimension() {
        return this.<Integer>value(EMBEDDING_DIMENSION, 0);
    }

    public int totalBatches() {
        return this.<Integer>value(TOTAL_BATCHES, 0);
    }

    public int nextBatchIndex() {
        return this.<Integer>value(NEXT_BATCH_INDEX, 0);
    }

    public boolean persisted() {
        return this.<Boolean>value(PERSISTED, false);
    }

    /** Step 5：本次执行的 fencing token；0 表示未启用租约（兼容 Step 4 的无租约路径）。 */
    public long fencingToken() {
        return this.<Number>value(FENCING_TOKEN, 0L).longValue();
    }

    /** Step 5：本次执行的 Worker / lease owner。 */
    public String workerId() {
        return this.<String>value(WORKER_ID).orElse("");
    }

    public String status() {
        return this.<String>value(STATUS, "NEW");
    }
}
