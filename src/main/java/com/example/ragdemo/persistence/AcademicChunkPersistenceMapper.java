package com.example.ragdemo.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.chunk.AcademicChunk;
import com.example.ragdemo.embedding.EmbeddingGateway;
import com.example.ragdemo.model.param.DocumentChunkInsertParam;
import com.example.ragdemo.util.VectorUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link AcademicChunk}（领域对象）→ {@link DocumentChunkInsertParam}（持久化 DTO）映射层（skill §5 / §12）。
 * <p>
 * <b>职责边界</b>：SectionAwareChunker 不知道数据库结构；本类也不知道分块逻辑。
 * 二者通过本映射层解耦。
 * <p>
 * <b>两个关键契约</b>：
 * <ol>
 *   <li><b>domainId 与 dbDocumentId 解耦</b>（skill §5）：
 *       {@code AcademicChunk.documentId} 是领域/来源标识，<b>不</b>写进 FK；
 *       写入 {@code document_chunks.document_id} 的是调用方传入的 {@code dbDocumentId}。</li>
 *   <li><b>embedding 必须由 {@code embeddingText} 计算</b>（skill §7）：
 *       因此本类提供接收 {@link EmbeddingGateway} 的重载，从<b>契约层面</b>保证这一点，
 *       而不是依赖调用方自觉。{@code content} 与 {@code embeddingText} 两者都入库，
 *       便于复现 / debug / content-vs-embeddingText 消融。</li>
 * </ol>
 */
@Component
public class AcademicChunkPersistenceMapper {

    private final ObjectMapper objectMapper;

    public AcademicChunkPersistenceMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 推荐入口：embedding 由 {@code chunk.embeddingText} 现算（保证契约）。
     */
    public DocumentChunkInsertParam toInsertParam(AcademicChunk chunk, long dbDocumentId, long kbId,
            EmbeddingGateway gateway) {
        String embeddingText = chunk.getEmbeddingText();
        if (embeddingText == null || embeddingText.isBlank()) {
            throw new IllegalArgumentException("embeddingText 为空，无法生成 embedding: chunkId="
                    + chunk.getChunkId());
        }
        float[] vector = gateway.embedDocuments(List.of(embeddingText)).get(0);
        return toInsertParam(chunk, dbDocumentId, kbId, vector);
    }

    /**
     * 批量流程用入口：embedding 由调用方传入（调用方须保证来自 {@code chunk.embeddingText}）。
     */
    public DocumentChunkInsertParam toInsertParam(AcademicChunk chunk, long dbDocumentId, long kbId,
            float[] embedding) {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk 不能为 null");
        }
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("embedding 不能为空: chunkId=" + chunk.getChunkId());
        }

        DocumentChunkInsertParam param = new DocumentChunkInsertParam();
        // ---- persistence key（与领域 documentId 解耦）----
        param.setDocumentId(dbDocumentId);
        param.setKnowledgeBaseId(kbId);
        param.setChunkIndex(chunk.getOrder());
        param.setChunkKey(chunk.getChunkId());

        // ---- 内容：content 与 embeddingText 分离（skill §7）----
        param.setContent(chunk.getContent());
        param.setEmbeddingText(chunk.getEmbeddingText());
        param.setEmbedding(VectorUtils.toPgVector(embedding));

        // ---- 章节 / 类型（专列，可 SQL 过滤）----
        param.setChunkType(chunk.getChunkType() == null ? null : chunk.getChunkType().name());
        param.setSectionId(chunk.getSectionId());
        param.setSectionTitle(chunk.getSectionTitle());
        param.setSectionLevel(chunk.getSectionLevel());
        param.setSectionPath(VectorUtils.toPgTextArray(chunk.getSectionPath()));
        param.setContentHash(chunk.getContentHash());

        // ---- 页码 / token ----
        param.setPageNumber(chunk.getStartPage());
        param.setEndPage(chunk.getEndPage());
        param.setTokenCount(chunk.getTokenCount());

        // ---- 新链路无字符偏移（不伪造；旧链路继续使用这两列）----
        param.setStartOffset(null);
        param.setEndOffset(null);

        // ---- 长尾 → metadata JSONB（skill §6）----
        param.setMetadata(toMetadataJson(chunk));
        return param;
    }

    /** 长尾字段打包进 metadata JSONB。 */
    private String toMetadataJson(AcademicChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("blockTypes", chunk.getBlockTypes());
        metadata.put("sourceBlockIds", chunk.getSourceBlockIds());
        metadata.put("overlapBlockIds", chunk.getOverlapBlockIds());
        metadata.put("referenceIds", chunk.getReferenceIds());
        metadata.put("charCount", chunk.getCharCount());
        metadata.put("oversized", chunk.isOversized());
        metadata.put("undersized", chunk.isUndersized());
        metadata.put("titleContext", chunk.getTitleContext());
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new IllegalStateException("metadata 序列化失败: chunkId=" + chunk.getChunkId(), e);
        }
    }
}
