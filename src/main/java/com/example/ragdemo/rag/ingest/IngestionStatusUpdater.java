package com.example.ragdemo.rag.ingest;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.example.ragdemo.mapper.DocumentsMapper;
import com.example.ragdemo.mapper.IngestionTaskMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 入库状态/任务状态更新的唯一入口（两条链路共用）。
 * <p>
 * 抽出来的原因：GENERIC 与 ACADEMIC 两条链路必须表现<b>一致的失败语义</b>
 * （skill §10），若各自实现一套状态更新，迟早会漂移。
 */
@Slf4j
@Service
public class IngestionStatusUpdater {

    private final DocumentsMapper documentsMapper;
    private final IngestionTaskMapper ingestionTaskMapper;

    public IngestionStatusUpdater(DocumentsMapper documentsMapper,
            IngestionTaskMapper ingestionTaskMapper) {
        this.documentsMapper = documentsMapper;
        this.ingestionTaskMapper = ingestionTaskMapper;
    }

    public void markTaskRunning(Long taskId) {
        ingestionTaskMapper.markRunning(taskId);
        log.info("任务开始执行: taskId={}", taskId);
    }

    public void markTaskSuccess(Long taskId) {
        ingestionTaskMapper.updateStatus(taskId, "success", null, LocalDateTime.now());
        log.info("任务执行成功: taskId={}", taskId);
    }

    public void markTaskFailed(Long taskId, String errorMessage) {
        ingestionTaskMapper.updateStatus(taskId, "failed", errorMessage, LocalDateTime.now());
        log.error("任务执行失败: taskId={}, error={}", taskId, errorMessage);
    }

    public void updateDocumentStatus(Long documentId, String status, int progress, String errorMessage) {
        documentsMapper.updateStatus(documentId, status, progress, errorMessage);
        log.info("文档状态更新: documentId={}, status={}, progress={}", documentId, status, progress);
    }

    public void updateChunkCount(Long documentId, int count) {
        documentsMapper.updateChunkCount(documentId, count);
        log.info("更新 chunk 数量: documentId={}, chunkCount={}", documentId, count);
    }
}
