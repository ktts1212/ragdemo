package com.example.ragdemo.model.dto;

/**
 * Kafka 消息体：上传完成后投递给异步入库 consumer
 */
public record DocumentIngestionMessage(
        Long documentId,
        Long knowledgeBaseId,
        Long taskId
) {}
