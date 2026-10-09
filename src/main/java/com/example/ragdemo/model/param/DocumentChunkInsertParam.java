package com.example.ragdemo.model.param;

import lombok.Data;

/**
 * document_chunks 插入参数，embedding 以 pgvector 兼容字符串传入
 */
@Data
public class DocumentChunkInsertParam {
    private Long documentId;
    private Long knowledgeBaseId;
    private int chunkIndex;
    private String content;
    private Integer pageNumber;
    private Integer tokenCount;
    private Integer startOffset;
    private Integer endOffset;
    private String metadata;
    /** pgvector 格式字符串，如 "[0.12,0.03,-0.44,...]" */
    private String embedding;

    // ==================== 学术链路字段（skill §6；旧通用链路全为 null） ====================

    /** 稳定 chunk key（= AcademicChunk.chunkId），与 document_id 组成唯一键 */
    private String chunkKey;

    /** 高层 chunk 类型：TEXT / LIST / FIGURE / TABLE / FORMULA / FOOTNOTE / CAPTION / MIXED / ABSTRACT / REFERENCE */
    private String chunkType;

    private String sectionId;
    private String sectionTitle;

    /** PostgreSQL text[] 字面量，如 {"4. Methods","4.1 Datasets"}（经 VectorUtils.toPgTextArray） */
    private String sectionPath;

    private Integer sectionLevel;

    /** 真正送入 embedding 的文本（= content + Document/Section 前缀），与 content 分离保存 */
    private String embeddingText;

    /** embeddingText 的 SHA-256 前 16 位（变更检测） */
    private String contentHash;

    /** chunk 结束页（学术 chunk 可跨页） */
    private Integer endPage;
}
