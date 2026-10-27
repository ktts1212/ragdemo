package com.example.ragdemo.infrastructure.grobid;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.example.ragdemo.infrastructure.grobid.exception.GrobidException;
import com.example.ragdemo.infrastructure.grobid.exception.GrobidInvalidResponseException;
import com.example.ragdemo.infrastructure.grobid.exception.GrobidProcessingException;
import com.example.ragdemo.infrastructure.grobid.exception.GrobidTimeoutException;
import com.example.ragdemo.infrastructure.grobid.exception.GrobidUnavailableException;

import lombok.extern.slf4j.Slf4j;

/**
 * GROBID 客户端 HTTP 实现。
 * <p>
 * 职责边界（严格遵循 grobid-client-skill）：
 * <ul>
 *   <li>只负责 HTTP 通信：multipart 上传 PDF → 接收 TEI XML</li>
 *   <li>显式 HTTP 状态分类，区分可重试/不可重试异常</li>
 *   <li>有限重试（默认 3 次，间隔 5s），重试耗尽后抛出异常由上层 Kafka 重试兜底</li>
 *   <li>提供 isAlive / health 诊断接口</li>
 * </ul>
 * 不解析 TEI、不存储、不更新数据库、不产生消息。
 * <p>
 * 线程安全：作为 Spring 单例复用，连接池由 RestClient 管理。
 *
 * @deprecated legacy：主链路已切换到 Marker 解析器，本类仅保留作 benchmark。
 */
@Deprecated(since = "marker-refactor")
@Slf4j
@Component
public class GrobidHttpClient implements GrobidClient {

    private static final String FULLTEXT_PATH = "/api/processFulltextDocument";
    private static final String ISALIVE_PATH = "/api/isalive";
    private static final String HEALTH_PATH = "/api/health";

    /** 通常不可重试的 GROBID 错误码 */
    private static final java.util.Set<String> NON_RETRYABLE_ERROR_CODES = java.util.Set.of(
            "BAD_INPUT_DATA", "NO_BLOCKS", "TOO_MANY_BLOCKS", "TOO_MANY_TOKENS",
            "PARSING_ERROR", "PDFALTO_CONVERSION_FAILURE");

    private final GrobidProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * 生产用构造器。
     * <p>
     * 必须显式标注 {@code @Autowired}：本类还有第二个（包私有）测试专用构造器，
     * 存在多个构造器时 Spring 无法自动选择，会退化为"要求无参构造器"而启动失败。
     */
    @Autowired
    public GrobidHttpClient(GrobidProperties properties) {
        this(properties, buildRestClient(properties), new ObjectMapper());
    }

    /**
     * 测试专用构造：注入已绑定 MockRestServiceServer 的 RestClient。
     */
    GrobidHttpClient(GrobidProperties properties, RestClient restClient, ObjectMapper objectMapper) {
        validateConfig(properties);
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    private static RestClient buildRestClient(GrobidProperties properties) {
        //1.连接池管理器
        PoolingHttpClientConnectionManager connectionManager=new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(10);//连接池最多有几个来连接
        connectionManager.setDefaultMaxPerRoute(5);//每个路由的最大连接数量
        
        //1.2连接配置
        ConnectionConfig connectionConfig=ConnectionConfig.custom()
        .setConnectTimeout(Timeout.ofMilliseconds(properties.getConnectTimeout().toMillis()))
        .build();
        //2.请求配置
        RequestConfig requestConfig=RequestConfig.custom()
        .setResponseTimeout(Timeout.ofMilliseconds(properties.getReadTimeout().toMillis()))
        .setConnectionRequestTimeout(Timeout.ofMilliseconds(3000))  //从池中获取连接超时时间
        .build();
        //2.1连接配置挂载到管理器
        connectionManager.setDefaultConnectionConfig(connectionConfig);
        //3.httpclient
        CloseableHttpClient httpClient=HttpClients.custom()
        .setConnectionManager(connectionManager)
        .setDefaultRequestConfig(requestConfig)
        .evictExpiredConnections()
        .evictIdleConnections(Timeout.ofMilliseconds(60000))  //空闲60s清除
        .build();
        
        HttpComponentsClientHttpRequestFactory factory=new HttpComponentsClientHttpRequestFactory(httpClient);

        return RestClient.builder()
        .baseUrl(properties.getBaseUrl())
        .requestFactory(factory)
        .build();

    }

    private void validateConfig(GrobidProperties properties) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("rag.grobid.base-url 未配置");
        }
        if (properties.getMaxRetries() < 0) {
            throw new IllegalStateException("rag.grobid.max-retries 不能为负数");
        }
    }

    // ==================== GrobidClient ====================

    @Override
    public GrobidResponse processFulltext(byte[] pdfBytes, String fileName) {
        validateInput(pdfBytes, fileName);

        int maxRetries = properties.getMaxRetries();
        for (int attempt = 0; ; attempt++) {
            try {
                return doProcessFulltext(pdfBytes, fileName, attempt + 1);
            } catch (GrobidException e) {
                if (!e.isRetryable() || attempt >= maxRetries) {
                    log.error("GROBID 解析失败(重试耗尽): fileName={}, attempt={}, exceptionType={}, " +
                                    "httpStatus={}, grobidErrorCode={}, retryable={}",
                            fileName, attempt + 1, e.getClass().getSimpleName(),
                            e.getHttpStatus(), e.getGrobidErrorCode(), e.isRetryable());
                    throw e;
                }
                log.warn("GROBID 瞬时故障，准备重试: fileName={}, attempt={}, exceptionType={}, " +
                                "httpStatus={}, grobidErrorCode={}, retryable={}, 将在 {}ms 后重试",
                        fileName, attempt + 1, e.getClass().getSimpleName(),
                        e.getHttpStatus(), e.getGrobidErrorCode(), e.isRetryable(),
                        properties.getRetryBackoff().toMillis());
                sleepBackoff(properties.getRetryBackoff());
            }
        }
    }

    @Override
    public boolean isAlive() {
        try {
            ResponseEntity<String> response = restClient.get()
                    .uri(ISALIVE_PATH)
                    .exchange((request, clientResponse) -> {
                        String body = readBody(clientResponse);
                        return ResponseEntity.status(clientResponse.getStatusCode()).body(body);
                    });
            boolean alive = response.getStatusCode().is2xxSuccessful();
            log.debug("GROBID isAlive: httpStatus={}, alive={}", response.getStatusCode().value(), alive);
            return alive;
        } catch (Exception e) {
            log.warn("GROBID isAlive 调用异常: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public GrobidHealth health() {
        try {
            ResponseEntity<GrobidHealth> response = restClient.get()
                    .uri(HEALTH_PATH)
                    .exchange((request, clientResponse) -> {
                        String body = readBody(clientResponse);
                        GrobidHealth health = null;
                        if (clientResponse.getStatusCode().is2xxSuccessful() && !body.isBlank()) {
                            health = objectMapper.readValue(body, GrobidHealth.class);
                        }
                        return ResponseEntity.status(clientResponse.getStatusCode()).body(health);
                    });
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new GrobidUnavailableException(
                        "GROBID health 检查失败: HTTP " + response.getStatusCode().value(),
                        response.getStatusCode().value(), null);
            }
            if (response.getBody() == null) {
                throw new GrobidInvalidResponseException("GROBID health 响应无效或为空",
                        response.getStatusCode().value());
            }
            log.info("GROBID health: active={}, idle={}, max={}",
                    response.getBody().active(), response.getBody().idle(), response.getBody().max());
            return response.getBody();
        } catch (GrobidException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw mapTransportException(e);
        } catch (RuntimeException e) {
            throw new GrobidUnavailableException("GROBID health 检查失败: " + e.getMessage(),
                    null, e);
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 读取响应体为 UTF-8 字符串；IO 失败映射为 GrobidUnavailableException（可重试）。
     */
    private String readBody(ClientHttpResponse clientResponse) {
        try {
            return new String(clientResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GrobidUnavailableException("读取 GROBID 响应失败: " + e.getMessage(), null, e);
        }
    }

    private GrobidResponse doProcessFulltext(byte[] pdfBytes, String fileName, int attempt) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("input", new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });

        long start = System.currentTimeMillis();
        ResponseEntity<String> response;
        try {
            response = restClient.post()
                    .uri(FULLTEXT_PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .exchange((request, clientResponse) -> {
                        String body = readBody(clientResponse);
                        return ResponseEntity.status(clientResponse.getStatusCode()).body(body);
                    });
        } catch (ResourceAccessException e) {
            throw mapTransportException(e);
        }
        long durationMs = System.currentTimeMillis() - start;

        int httpStatus = response.getStatusCode().value();
        String body = response.getBody();

        log.info("GROBID 调用完成: operation=processFulltext, fileName={}, pdfSize={} bytes, " +
                        "httpStatus={}, durationMs={}ms, attempt={}",
                fileName, pdfBytes.length, httpStatus, durationMs, attempt);

        return classifyFulltextResponse(httpStatus, body, durationMs);
    }

    /**
     * HTTP 状态分类：映射到可重试/不可重试的领域异常。
     */
    private GrobidResponse classifyFulltextResponse(int httpStatus, String body, long durationMs) {
        if (httpStatus == 200) {
            if (body == null || body.isBlank()) {
                throw new GrobidInvalidResponseException("GROBID 返回 200 但响应体为空", httpStatus);
            }
            return new GrobidResponse(body, durationMs);
        }

        if (httpStatus == 204) {
            // 处理未产生可用内容，不可盲目重试
            throw new GrobidInvalidResponseException("GROBID 处理完成但未产生内容 (204)", httpStatus);
        }

        if (httpStatus == 400) {
            throw new GrobidProcessingException("GROBID 拒绝请求: 无效输入 (400)",
                    httpStatus, null, false);
        }

        if (httpStatus == 503) {
            // 引擎/解析池耗尽或临时不可用，可重试
            throw new GrobidUnavailableException("GROBID 引擎池不可用 (503): " + snippet(body),
                    httpStatus, null);
        }

        if (httpStatus == 500) {
            return classifyInternalError(body, durationMs);
        }

        if (httpStatus >= 500) {
            throw new GrobidUnavailableException("GROBID 服务端错误 (" + httpStatus + "): " + snippet(body),
                    httpStatus, null);
        }

        throw new GrobidProcessingException("GROBID 未预期的 HTTP 状态 (" + httpStatus + "): " + snippet(body),
                httpStatus, null, false);
    }

    /**
     * 500 响应：检查 body 中的 GROBID 错误码，区分可重试/不可重试。
     */
    private GrobidResponse classifyInternalError(String body, long durationMs) {
        String errorCode = extractGrobidErrorCode(body);
        int httpStatus = 500;

        if (errorCode == null) {
            throw new GrobidProcessingException("GROBID 内部错误 (500): " + snippet(body),
                    httpStatus, null, false);
        }

        boolean retryable = !NON_RETRYABLE_ERROR_CODES.contains(errorCode);

        if ("TIMEOUT".equals(errorCode)) {
            throw new GrobidTimeoutException("GROBID 处理超时 (TIMEOUT)", httpStatus, null);
        }
        throw new GrobidProcessingException("GROBID 处理失败: " + errorCode,
                httpStatus, errorCode, retryable);
    }

    /**
     * 从 GROBID 500 响应体中提取错误码（如 BAD_INPUT_DATA / TIMEOUT / GENERAL）。
     * 兼容 JSON（{"error":...}）与纯文本两种形式。
     */
    private String extractGrobidErrorCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        for (String code : NON_RETRYABLE_ERROR_CODES) {
            if (body.contains(code)) {
                return code;
            }
        }
        if (body.contains("TIMEOUT")) {
            return "TIMEOUT";
        }
        if (body.contains("TAGGING_ERROR")) {
            return "TAGGING_ERROR";
        }
        if (body.contains("GENERAL")) {
            return "GENERAL";
        }
        return null;
    }

    /**
     * 网络层异常映射：读超时 → Timeout；连接失败/不可达 → Unavailable。
     */
    private GrobidException mapTransportException(ResourceAccessException e) {
        if (e.getCause() instanceof SocketTimeoutException) {
            return new GrobidTimeoutException("GROBID 请求超时: " + e.getMessage(), null, e);
        }
        return new GrobidUnavailableException("GROBID 服务不可达: " + e.getMessage(), null, e);
    }

    private void validateInput(byte[] pdfBytes, String fileName) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF 内容不能为空");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (!fileName.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("仅支持 PDF 文件: " + fileName);
        }
    }

    private void sleepBackoff(Duration backoff) {
        try {
            TimeUnit.MILLISECONDS.sleep(backoff.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new GrobidUnavailableException("重试等待被中断", null, ie);
        }
    }

    private String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "无响应体";
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
