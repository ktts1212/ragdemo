package com.example.ragdemo.model.dto;

/**
 * MinIO 上传操作的返回结果
 */
public record MinioUploadResult(
        String bucket,
        String objectName,
        Long fileSize,
        String contentType
) {}
