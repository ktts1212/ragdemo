package com.example.ragdemo.infrastructure.marker;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Marker API 客户端配置，绑定 application.yml 中 rag.marker.* 。
 * <p>
 * 本项目通过 SSH Tunnel 访问远程 Marker：Windows 127.0.0.1:18001 → Linux marker-api。
 * 禁止在 Java 中硬编码服务器公网地址。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.marker")
public class MarkerProperties {

    /** Marker API 地址，如 http://127.0.0.1:18001 */
    private String baseUrl;

    /** 连接超时，默认 5s */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 读取超时：Marker 解析论文可能数十秒到数分钟，默认 900s */
    private Duration readTimeout = Duration.ofSeconds(900);
}
