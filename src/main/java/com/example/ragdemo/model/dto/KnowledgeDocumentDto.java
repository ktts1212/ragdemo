package com.example.ragdemo.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 上传接口响应 DTO，与 KnowledgeDocument 实体对应但只暴露前端需要的字段
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeDocumentDto(
        Long id,
        String fileName,
        String status,
        String uploadTime,
        Long fileSize,
        String contentType,
        Integer progress,
        Integer chunkCount,
        String errorMessage,
        Long knowledgeBaseId
) {}
