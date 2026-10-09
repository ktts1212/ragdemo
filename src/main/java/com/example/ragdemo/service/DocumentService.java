package com.example.ragdemo.service;

import com.example.ragdemo.model.dto.KnowledgeDocumentDto;
import com.example.ragdemo.model.vo.DocumentVO;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentService {

    List<DocumentVO> selectDocumentList();

    /**
     * 单文件上传（同步）：校验 → <b>三级决策 parseMode</b> → MinIO → documents + ingestion_tasks → Kafka → DTO
     *
     * @param parseMode 上传参数（三级决策第 1 级）；null 或 AUTO 时继续用配置默认与扩展名/MIME 自动判断
     */
    KnowledgeDocumentDto upload(MultipartFile file, Long knowledgeBaseId,
            com.example.ragdemo.rag.ingest.ParseMode parseMode);

    /** 兼容旧调用（不显式指定 parseMode）。 */
    default KnowledgeDocumentDto upload(MultipartFile file, Long knowledgeBaseId) {
        return upload(file, knowledgeBaseId, null);
    }

    /**
     * 批量上传：每个文件走独立的上传 + 异步任务流程
     */
    List<KnowledgeDocumentDto> batchUpload(MultipartFile[] files, Long knowledgeBaseId);

}
