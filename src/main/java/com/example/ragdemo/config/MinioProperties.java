package com.example.ragdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Component
@ConfigurationProperties(prefix = "minio")
@Data
public class MinioProperties {
    String endpoint;
    String accessKey;
    String secretKey;
    String bucket;
}