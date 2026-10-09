package com.example.ragdemo.document.chunk;

/**
 * Chunker 执行期的计数快照（供 {@link ChunkQualityReporter} 计算 QA 指标）。
 *
 * @param noiseBlockCount       抵达 Chunker 的 NOISE block 数（应为 0）
 * @param skippedFigureCount    内容为空的 Figure 数（不产出 chunk）
 * @param skippedTableCount     内容为空的 Table 数（不产出 chunk）
 * @param skippedReferenceCount rawText 为空的 Reference 数（不产出 chunk）
 * @param splitParagraphCount   被强制切分的超长 paragraph 数
 */
public record ChunkBuildStats(
        int noiseBlockCount,
        int skippedFigureCount,
        int skippedTableCount,
        int skippedReferenceCount,
        int splitParagraphCount
) {
}
