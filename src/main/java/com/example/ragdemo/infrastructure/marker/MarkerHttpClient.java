package com.example.ragdemo.infrastructure.marker;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.example.ragdemo.infrastructure.marker.exception.MarkerException;
import com.example.ragdemo.infrastructure.marker.exception.MarkerInvalidResponseException;
import com.example.ragdemo.infrastructure.marker.exception.MarkerProcessingException;
import com.example.ragdemo.infrastructure.marker.exception.MarkerTimeoutException;
import com.example.ragdemo.infrastructure.marker.exception.MarkerUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Marker API HTTP 客户端实现。
 * <p>
 * 职责边界（严格遵循 Marker 重构 skill §4"HTTP Client != Parser"）：
 * <ul>
 *   <li>只负责 HTTP：multipart 上传 PDF → 接收 Raw Marker JSON</li>
 *   <li>显式 HTTP 状态分类，异常携带 errorCode / retryable</li>
 *   <li>不解析 Document Tree、不做结构映射、不落库</li>
 *   <li>不自动重试：Marker 单篇解析昂贵（数十秒~数分钟），重试决策交给上层</li>
 * </ul>
 * 线程安全：Spring 单例复用，连接由 RestClient 管理。
 */
@Slf4j
@Component
public class MarkerHttpClient implements MarkerClient {

    private static final String PARSE_PATH = "/api/v1/parse";
    private static final String HEALTH_PATH = "/health";
    /** Marker multipart 表单字段名 */
    private static final String FILE_FIELD = "file";

    private final MarkerProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * 生产用构造器。
     * <p>
     * 必须显式标注 {@code @Autowired}：本类还有第二个（包私有）测试专用构造器，
     * 存在多个构造器时 Spring 无法自动选择，会退化为"要求无参构造器"而启动失败。
     */
    @Autowired
    public MarkerHttpClient(MarkerProperties properties) {
        this(properties, buildRestClient(properties), new ObjectMapper());
    }

    /**
     * 测试专用构造：注入已绑定 MockRestServiceServer 的 RestClient。
     */
    MarkerHttpClient(MarkerProperties properties, RestClient restClient, ObjectMapper objectMapper) {
        validateConfig(properties);
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    private static RestClient buildRestClient(MarkerProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    private void validateConfig(MarkerProperties properties) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("rag.marker.base-url 未配置");
        }
    }

    // ==================== MarkerClient ====================

    @Override
    public MarkerResponse parse(byte[] pdfBytes, String fileName) {
        validateInput(pdfBytes, fileName);

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add(FILE_FIELD, new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                // 让 multipart part 带上 filename，Marker 依赖文件类型
                return fileName;
            }
        });

        long start = System.currentTimeMillis();
        ResponseEntity<String> response;
        try {
            response = restClient.post()
                    .uri(PARSE_PATH)
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

        log.info("Marker 调用完成: operation=parse, fileName={}, pdfSize={} bytes, "
                        + "httpStatus={}, durationMs={}ms",
                fileName, pdfBytes.length, httpStatus, durationMs);

        return classifyParseResponse(httpStatus, body);
    }

    @Override
    public MarkerHealth health() {
        try {
            ResponseEntity<String> response = restClient.get()
                    .uri(HEALTH_PATH)
                    .exchange((request, clientResponse) -> {
                        String body = readBody(clientResponse);
                        return ResponseEntity.status(clientResponse.getStatusCode()).body(body);
                    });
            int httpStatus = response.getStatusCode().value();
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new MarkerUnavailableException(
                        "Marker health 检查失败: HTTP " + httpStatus, httpStatus, null);
            }
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                throw new MarkerInvalidResponseException("Marker health 响应为空", httpStatus);
            }
            MarkerHealth health = objectMapper.readValue(body, MarkerHealth.class);
            log.debug("Marker health: status={}", health.status());
            return health;
        } catch (MarkerException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw mapTransportException(e);
        } catch (Exception e) {
            throw new MarkerUnavailableException("Marker health 检查失败: " + e.getMessage(), null, e);
        }
    }

    @Override
    public boolean isAlive() {
        try {
            MarkerHealth health = health();
            return health != null && "ok".equalsIgnoreCase(health.status());
        } catch (Exception e) {
            log.warn("Marker isAlive 异常: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 内部实现 ====================

    /**
     * HTTP 状态与业务错误分类。
     */
    private MarkerResponse classifyParseResponse(int httpStatus, String body) {
        if (httpStatus >= 500) {
            throw new MarkerUnavailableException(
                    "Marker 服务端错误 (" + httpStatus + "): " + snippet(body), httpStatus, null);
        }
        if (httpStatus >= 400) {
            throw new MarkerProcessingException(
                    "Marker 拒绝请求 (" + httpStatus + "): " + snippet(body), httpStatus, null);
        }

        if (body == null || body.isBlank()) {
            throw new MarkerInvalidResponseException("Marker 返回 2xx 但响应体为空", httpStatus);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new MarkerInvalidResponseException("Marker 响应不是合法 JSON: " + e.getMessage(),
                    httpStatus, e);
        }

        boolean success = root.path("success").asBoolean(false);
        String errorCode = root.path("errorCode").isMissingNode() ? null : root.path("errorCode").asText();
        String message = root.path("message").isMissingNode() ? null : root.path("message").asText();

        if (!success) {
            // Marker 明确返回业务失败
            throw new MarkerProcessingException(
                    "Marker 解析失败: " + (message != null ? message : "未知原因"),
                    httpStatus, errorCode);
        }

        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            throw new MarkerInvalidResponseException("Marker 返回 success=true 但缺少 data", httpStatus);
        }

        String filename = root.path("filename").isMissingNode() ? null : root.path("filename").asText();
        Double elapsedSeconds = root.path("elapsedSeconds").isNumber()
                ? root.path("elapsedSeconds").asDouble()
                : null;

        return new MarkerResponse(true, filename, elapsedSeconds, data, errorCode, message);
    }

    private String readBody(ClientHttpResponse clientResponse) {
        try {
            return new String(clientResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MarkerUnavailableException("读取 Marker 响应失败: " + e.getMessage(), null, e);
        }
    }

    private MarkerException mapTransportException(ResourceAccessException e) {
        if (e.getCause() instanceof SocketTimeoutException) {
            return new MarkerTimeoutException("Marker 请求超时: " + e.getMessage(), e);
        }
        return new MarkerUnavailableException("Marker 服务不可达: " + e.getMessage(), null, e);
    }

    private void validateInput(byte[] pdfBytes, String fileName) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF 内容不能为空");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
    }

    private String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "无响应体";
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
