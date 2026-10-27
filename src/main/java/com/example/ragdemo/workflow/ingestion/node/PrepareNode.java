package com.example.ragdemo.workflow.ingestion.node;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.document.chunk.AcademicChunk;
import com.example.ragdemo.rag.ingest.IngestionStatusUpdater;
import com.example.ragdemo.workflow.ingestion.IngestionErrorCodes;
import com.example.ragdemo.workflow.ingestion.IngestionState;
import com.example.ragdemo.workflow.ingestion.artifact.IngestionArtifactService;

import lombok.extern.slf4j.Slf4j;

/**
 * 准备节点：校验身份、固化 embedding 指纹与批次大小、推进文档状态。
 * <p>
 * 只读 State，不产生外部副作用（除状态更新），可安全重复执行。
 */
@Slf4j
@Component
public class PrepareNode {

    private final IngestionStatusUpdater statusUpdater;
    private final IngestionArtifactService artifacts;
    private final RagProperties ragProperties;

    public PrepareNode(IngestionStatusUpdater statusUpdater, IngestionArtifactService artifacts,
            RagProperties ragProperties) {
        this.statusUpdater = statusUpdater;
        this.artifacts = artifacts;
        this.ragProperties = ragProperties;
    }

    public Map<String, Object> apply(IngestionState state) {
        long dbDocumentId = state.dbDocumentId();
        if (state.checksum() == null || state.checksum().isBlank()) {
            throw new IllegalStateException(IngestionErrorCodes.MISSING_CHECKSUM
                    + "documentId=" + dbDocumentId);
        }
        if (state.sourceKey().isBlank()) {
            throw new IllegalStateException(IngestionErrorCodes.DOWNLOAD_FAILED
                    + "storagePath 为空, documentId=" + dbDocumentId);
        }

        int version = state.documentVersion();
        int batchSize = Math.max(1, ragProperties.getEmbedding().getBatchSize());
        String modelId = ragProperties.getEmbedding().getModel();
        int dimension = ragProperties.getEmbedding().getDimension();
        String fingerprint = artifacts.embeddingFingerprint(modelId, dimension, batchSize);

        statusUpdater.updateDocumentStatus(dbDocumentId, "parsing", 20, null);
        log.info("入库图 prepare: taskId={}, documentId={}, version={}, model={}, dim={}, batchSize={}, fp={}",
                state.taskId(), dbDocumentId, version, modelId, dimension, batchSize, fingerprint);

        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put(IngestionState.WORKFLOW_VERSION, IngestionArtifactService.WORKFLOW_VERSION);
        delta.put(IngestionState.EMBEDDING_MODEL_ID, modelId);
        // 现有配置只有 model 名，没有独立版本字段 ⇒ 用模型名作为版本标识（已记录为限制）
        delta.put(IngestionState.EMBEDDING_MODEL_VERSION, modelId);
        delta.put(IngestionState.EMBEDDING_DIMENSION, dimension);
        delta.put(IngestionState.EMBEDDING_BATCH_SIZE, batchSize);
        delta.put(IngestionState.EMBEDDING_FINGERPRINT, fingerprint);
        delta.put(IngestionState.EMBEDDING_ARTIFACT_PREFIX,
                artifacts.embeddingArtifactPrefix(dbDocumentId, version, fingerprint));
        delta.put(IngestionState.NEXT_BATCH_INDEX, 0);
        delta.put(IngestionState.PERSISTED, false);
        delta.put(IngestionState.STATUS, "RUNNING");
        return delta;
    }
}
