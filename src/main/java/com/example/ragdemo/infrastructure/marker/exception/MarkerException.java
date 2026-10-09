package com.example.ragdemo.infrastructure.marker.exception;

import lombok.Getter;

/**
 * Marker 调用异常基类。
 * <p>
 * 携带三个决策/诊断信息：
 * <ul>
 *   <li>{@code httpStatus} — 触发异常时的 HTTP 状态码（网络层异常为 null）</li>
 *   <li>{@code errorCode} — Marker 返回的 errorCode（如 MARKER_PARSE_FAILED）</li>
 *   <li>{@code retryable} — 是否为可重试的瞬时故障</li>
 * </ul>
 */
@Getter
public class MarkerException extends RuntimeException {

    private final Integer httpStatus;
    private final String errorCode;
    private final boolean retryable;

    public MarkerException(String message) {
        this(message, null, null, false, null);
    }

    public MarkerException(String message, boolean retryable, Throwable cause) {
        this(message, null, null, retryable, cause);
    }

    public MarkerException(String message, Integer httpStatus, String errorCode,
            boolean retryable, Throwable cause) {
        super(buildMessage(message, httpStatus, errorCode), cause);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    private static String buildMessage(String message, Integer httpStatus, String errorCode) {
        StringBuilder sb = new StringBuilder(message == null ? "Marker 调用失败" : message);
        if (httpStatus != null) {
            sb.append(" [httpStatus=").append(httpStatus).append(']');
        }
        if (errorCode != null && !errorCode.isBlank()) {
            sb.append(" [errorCode=").append(errorCode).append(']');
        }
        return sb.toString();
    }
}
