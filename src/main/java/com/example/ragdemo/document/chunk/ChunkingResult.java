package com.example.ragdemo.document.chunk;

import java.util.List;

/**
 * Chunker 的完整输出：chunk 列表 + warnings + QA 度量。
 * <p>
 * 风格对齐 {@code ValidationResult}（errors/warnings/metrics）。
 *
 * @param documentId 文档 id
 * @param chunks     全部 chunk（按 {@code order} 升序）
 * @param warnings   非致命问题（QA 预警），不阻断流程
 * @param metrics    Chunk QA 度量
 */
public record ChunkingResult(
        String documentId,
        List<AcademicChunk> chunks,
        List<String> warnings,
        ChunkQualityMetrics metrics
) {

    public int chunkCount() {
        return chunks == null ? 0 : chunks.size();
    }

    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }

    /** 按类型过滤（调试/QA 用）。 */
    public List<AcademicChunk> chunksOf(ChunkType type) {
        if (chunks == null || type == null) {
            return List.of();
        }
        return chunks.stream().filter(c -> c.getChunkType() == type).toList();
    }
}
