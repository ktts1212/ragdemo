package com.example.ragdemo.controller;

import com.example.ragdemo.model.dto.UploadResponse;
import com.example.ragdemo.service.DocumentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/upload")
    public UploadResponse upload(@RequestParam("file") MultipartFile file) throws Exception {
        int chunks = documentService.upload(file);
        return new UploadResponse(file.getOriginalFilename(), chunks, "上传并向量化完成");
    }
}
