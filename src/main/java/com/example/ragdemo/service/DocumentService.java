package com.example.ragdemo.service;

import com.example.ragdemo.model.dto.KnowledgeDocumentDto;
import com.example.ragdemo.model.vo.DocumentVO;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentService {

    List<DocumentVO> selectDocumentList();

    /**
     * 单文件上传（同步）：校验 → MinIO → 插入 documents + ingestion_tasks → 发送 Kafka → 返回 DTO
     */
    KnowledgeDocumentDto upload(MultipartFile file, Long knowledgeBaseId);

    /**
     * 批量上传：每个文件走独立的上传 + 异步任务流程
     */
    List<KnowledgeDocumentDto> batchUpload(MultipartFile[] files, Long knowledgeBaseId);

}
