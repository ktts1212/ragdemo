package com.example.ragdemo.service;

/**
 * 异步入库服务：Kafka Consumer 收到消息后调用
 */
public interface IngestionService {

    /**
     * 执行文档异步入库全流程：
     * 下载原文 → 解析 → 分块 → embedding → 写入 document_chunks → 更新状态
     *
     * @param documentId 文档 ID
     * @param taskId     入库任务 ID
     */
    void ingest(Long documentId, Long taskId);
}
