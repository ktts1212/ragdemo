package com.example.ragdemo.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档分块表（document_chunks）实体
 * <p>
 * 每个 chunk 包含一段文档文本、对应的 embedding 向量及元数据。
 * 通过 document_id 外键关联 documents 表。
 */
@Data
public class DocumentChunk {

    /** 主键 ID */
    private Long id;

    /** 所属文档 ID（关联 documents.id） */
    private Long documentId;

    /** 所属知识库 ID（关联 knowledge_bases.id） */
    private Long knowledgeBaseId;

    /** 该 chunk 在文档内的序号（从 0 开始） */
    private Integer chunkIndex;

    /** 分块文本内容 */
    private String content;

    /** 所在页码（PDF 等带页码的文档可用） */
    private Integer pageNumber;

    /** Token 数量（用于统计或 LLM 上下文窗口控制） */
    private Integer tokenCount;

    /** 在原文中的起始偏移量（字符数） */
    private Integer startOffset;

    /** 在原文中的结束偏移量（字符数） */
    private Integer endOffset;

    /** 额外元数据（JSON 格式，可存放标题、章节等） */
    private String metadata;

    /** 文本对应的 embedding 向量（float 数组） */
    private float[] embedding;

    /** 查询时与问题的相似度得分（仅检索回填，不存库） */
    private Double score;

    /** 创建时间 */
    private LocalDateTime createdAt;
}