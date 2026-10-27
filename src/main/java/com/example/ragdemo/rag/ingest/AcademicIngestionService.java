package com.example.ragdemo.rag.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.document.chunk.AcademicChunk;
import com.example.ragdemo.document.chunk.ChunkingResult;
import com.example.ragdemo.document.chunk.SectionAwareChunker;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.pipeline.CanonicalDocumentPipeline;
import com.example.ragdemo.document.pipeline.PipelineResult;
import com.example.ragdemo.embedding.EmbeddingGateway;
import com.example.ragdemo.model.entity.KnowledgeDocument;
import com.example.ragdemo.model.param.DocumentChunkInsertParam;
import com.example.ragdemo.persistence.AcademicChunkPersistenceMapper;
import com.example.ragdemo.service.MinioService;
import com.example.ragdemo.rag.ingest.ArtifactKeyBuilder.ArtifactType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * ACADEMIC 链路的入库编排（skill §9 / §10 / §11 / §13 / §14）。
 * <pre>
 * 1. MinIO 下载源 PDF
 * 2. CanonicalDocumentPipeline：Marker → Parser → Repair → Cleaner（→ Canonical）
 * 3. hard-invalid → 抛错（failed，不 indexed）；warning-only → 允许入库
 * 4. artifact 持久化：marker-raw.json / canonical.json / chunks.json（决策 D2 的版本化 key）
 * 5. SectionAwareChunker → AcademicChunk[]
 * 6. embedding（事务外、分批、可重试）
 * 7. 映射 + 事务化替换写入（{@link ChunkPersistenceService}）
 * </pre>
 * <b>失败语义</b>：任一步失败都抛出，由调用方统一置 failed —— <b>绝不</b>静默回退为通用链路再标 indexed（skill §10）。
 */
@Slf4j
@Service
public class AcademicIngestionService {

    private static final String ERROR_CODE_DOWNLOAD = "ACADEMIC_DOWNLOAD_FAILED: ";
    private static final String ERROR_CODE_INVALID = "ACADEMIC_INVALID_CANONICAL: ";
    private static final String ERROR_CODE_NO_CHUNKS = "ACADEMIC_NO_CHUNKS: ";
    private static final String ERROR_CODE_CHECKSUM = "ACADEMIC_MISSING_CHECKSUM: ";

    private final MinioService minioService;
    private final CanonicalDocumentPipeline canonicalDocumentPipeline;
    private final SectionAwareChunker sectionAwareChunker;
    private final EmbeddingGateway embeddingGateway;
    private final AcademicChunkPersistenceMapper persistenceMapper;
    private final ChunkPersistenceService chunkPersistenceService;
    private final ArtifactKeyBuilder artifactKeyBuilder;
    private final IngestionStatusUpdater statusUpdater;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    /** Step 4 提取出的共享 manifest 构建器：与入库图逐字一致，避免格式漂移 */
    private final ChunkManifestBuilder chunkManifestBuilder;

    public AcademicIngestionService(MinioService minioService,
            CanonicalDocumentPipeline canonicalDocumentPipeline,
            SectionAwareChunker sectionAwareChunker,
            EmbeddingGateway embeddingGateway,
            AcademicChunkPersistenceMapper persistenceMapper,
            ChunkPersistenceService chunkPersistenceService,
            ArtifactKeyBuilder artifactKeyBuilder,
            IngestionStatusUpdater statusUpdater,
            ObjectMapper objectMapper,
            RagProperties ragProperties,
            ChunkManifestBuilder chunkManifestBuilder) {
        this.minioService = minioService;
        this.canonicalDocumentPipeline = canonicalDocumentPipeline;
        this.sectionAwareChunker = sectionAwareChunker;
        this.embeddingGateway = embeddingGateway;
        this.persistenceMapper = persistenceMapper;
        this.chunkPersistenceService = chunkPersistenceService;
        this.artifactKeyBuilder = artifactKeyBuilder;
        this.statusUpdater = statusUpdater;
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
        this.chunkManifestBuilder = chunkManifestBuilder;
    }

    /**
     * 执行 ACADEMIC 入库。成功时文档已置 indexed；失败时抛异常（调用方置 failed）。
     */
    public void ingest(KnowledgeDocument document, Long taskId) {
        long dbDocumentId = document.getId();
        long kbId = document.getKnowledgeBaseId();
        int version = document.getVersion() == null ? 1 : document.getVersion();
        String domainDocumentId = domainDocumentIdOf(document);

        // 1. 下载源文件
        statusUpdater.updateDocumentStatus(dbDocumentId, "parsing", 20, null);
        byte[] pdfBytes = downloadSource(document);

        // 2. Marker → Parser → Repair → Cleaner → Canonical
        PipelineResult result = canonicalDocumentPipeline.process(
                domainDocumentId, pdfBytes, document.getFileName());
        AcademicDocument canonical = result.document();

        // 3. hard-invalid 不入库（skill §8）；warning-only 允许
        if (!result.valid()) {
            throw new IllegalStateException(ERROR_CODE_INVALID
                    + result.cleaning().validation().errors());
        }
        if (!result.warnings().isEmpty()) {
            log.warn("canonical warning-only 入库: documentId={}, warnings={}",
                    dbDocumentId, result.warnings());
        }

        // 4. artifact 持久化（先存 marker-raw / canonical，便于离线重跑，skill §11）
        persistArtifact(dbDocumentId, version, ArtifactType.MARKER_RAW, result.rawDocument());
        persistArtifact(dbDocumentId, version, ArtifactType.CANONICAL, canonical);

        // 5. 分块
        statusUpdater.updateDocumentStatus(dbDocumentId, "chunking", 45, null);
        ChunkingResult chunking = sectionAwareChunker.chunk(canonical);
        List<AcademicChunk> chunks = chunking.chunks();
        if (chunks.isEmpty()) {
            throw new IllegalStateException(ERROR_CODE_NO_CHUNKS + "documentId=" + dbDocumentId);
        }
        persistArtifact(dbDocumentId, version, ArtifactType.CHUNKS, buildChunkManifest(chunks));

        // 6. embedding（事务外，分批 + 有限重试；任一批失败 → 整篇不 indexed）
        statusUpdater.updateDocumentStatus(dbDocumentId, "embedding", 70, null);
        List<String> embeddingTexts = chunks.stream().map(AcademicChunk::getEmbeddingText).toList();
        List<float[]> vectors = embeddingGateway.embedDocuments(embeddingTexts);
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException("embedding 数量与 chunk 数量不一致: "
                    + vectors.size() + " vs " + chunks.size());
        }

        // 7. 映射 + 事务化替换写入（含 status=indexed）
        List<DocumentChunkInsertParam> params = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            params.add(persistenceMapper.toInsertParam(
                    chunks.get(i), dbDocumentId, kbId, vectors.get(i)));
        }
        chunkPersistenceService.replaceChunks(dbDocumentId, params);
        statusUpdater.markTaskSuccess(taskId);

        log.info("ACADEMIC 入库完成: documentId={}, version={}, chunks={}, embeddingModel={}",
                dbDocumentId, version, params.size(), ragProperties.getEmbedding().getModel());
    }

    // ==================== 内部 ====================

    /**
     * 领域 documentId：采用文件内容校验和（SHA-256）。
     * <p>
     * 理由（skill §5.1）：<b>不</b>把 DB surrogate id 塞进领域对象；
     * 且内容寻址使 chunkKey（{@code domainId:sectionId:index}）在重传相同内容时保持稳定，
     * 便于幂等与 diff。
     */
    private String domainDocumentIdOf(KnowledgeDocument document) {
        String checksum = document.getChecksum();
        if (checksum == null || checksum.isBlank()) {
            throw new IllegalStateException(ERROR_CODE_CHECKSUM + "documentId=" + document.getId());
        }
        return checksum;
    }

    private byte[] downloadSource(KnowledgeDocument document) {
        try (InputStream inputStream = minioService.download(document.getStoragePath())) {
            return inputStream.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(ERROR_CODE_DOWNLOAD + document.getStoragePath(), e);
        }
    }

    private void persistArtifact(long dbDocumentId, int version, ArtifactType type, Object payload) {
        String key = artifactKeyBuilder.artifactKey(dbDocumentId, version, type);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            minioService.upload(bytes, key, "application/json");
            log.info("artifact 已持久化: {}", key);
        } catch (Exception e) {
            throw new IllegalStateException("ACADEMIC_ARTIFACT_WRITE_FAILED: " + key, e);
        }
    }

    /** chunk manifest：委托共享实现（Step 4 提取，保证与入库图格式一致）。 */
    private List<Map<String, Object>> buildChunkManifest(List<AcademicChunk> chunks) {
        return chunkManifestBuilder.build(chunks);
    }

    /** 供调用方读取 canonical JSON 节点（避免重复序列化两次）。 */
    JsonNode rawDocumentOf(PipelineResult result) {
        return result.rawDocument();
    }
}
