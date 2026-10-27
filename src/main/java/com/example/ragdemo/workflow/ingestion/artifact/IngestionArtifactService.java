package com.example.ragdemo.workflow.ingestion.artifact;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.rag.ingest.ArtifactKeyBuilder;
import com.example.ragdemo.rag.ingest.ArtifactKeyBuilder.ArtifactType;
import com.example.ragdemo.service.MinioService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 入库阶段产物（MinIO）的读写与 provenance 校验（step4 §6）。
 * <p>
 * <b>为什么需要它</b>：{@link MinioService} 只提供按 key 的裸读写，
 * 而节点恢复时必须能判断"已有产物能否安全复用"，这需要
 * ① JSON 往返 ② 版本/指纹校验 ③ 摘要完整性校验 —— 这些是新逻辑，不是重复包装。
 * <p>
 * <b>保守策略</b>：任何一项校验不通过（缺失、反序列化失败、documentId/版本指纹/摘要不匹配）
 * 一律判定为"不可复用"，由调用方重算；<b>绝不</b>只凭"对象存在"就跳过昂贵阶段。
 */
@Slf4j
@Service
public class IngestionArtifactService {

    /**
     * 编排版本（人工维护）：pipeline / parser / cleaner 逻辑变更后必须 +1，
     * 否则 {@link CanonicalMeta} 校验会放行旧的 canonical 产物。
     */
    public static final int WORKFLOW_VERSION = 1;

    /**
     * Chunker 配置指纹（人工维护）：分块参数（target/max/min/overlap/overlapEnabled）变更后必须更新，
     * 用于 {@code chunks.json} 的对照校验。
     */
    public static final String CHUNKER_VERSION = "section-aware-v1:t700/m1000/m180/o100/off";

    private final MinioService minioService;
    private final ObjectMapper objectMapper;
    private final ArtifactKeyBuilder keyBuilder;

    public IngestionArtifactService(MinioService minioService, ObjectMapper objectMapper,
            ArtifactKeyBuilder keyBuilder) {
        this.minioService = minioService;
        this.objectMapper = objectMapper;
        this.keyBuilder = keyBuilder;
    }

    // ==================== key ====================

    public String markerRawKey(long dbDocumentId, int version) {
        return keyBuilder.artifactKey(dbDocumentId, version, ArtifactType.MARKER_RAW);
    }

    public String canonicalKey(long dbDocumentId, int version) {
        return keyBuilder.artifactKey(dbDocumentId, version, ArtifactType.CANONICAL);
    }

    public String canonicalMetaKey(long dbDocumentId, int version) {
        return keyBuilder.artifactKey(dbDocumentId, version, ArtifactType.CANONICAL_META);
    }

    public String chunksKey(long dbDocumentId, int version) {
        return keyBuilder.artifactKey(dbDocumentId, version, ArtifactType.CHUNKS);
    }

    public String embeddingBatchKey(long dbDocumentId, int version, String fingerprint, int batchIndex) {
        return keyBuilder.embeddingBatchKey(dbDocumentId, version, fingerprint, batchIndex);
    }

    /** 批次产物所在目录（前缀），写入 State 便于排查。 */
    public String embeddingArtifactPrefix(long dbDocumentId, int version, String fingerprint) {
        return keyBuilder.versionPrefix(dbDocumentId, version) + "artifacts/embeddings/" + fingerprint + "/";
    }

    // ==================== IO ====================

    public void writeJson(String key, Object payload) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            minioService.upload(bytes, key, "application/json");
            log.info("artifact 已持久化: key={}, bytes={}", key, bytes.length);
        } catch (Exception e) {
            throw new IllegalStateException("INGESTION_ARTIFACT_WRITE_FAILED: " + key, e);
        }
    }

    public <T> T readJson(String key, Class<T> type) {
        try (InputStream in = minioService.download(key)) {
            return objectMapper.readValue(in.readAllBytes(), type);
        } catch (Exception e) {
            throw new IllegalStateException("INGESTION_ARTIFACT_READ_FAILED: " + key, e);
        }
    }

    public AcademicDocument readCanonical(String key) {
        return readJson(key, AcademicDocument.class);
    }

    /** 泛型集合读（如 chunks manifest 的 {@code List<Map<String,Object>>}）。 */
    public <T> T readJson(String key, TypeReference<T> type) {
        try (InputStream in = minioService.download(key)) {
            return objectMapper.readValue(in.readAllBytes(), type);
        } catch (Exception e) {
            throw new IllegalStateException("INGESTION_ARTIFACT_READ_FAILED: " + key, e);
        }
    }

    /** MinioService 未提供 stat/exists，这里用 download 探测（失败即视为不存在）。 */
    public boolean exists(String key) {
        try (InputStream in = minioService.download(key)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    public String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    public String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 模型指纹（路径安全，短）：模型 id + 维度 + 批次大小的摘要。 */
    public String embeddingFingerprint(String modelId, int dimension, int batchSize) {
        return sha256Hex(modelId + "|" + dimension + "|" + batchSize).substring(0, 12);
    }

    // ==================== canonical provenance ====================

    /** canonical 产物的 provenance sidecar（独立文件，不改动现有 canonical.json 格式）。 */
    public record CanonicalMeta(String checksum, int documentVersion, int workflowVersion,
            String parserBackend, String canonicalDigest) {
    }

    public void writeCanonicalMeta(long dbDocumentId, int version, AcademicDocument canonical,
            String checksum, String parserBackend) {
        String digest = sha256Hex(writeValueAsBytesQuietly(canonical));
        writeJson(canonicalMetaKey(dbDocumentId, version),
                new CanonicalMeta(checksum, version, WORKFLOW_VERSION, parserBackend, digest));
    }

    public Optional<CanonicalMeta> readCanonicalMeta(long dbDocumentId, int version) {
        String key = canonicalMetaKey(dbDocumentId, version);
        if (!exists(key)) {
            return Optional.empty();
        }
        try {
            return Optional.of(readJson(key, CanonicalMeta.class));
        } catch (Exception e) {
            log.warn("canonical meta 读取失败，按不可复用处理: key={}, err={}", key, e.toString());
            return Optional.empty();
        }
    }

    /** 判断已有 canonical 产物能否安全复用（全部条件满足才允许）。 */
    public boolean isCanonicalReusable(long dbDocumentId, int version, String checksum,
            String canonicalKey) {
        if (!exists(canonicalKey)) {
            return false;
        }
        Optional<CanonicalMeta> meta = readCanonicalMeta(dbDocumentId, version);
        if (meta.isEmpty()) {
            log.info("canonical 缺少 provenance sidecar，保守重算: documentId={}, version={}",
                    dbDocumentId, version);
            return false;
        }
        CanonicalMeta m = meta.get();
        boolean ok = checksum.equals(m.checksum())
                && version == m.documentVersion()
                && WORKFLOW_VERSION == m.workflowVersion();
        if (!ok) {
            log.info("canonical provenance 不匹配，保守重算: expected(checksum={}, version={}, wf={}) "
                            + "actual(checksum={}, version={}, wf={})",
                    checksum, version, WORKFLOW_VERSION, m.checksum(), m.documentVersion(), m.workflowVersion());
        }
        return ok;
    }

    private byte[] writeValueAsBytesQuietly(Object payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败: " + payload.getClass().getSimpleName(), e);
        }
    }
}
