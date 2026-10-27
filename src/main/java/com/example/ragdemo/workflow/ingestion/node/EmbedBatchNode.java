package com.example.ragdemo.workflow.ingestion.node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.chunk.AcademicChunk;
import com.example.ragdemo.document.chunk.SectionAwareChunker;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.embedding.EmbeddingGateway;
import com.example.ragdemo.rag.ingest.IngestionStatusUpdater;
import com.example.ragdemo.workflow.ingestion.IngestionErrorCodes;
import com.example.ragdemo.workflow.ingestion.IngestionState;
import com.example.ragdemo.workflow.ingestion.artifact.EmbeddingBatchArtifact;
import com.example.ragdemo.workflow.ingestion.artifact.IngestionArtifactService;
import com.example.ragdemo.workflow.testkit.WorkflowFaultInjector;

import lombok.extern.slf4j.Slf4j;

/**
 * 单批 Embedding 节点（step4 §4/§6.3）：<b>每次只处理 1 批</b>，结果外置到 MinIO。
 * <p>
 * <b>不会二次分批</b>：本节点取的切片长度 ≤ {@code rag.embedding.batch-size}，
 * 因此 {@link EmbeddingGateway#embedDocuments} 内部只会调用一次
 * {@code embedBatch}（该实现按 {@code batch-size} 内部切批，传入 ≤ 一批长度即恰好一次）。
 * <p>
 * <b>复用规则</b>：批次产物必须同时满足指纹（模型/维度/批次大小）、batchIndex、该批
 * chunkId 顺序与 contentHash 全部匹配才复用；否则<b>重算</b>（缺失/损坏/不匹配一律重算）。
 */
@Slf4j
@Component
public class EmbedBatchNode {

    private final SectionAwareChunker chunker;
    private final EmbeddingGateway embeddingGateway;
    private final IngestionArtifactService artifacts;
    private final IngestionStatusUpdater statusUpdater;
    private final WorkflowFaultInjector faultInjector;

    public EmbedBatchNode(SectionAwareChunker chunker, EmbeddingGateway embeddingGateway,
            IngestionArtifactService artifacts, IngestionStatusUpdater statusUpdater,
            WorkflowFaultInjector faultInjector) {
        this.chunker = chunker;
        this.embeddingGateway = embeddingGateway;
        this.artifacts = artifacts;
        this.statusUpdater = statusUpdater;
        this.faultInjector = faultInjector;
    }

    public Map<String, Object> apply(IngestionState state) {
        long dbDocumentId = state.dbDocumentId();
        int version = state.documentVersion();
        int index = state.nextBatchIndex();
        int batchSize = Math.max(1, state.embeddingBatchSize());

        AcademicDocument canonical = artifacts.readCanonical(state.canonicalKey());
        List<AcademicChunk> chunks = chunker.chunk(canonical).chunks();
        int from = index * batchSize;
        int to = Math.min(from + batchSize, chunks.size());
        if (from >= to) {
            throw new IllegalStateException(IngestionErrorCodes.STATE_MISMATCH
                    + "批次越界: batchIndex=" + index + " chunkCount=" + chunks.size());
        }
        List<AcademicChunk> slice = chunks.subList(from, to);
        String key = artifacts.embeddingBatchKey(dbDocumentId, version, state.embeddingFingerprint(), index);

        EmbeddingBatchArtifact batch = tryReuse(key, state, slice, index, from);
        if (batch == null) {
            List<String> texts = slice.stream().map(AcademicChunk::getEmbeddingText).toList();
            List<float[]> vectors;
            try {
                vectors = embeddingGateway.embedDocuments(texts);
            } catch (RuntimeException e) {
                throw new IllegalStateException(IngestionErrorCodes.EMBEDDING_FAILED
                        + "batchIndex=" + index + " documentId=" + dbDocumentId, e);
            }
            if (vectors == null || vectors.size() != slice.size()) {
                throw new IllegalStateException(IngestionErrorCodes.EMBEDDING_FAILED
                        + "返回数量不一致: expected=" + slice.size() + " actual="
                        + (vectors == null ? 0 : vectors.size()) + " batchIndex=" + index);
            }
            for (float[] vector : vectors) {
                int actual = vector == null ? 0 : vector.length;
                if (actual != state.embeddingDimension()) {
                    throw new IllegalStateException(IngestionErrorCodes.EMBEDDING_FAILED
                            + "维度不一致: expected=" + state.embeddingDimension() + " actual=" + actual);
                }
            }
            batch = buildBatch(state, slice, vectors, index, from);
            artifacts.writeJson(key, batch);   // 先落盘，再返回完成
            log.info("批次 embedding 完成并外置: batchIndex={}, size={}, key={}", index, slice.size(), key);

            // 故障注入点（生产 no-op）：A2 —— 批次产物已写、节点未返回（Checkpoint 未提交）
            faultInjector.at(WorkflowFaultInjector.Points.EMBED_AFTER_BATCH_ARTIFACT,
                    Map.of("taskId", state.taskId(), "documentId", dbDocumentId, "batchIndex", index));
        } else {
            log.info("复用已有批次产物: batchIndex={}, key={}", index, key);
        }

        int next = index + 1;
        int total = Math.max(1, state.totalBatches());
        int progress = Math.min(90, 70 + (int) Math.round(next * 20.0 / total));
        statusUpdater.updateDocumentStatus(dbDocumentId, "embedding", progress, null);

        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put(IngestionState.NEXT_BATCH_INDEX, next);   // base ⇒ 绝对值
        delta.put(IngestionState.STATUS, next >= state.totalBatches() ? "EMBEDDED" : "EMBEDDING");
        return delta;
    }

    /** 已有产物可复用则返回它，否则返回 {@code null}（调用方重算）。 */
    private EmbeddingBatchArtifact tryReuse(String key, IngestionState state, List<AcademicChunk> slice,
            int index, int from) {
        if (!artifacts.exists(key)) {
            return null;
        }
        try {
            EmbeddingBatchArtifact existing = artifacts.readJson(key, EmbeddingBatchArtifact.class);
            List<String> expectedIds = slice.stream().map(AcademicChunk::getChunkId).toList();
            List<String> expectedHashes = slice.stream().map(AcademicChunk::getContentHash).toList();
            boolean ok = existing.getBatchIndex() == index
                    && existing.getFromChunkOrder() == from
                    && state.embeddingFingerprint().equals(existing.getEmbeddingFingerprint())
                    && existing.getDimension() == state.embeddingDimension()
                    && existing.size() == slice.size()
                    && expectedIds.equals(existing.getChunkIds())
                    && expectedHashes.equals(existing.getContentHashes())
                    && digestOf(expectedIds, expectedHashes).equals(existing.getDigest());
            if (ok) {
                return existing;
            }
            log.warn("批次产物校验不通过，重算: key={}, batchIndex={}", key, index);
        } catch (Exception e) {
            log.warn("批次产物读取失败，重算: key={}, err={}", key, e.toString());
        }
        return null;
    }

    private EmbeddingBatchArtifact buildBatch(IngestionState state, List<AcademicChunk> slice,
            List<float[]> vectors, int index, int from) {
        List<String> ids = new ArrayList<>(slice.size());
        List<String> hashes = new ArrayList<>(slice.size());
        for (AcademicChunk chunk : slice) {
            ids.add(chunk.getChunkId());
            hashes.add(chunk.getContentHash());
        }
        EmbeddingBatchArtifact batch = new EmbeddingBatchArtifact();
        batch.setTaskId(String.valueOf(state.taskId()));
        batch.setDbDocumentId(state.dbDocumentId());
        batch.setDocumentVersion(state.documentVersion());
        batch.setBatchIndex(index);
        batch.setFromChunkOrder(from);
        batch.setEmbeddingFingerprint(state.embeddingFingerprint());
        batch.setModelId(state.<String>value(IngestionState.EMBEDDING_MODEL_ID).orElse(""));
        batch.setDimension(state.embeddingDimension());
        batch.setChunkIds(ids);
        batch.setContentHashes(hashes);
        batch.setVectors(new ArrayList<>(vectors));
        batch.setDigest(digestOf(ids, hashes));
        return batch;
    }

    private String digestOf(List<String> ids, List<String> hashes) {
        return artifacts.sha256Hex(String.join("|", ids) + "#" + String.join("|", hashes));
    }
}
