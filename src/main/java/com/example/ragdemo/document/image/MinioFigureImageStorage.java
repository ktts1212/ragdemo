package com.example.ragdemo.document.image;

import java.io.ByteArrayInputStream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.ragdemo.config.MinioProperties;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;

/**
 * MinIO 图片存储（生产）。
 * <p>
 * 通过 rag.image.storage=minio 开启。返回 MinIO object key。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.image.storage", havingValue = "minio")
public class MinioFigureImageStorage implements FigureImageStorage {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public MinioFigureImageStorage(MinioClient minioClient, MinioProperties minioProperties) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
    }

    @Override
    public String store(String documentId, String blockId, byte[] imageBytes, String contentType) {
        String key = ImageKeyUtils.buildKey(documentId, blockId, contentType);
        try (ByteArrayInputStream in = new ByteArrayInputStream(imageBytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(key)
                    .stream(in, imageBytes.length, -1)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build());
            log.debug("图片已上传 MinIO: key={}, size={} bytes", key, imageBytes.length);
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 图片上传失败: " + key, e);
        }
    }
}
