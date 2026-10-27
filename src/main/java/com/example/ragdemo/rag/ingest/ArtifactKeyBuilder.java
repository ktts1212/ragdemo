package com.example.ragdemo.rag.ingest;

import org.springframework.stereotype.Component;

/**
 * MinIO artifact key 构造（决策 D2）。
 * <p>
 * 层级命名：{@code documentId + version + artifactType}
 * <pre>
 * documents/{documentId}/versions/{version}/source/original.pdf
 * documents/{documentId}/versions/{version}/artifacts/marker-raw.json
 * documents/{documentId}/versions/{version}/artifacts/canonical.json
 * documents/{documentId}/versions/{version}/artifacts/chunks.json
 * </pre>
 * 版本化意义：同一文档重跑解析时版本递增，旧产物不被覆盖，可回溯/可对比。
 */
@Component
public class ArtifactKeyBuilder {

    /** artifact 类型与其在 version 目录下的相对路径。 */
    public enum ArtifactType {
        /** 原始文件（扩展名由实际上传文件决定，见 {@link #sourceKey}） */
        SOURCE("source/original.pdf"),
        /** Marker 原始 JSON（可离线重跑 Parser/Repair/Cleaner，不必再调 Marker） */
        MARKER_RAW("artifacts/marker-raw.json"),
        /** Canonical JSON（可离线重跑 Chunker） */
        CANONICAL("artifacts/canonical.json"),
        /** Chunk manifest（可选：便于对照分块结果） */
        CHUNKS("artifacts/chunks.json"),
        /** Canonical 产物的 provenance sidecar（Step 4：判断旧清洗结果能否安全复用，避免误用） */
        CANONICAL_META("artifacts/canonical.meta.json");

        private final String relativePath;

        ArtifactType(String relativePath) {
            this.relativePath = relativePath;
        }

        public String relativePath() {
            return relativePath;
        }
    }

    /** {@code documents/{documentId}/versions/{version}/} 前缀。 */
    public String versionPrefix(long documentId, int version) {
        return "documents/" + documentId + "/versions/" + version + "/";
    }

    /**
     * 原始文件 key，扩展名取自上传文件（未知时回退 {@code .pdf}）。
     * 保持决策中 {@code source/original.pdf} 的形态。
     */
    public String sourceKey(long documentId, int version, String fileExtension) {
        String ext = (fileExtension == null || fileExtension.isBlank()) ? ".pdf" : fileExtension;
        return versionPrefix(documentId, version) + "source/original" + ext;
    }

    /** 解析产物 key，如 {@code .../artifacts/canonical.json}。 */
    public String artifactKey(long documentId, int version, ArtifactType type) {
        return versionPrefix(documentId, version) + type.relativePath();
    }

    /**
     * 批次向量产物 key（Step 4）：
     * {@code documents/{id}/versions/{v}/artifacts/embeddings/{fingerprint}/batch-{index}.json}。
     * <p>
     * 用模型/参数指纹分层：模型、维度或批次大小变化 ⇒ fingerprint 变化 ⇒
     * 旧批次向量天然落在另一个目录，不会被误复用。
     */
    public String embeddingBatchKey(long documentId, int version, String fingerprint, int batchIndex) {
        return versionPrefix(documentId, version) + "artifacts/embeddings/" + fingerprint
                + "/batch-" + batchIndex + ".json";
    }
}
