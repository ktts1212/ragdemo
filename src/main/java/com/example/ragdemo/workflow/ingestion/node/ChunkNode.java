package com.example.ragdemo.workflow.ingestion.node;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.chunk.AcademicChunk;
import com.example.ragdemo.document.chunk.ChunkingResult;
import com.example.ragdemo.document.chunk.SectionAwareChunker;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.rag.ingest.ChunkManifestBuilder;
import com.example.ragdemo.rag.ingest.IngestionStatusUpdater;
import com.example.ragdemo.workflow.ingestion.IngestionErrorCodes;
import com.example.ragdemo.workflow.ingestion.IngestionState;
import com.example.ragdemo.workflow.ingestion.artifact.IngestionArtifactService;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.extern.slf4j.Slf4j;

/**
 * 分块节点：从 {@code canonical.json} <b>确定性重建</b> chunk 集合，写 {@code chunks.json} manifest。
 * <p>
 * <b>为什么不把 chunk 列表放进 State</b>：那是大对象；State 只保留 {@code canonicalKey} +
 * {@code chunkCount} + {@code chunksDigest}，其余节点按需重建（step4 §5）。
 * <p>
 * <b>确定性校验</b>：若 manifest 已存在，则用它的 {@code chunkId} 顺序与数量核对重建结果；
 * 不一致说明"同一 canonical 不能稳定重建 chunk"，直接报错而不是继续（step4 §6.2）。
 */
@Slf4j
@Component
public class ChunkNode {

    private final SectionAwareChunker chunker;
    private final ChunkManifestBuilder manifestBuilder;
    private final IngestionArtifactService artifacts;
    private final IngestionStatusUpdater statusUpdater;

    public ChunkNode(SectionAwareChunker chunker, ChunkManifestBuilder manifestBuilder,
            IngestionArtifactService artifacts, IngestionStatusUpdater statusUpdater) {
        this.chunker = chunker;
        this.manifestBuilder = manifestBuilder;
        this.artifacts = artifacts;
        this.statusUpdater = statusUpdater;
    }

    public Map<String, Object> apply(IngestionState state) {
        long dbDocumentId = state.dbDocumentId();
        int version = state.documentVersion();
        statusUpdater.updateDocumentStatus(dbDocumentId, "chunking", 45, null);

        AcademicDocument canonical = artifacts.readCanonical(state.canonicalKey());
        ChunkingResult chunking = chunker.chunk(canonical);
        List<AcademicChunk> chunks = chunking.chunks();
        if (chunks.isEmpty()) {
            throw new IllegalStateException(IngestionErrorCodes.NO_CHUNKS + "documentId=" + dbDocumentId);
        }

        String chunksKey = artifacts.chunksKey(dbDocumentId, version);
        verifyAgainstExistingManifest(chunksKey, chunks);
        artifacts.writeJson(chunksKey, manifestBuilder.build(chunks));

        int batchSize = Math.max(1, state.embeddingBatchSize());
        int totalBatches = (chunks.size() + batchSize - 1) / batchSize;

        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put(IngestionState.CHUNKS_KEY, chunksKey);
        delta.put(IngestionState.CHUNK_COUNT, chunks.size());
        delta.put(IngestionState.CHUNKS_DIGEST, chunksDigest(chunks));
        delta.put(IngestionState.CHUNKER_VERSION, IngestionArtifactService.CHUNKER_VERSION);
        delta.put(IngestionState.TOTAL_BATCHES, totalBatches);
        delta.put(IngestionState.NEXT_BATCH_INDEX, 0);
        delta.put(IngestionState.STATUS, "CHUNKED");
        log.info("入库图 chunk: documentId={}, chunks={}, batches={}", dbDocumentId, chunks.size(), totalBatches);
        return delta;
    }

    private String chunksDigest(List<AcademicChunk> chunks) {
        StringBuilder sb = new StringBuilder(chunks.size() * 48);
        for (AcademicChunk chunk : chunks) {
            sb.append(chunk.getChunkId()).append(':').append(chunk.getContentHash()).append('|');
        }
        return artifacts.sha256Hex(sb.toString());
    }

    private void verifyAgainstExistingManifest(String chunksKey, List<AcademicChunk> rebuilt) {
        if (!artifacts.exists(chunksKey)) {
            return;
        }
        List<Map<String, Object>> manifest = artifacts.readJson(chunksKey,
                new TypeReference<List<Map<String, Object>>>() {
                });
        if (manifest.size() != rebuilt.size()) {
            throw new IllegalStateException(IngestionErrorCodes.STATE_MISMATCH
                    + "chunks.json 与重建结果数量不一致: manifest=" + manifest.size()
                    + " rebuilt=" + rebuilt.size());
        }
        for (int i = 0; i < manifest.size(); i++) {
            Object manifestChunkId = manifest.get(i).get("chunkId");
            if (!rebuilt.get(i).getChunkId().equals(manifestChunkId)) {
                throw new IllegalStateException(IngestionErrorCodes.STATE_MISMATCH
                        + "chunkId 顺序不一致 at " + i + ": manifest=" + manifestChunkId
                        + " rebuilt=" + rebuilt.get(i).getChunkId());
            }
        }
        log.info("chunks.json 与重建结果一致（确定性校验通过）: key={}", chunksKey);
    }
}
