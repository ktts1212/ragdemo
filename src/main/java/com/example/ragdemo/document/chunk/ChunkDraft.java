package com.example.ragdemo.document.chunk;

import java.util.List;

import com.example.ragdemo.document.domain.block.DocumentBlock;

/**
 * Chunker 内部的"待定稿 chunk"。
 * <p>
 * 各 BlockKind 的专用 chunker 只产出 draft（内容 + 类型 + provenance），
 * 由 {@link SectionAwareChunker} 统一定稿（order / chunkId / embeddingText / tokenCount / hash），
 * 从而使各个 chunker 保持无状态、易测试。
 * <p>
 * 注意：draft 不承载 "oversized" 信息 —— 是否超长由定稿时实测
 * （{@code AcademicChunk.tokenCount > maxTokens}）得出，
 * 避免"超长块的切分产物"与"超长 chunk"两个概念混淆。
 *
 * @param chunkType    高层类型
 * @param content      纯净内容
 * @param blockTypes   组成该 chunk 的 block 类型（有序去重）
 * @param sourceBlocks 来源 block（用于 provenance union 与页码聚合）
 * @param referenceIds 来源文献 id（仅 REFERENCE 使用）
 */
public record ChunkDraft(
        ChunkType chunkType,
        String content,
        List<BlockKind> blockTypes,
        List<DocumentBlock> sourceBlocks,
        List<String> referenceIds
) {

    /** 常用构造：无 referenceIds。 */
    public static ChunkDraft of(ChunkType chunkType, String content,
            List<BlockKind> blockTypes, List<DocumentBlock> sourceBlocks) {
        return new ChunkDraft(chunkType, content, blockTypes, sourceBlocks, List.of());
    }
}
