package com.example.ragdemo.service.serviceImpl;

import com.example.ragdemo.config.MinioProperties;
import com.example.ragdemo.model.dto.MinioUploadResult;
import com.example.ragdemo.service.MinioService;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@Service
public class MinioServiceImpl implements MinioService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public MinioServiceImpl(MinioClient minioClient, MinioProperties minioProperties) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
    }

    @Override
    public MinioUploadResult upload(MultipartFile file, String objectName) {
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(objectName)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());

            return new MinioUploadResult(
                    minioProperties.getBucket(),
                    objectName,
                    file.getSize(),
                    file.getContentType());
        } catch (Exception e) {
            throw new RuntimeException("MinIO upload failed: " + objectName, e);
        }
    }

    @Override
    public InputStream download(String objectName) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(objectName)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("MinIO download failed: " + objectName, e);
        }
    }

    @Override
    public void delete(String objectName) {
    }
}
