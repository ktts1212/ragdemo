package com.example.ragdemo.document.chunk;

/**
 * Chunk QA 度量（skill §25）。
 * <p>
 * 与 {@code DocumentQualityMetrics} 保持同一风格：record + 可观测指标，
 * 使 Chunker 的行为在真实论文上可解释、可回归。
 */
public record ChunkQualityMetrics(
        int chunkCount,
        double avgTokens,
        int p50Tokens,
        int p90Tokens,
        int maxTokens,
        int minTokens,
        // ===== 异常指标（理想值见 skill §26）=====
        int oversizedChunkCount,
        int undersizedChunkCount,
        int emptyChunkCount,
        int noiseChunkCount,
        int crossSectionChunkCount,
        int splitParagraphCount,
        // ===== 分类指标 =====
        int textChunkCount,
        int listChunkCount,
        int figureChunkCount,
        int tableChunkCount,
        int formulaChunkCount,
        int footnoteChunkCount,
        int captionChunkCount,
        int mixedChunkCount,
        int abstractChunkCount,
        int referenceChunkCount,
        // ===== 跳过/丢失监控 =====
        int skippedFigureCount,
        int skippedTableCount,
        int skippedReferenceCount,
        // ===== overlap / 完整性 =====
        int overlapChunkCount,
        int duplicatedSourceBlockCount,
        double referenceChunkRatio,
        double sectionCoverageRatio
) {
}
