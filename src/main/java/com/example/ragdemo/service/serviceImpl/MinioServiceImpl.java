package com.example.ragdemo.service.serviceImpl;

import com.example.ragdemo.config.MinioProperties;
import com.example.ragdemo.model.dto.MinioUploadResult;
import io.minio.MinioClient;
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
        return null;
    }

    @Override
    public InputStream download(String objectName) {
        return null;
    }

    @Override
    public void delete(String objectName) {
    }
}
