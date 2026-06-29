package com.example.ragdemo.model.dto;

import lombok.Data;

/**
 * pgvector 相似度检索结果中间 DTO。
 * <p>
 * 由 DocumentChunkMapper.searchSimilarChunks() 查询返回，
 * 后续在 ChatServiceImpl 中经过 minScore 过滤、context 拼接、sources 组装。
 */
@Data
public class RetrievedChunkDto {

    /** chunk 主键 ID */
    private Long chunkId;

    /** 所属文档 ID（关联 documents.id） */
    private Long documentId;

    /** 原始文件名（如 "心肌梗死指南.pdf"） */
    private String documentName;

    /** 所在页码（PDF 类文档有效，txt/md 为 null） */
    private Integer page;

    /** 该 chunk 在文档内的序号（从 0 开始） */
    private Integer chunkIndex;

    /** 分块文本内容（进入 prompt 的原文片段） */
    private String content;

    /** 向量余弦相似度 (0~1)，值越大越相关 */
    private Double score;
}
