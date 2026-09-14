package com.example.ragdemo.infrastructure.grobid;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * GROBID 客户端配置，绑定 application.yml 中 rag.grobid.* 配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.grobid")
public class GrobidProperties {

    /** GROBID 服务地址，如 http://localhost:8070 */
    private String baseUrl;

    /** 连接超时，默认 3s */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** 读取超时，复杂论文解析可能很慢，默认 120s */
    private Duration readTimeout = Duration.ofSeconds(120);

    /** 瞬时故障最大重试次数，默认 3 */
    private int maxRetries = 3;

    /** 重试间隔，默认 5s */
    private Duration retryBackoff = Duration.ofSeconds(5);
}
