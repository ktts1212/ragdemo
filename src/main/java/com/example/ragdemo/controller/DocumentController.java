package com.example.ragdemo.controller;

import com.example.ragdemo.common.Result;
import com.example.ragdemo.model.dto.KnowledgeDocumentDto;
import com.example.ragdemo.model.vo.DocumentVO;
import com.example.ragdemo.service.DocumentService;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping
    public Result<List<DocumentVO>> getDocList() {
        List<DocumentVO> list = documentService.selectDocumentList();
        return Result.success(list);
    }

    @PostMapping("/upload")
    public Result<KnowledgeDocumentDto> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "knowledgeBaseId", defaultValue = "1") Long knowledgeBaseId,
            @RequestParam(value = "parseMode", required = false) String parseMode) {
        log.info("文件的名?{}", file.getOriginalFilename());
        log.info("文件的大?{}", file.getSize());
        log.info("parseMode 参数: {}", parseMode);
        // 三级决策第 1 级：上传参数（null / AUTO 时继续用配置默认与自动判断）
        return Result.success(documentService.upload(file, knowledgeBaseId,
                com.example.ragdemo.rag.ingest.ParseMode.from(parseMode)));
    }

    @PostMapping("/batch-upload")
    public Result<List<KnowledgeDocumentDto>> batchUpload(
            @RequestPart("files") MultipartFile[] files,
            @RequestParam(value = "knowledgeBaseId", defaultValue = "1") Long knowledgeBaseId) {
        return Result.success(documentService.batchUpload(files, knowledgeBaseId));
    }
}
