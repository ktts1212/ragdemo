package com.example.ragdemo.workflow.ingestion.artifact;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 单批 Embedding 的外置产物（step4 §6.3）—— 写入 MinIO，<b>不进</b> Graph State。
 * <p>
 * 字段刻意包含足够多的"来源指纹"，用于判断能否安全复用：
 * {@code chunkIds}（有序）、{@code contentHashes}（即 {@code embeddingText} 的哈希）、
 * 模型/维度/批次大小指纹。任一不匹配 ⇒ 该批<b>必须重算</b>，避免模型或分块策略变更后误用旧向量。
 * <p>
 * 用 Lombok POJO 而非 record：保证 Jackson 往返（含 {@code List<float[]>}）零歧义。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingBatchArtifact {

    private String taskId;
    private long dbDocumentId;
    private int documentVersion;
    private int batchIndex;
    /** 模型 + 维度 + 批次大小 的指纹 */
    private String embeddingFingerprint;
    private String modelId;
    private int dimension;
    /** 该批在全局 chunk 顺序中的起点（= batchIndex * batchSize），用于顺序还原 */
    private int fromChunkOrder;

    /** 有序 chunkId（与 vectors 一一对应） */
    private List<String> chunkIds = new ArrayList<>();
    /** 有序 contentHash = embeddingText 的 SHA-256 前 16 位 */
    private List<String> contentHashes = new ArrayList<>();
    /** 向量列表（顺序与 chunkIds 一致） */
    private List<float[]> vectors = new ArrayList<>();
    /** 内容摘要：用于检测产物被截断/篡改 */
    private String digest;

    public int size() {
        return vectors == null ? 0 : vectors.size();
    }
}
