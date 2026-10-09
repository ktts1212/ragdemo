package com.example.ragdemo.service;

import com.example.ragdemo.model.dto.MinioUploadResult;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface MinioService {

    /**
     * 上传文件到 MinIO
     *
     * @param file       用户上传的文件
     * @param objectName MinIO 中的存储路径，如 "knowledge-base/1/20260624/uuid.pdf"
     * @return 上传结果（bucket、objectName、文件大小、contentType）
     */
    MinioUploadResult upload(MultipartFile file, String objectName);

    /**
     * 上传字节内容（用于解析产物：marker-raw.json / canonical.json / chunks.json 等）。
     *
     * @param content     内容字节
     * @param objectName  MinIO 存储路径
     * @param contentType MIME 类型（null 时按 octet-stream）
     */
    MinioUploadResult upload(byte[] content, String objectName, String contentType);

    /**
     * 从 MinIO 下载文件
     *
     * @param objectName MinIO 中的存储路径
     * @return 文件输入流
     */
    InputStream download(String objectName);

    /**
     * 从 MinIO 删除文件
     *
     * @param objectName MinIO 中的存储路径
     */
    void delete(String objectName);
}
