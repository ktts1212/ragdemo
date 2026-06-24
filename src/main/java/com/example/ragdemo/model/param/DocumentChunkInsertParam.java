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
}
