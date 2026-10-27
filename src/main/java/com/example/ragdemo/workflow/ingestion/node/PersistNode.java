package com.example.ragdemo.workflow.ingestion.node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.chunk.AcademicChunk;
import com.example.ragdemo.document.chunk.SectionAwareChunker;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.model.param.DocumentChunkInsertParam;
import com.example.ragdemo.persistence.AcademicChunkPersistenceMapper;
import com.example.ragdemo.rag.ingest.ChunkPersistenceService;
import com.example.ragdemo.workflow.ingestion.IngestionErrorCodes;
import com.example.ragdemo.workflow.ingestion.IngestionState;
import com.example.ragdemo.workflow.ingestion.artifact.EmbeddingBatchArtifact;
import com.example.ragdemo.workflow.ingestion.artifact.IngestionArtifactService;
import com.example.ragdemo.workflow.testkit.WorkflowFaultInjector;

import lombok.extern.slf4j.Slf4j;

/**
 * 持久化节点：收齐所有批次 → 按 chunk 顺序重组向量 → <b>仅调用一次</b>
 * {@link ChunkPersistenceService#replaceChunks}。
 * <p>
 * <b>为什么必须是一个节点、且只调一次</b>（step4 §0.6/§6.4）：{@code replaceChunks} 的唯一价值是
 * "删旧 + 插新 + chunk_count + indexed" 的<b>整体原子性</b>；拆成多个节点或每批调用都会产生
 * "部分批次已提交、文档已 indexed 但向量不全"的中间态。
 * <p>
 * 本节点通过 <b>Spring 注入的 Bean</b> 调用（不是 new），保证 {@code @Transactional} 代理生效。
 */
@Slf4j
@Component
public class PersistNode {

    private final SectionAwareChunker chunker;
    private final IngestionArtifactService artifacts;
    private final AcademicChunkPersistenceMapper persistenceMapper;
    private final ChunkPersistenceService chunkPersistenceService;
    private final WorkflowFaultInjector faultInjector;

    public PersistNode(SectionAwareChunker chunker, IngestionArtifactService artifacts,
            AcademicChunkPersistenceMapper persistenceMapper,
            ChunkPersistenceService chunkPersistenceService, WorkflowFaultInjector faultInjector) {
        this.chunker = chunker;
        this.artifacts = artifacts;
        this.persistenceMapper = persistenceMapper;
        this.chunkPersistenceService = chunkPersistenceService;
        this.faultInjector = faultInjector;
    }

    public Map<String, Object> apply(IngestionState state) {
        long dbDocumentId = state.dbDocumentId();
        long kbId = state.knowledgeBaseId();
        int version = state.documentVersion();
        int batchSize = Math.max(1, state.embeddingBatchSize());

        AcademicDocument canonical = artifacts.readCanonical(state.canonicalKey());
        List<AcademicChunk> chunks = chunker.chunk(canonical).chunks();
        if (chunks.size() != state.chunkCount()) {
            throw new IllegalStateException(IngestionErrorCodes.STATE_MISMATCH
                    + "重建 chunk 数与 State 不一致: rebuilt=" + chunks.size()
                    + " state=" + state.chunkCount());
        }

        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (int i = 0; i < state.totalBatches(); i++) {
            int from = i * batchSize;
            int to = Math.min(from + batchSize, chunks.size());
            String key = artifacts.embeddingBatchKey(dbDocumentId, version,
                    state.embeddingFingerprint(), i);
            EmbeddingBatchArtifact batch = artifacts.readJson(key, EmbeddingBatchArtifact.class);
            verifyBatch(batch, i, from, chunks.subList(from, to), state);
            vectors.addAll(batch.getVectors());
        }
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException(IngestionErrorCodes.STATE_MISMATCH
                    + "向量总数与 chunk 数不一致: vectors=" + vectors.size()
                    + " chunks=" + chunks.size());
        }

        List<DocumentChunkInsertParam> params = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            params.add(persistenceMapper.toInsertParam(chunks.get(i), dbDocumentId, kbId, vectors.get(i)));
        }

        // 故障注入点（生产 no-op）：A4 —— 事务提交之前
        faultInjector.at(WorkflowFaultInjector.Points.PERSIST_BEFORE_COMMIT,
                Map.of("taskId", state.taskId(), "documentId", dbDocumentId));

        // ★ 唯一一次写库：沿用原事务（delete → insert → chunk_count → indexed）
        long fencingToken = state.fencingToken();
        if (fencingToken > 0 && !state.workerId().isBlank()) {
            // Step 5：fencing 校验与业务写入在【同一事务】内完成（过期 Worker 的迟到提交被拒绝）
            chunkPersistenceService.replaceChunksGuarded(dbDocumentId, params,
                    state.taskId(), state.workerId(), fencingToken);
        } else {
            // 无租约兼容路径（Step 4 历史入口 / 离线重跑）
            chunkPersistenceService.replaceChunks(dbDocumentId, params);
        }

        // 故障注入点（生产 no-op）：A3 —— 事务已提交（documents=indexed）但节点未返回
        faultInjector.at(WorkflowFaultInjector.Points.PERSIST_AFTER_COMMIT,
                Map.of("taskId", state.taskId(), "documentId", dbDocumentId, "chunks", params.size()));

        log.info("入库图 persist 完成: documentId={}, chunks={}, fencingToken={}",
                dbDocumentId, params.size(), fencingToken);

        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put(IngestionState.PERSISTED, true);
        delta.put(IngestionState.STATUS, "PERSISTED");
        return delta;
    }

    private void verifyBatch(EmbeddingBatchArtifact batch, int index, int from,
            List<AcademicChunk> slice, IngestionState state) {
        if (batch == null) {
            throw new IllegalStateException(IngestionErrorCodes.STATE_MISMATCH
                    + "批次产物为空: batchIndex=" + index);
        }
        List<String> expectedIds = slice.stream().map(AcademicChunk::getChunkId).toList();
        List<String> expectedHashes = slice.stream().map(AcademicChunk::getContentHash).toList();
        if (batch.getBatchIndex() != index || batch.getFromChunkOrder() != from
                || batch.getDimension() != state.embeddingDimension()
                || batch.size() != slice.size()
                || !expectedIds.equals(batch.getChunkIds())
                || !expectedHashes.equals(batch.getContentHashes())) {
            throw new IllegalStateException(IngestionErrorCodes.STATE_MISMATCH
                    + "批次产物与重建 chunk 不匹配: batchIndex=" + index
                    + " artifactSize=" + batch.size() + " expectedSize=" + slice.size()
                    + " dimension=" + batch.getDimension() + " expectedDim=" + state.embeddingDimension());
        }
    }
}
