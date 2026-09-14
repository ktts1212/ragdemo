package com.example.ragdemo.infrastructure.grobid.exception;

import lombok.Getter;

/**
 * GROBID 调用异常基类。
 * <p>
 * 所有 GROBID 相关异常都继承此类，并携带：
 * <ul>
 *   <li>{@code httpStatus} — 触发异常时的 HTTP 状态码（网络层异常为 null）</li>
 *   <li>{@code grobidErrorCode} — GROBID 返回的错误码（如 BAD_INPUT_DATA / TIMEOUT），无则为 null</li>
 *   <li>{@code retryable} — 是否为可重试的瞬时故障</li>
 * </ul>
 */
@Getter
public class GrobidException extends RuntimeException {

    private final Integer httpStatus;
    private final String grobidErrorCode;
    private final boolean retryable;

    public GrobidException(String message) {
        this(message, null, null, false, null);
    }

    public GrobidException(String message, boolean retryable, Throwable cause) {
        this(message, null, null, retryable, cause);
    }

    public GrobidException(String message, Integer httpStatus, String grobidErrorCode,
            boolean retryable, Throwable cause) {
        super(buildMessage(message, httpStatus, grobidErrorCode), cause);
        this.httpStatus = httpStatus;
        this.grobidErrorCode = grobidErrorCode;
        this.retryable = retryable;
    }

    private static String buildMessage(String message, Integer httpStatus, String grobidErrorCode) {
        StringBuilder sb = new StringBuilder(message == null ? "GROBID 调用失败" : message);
        if (httpStatus != null) {
            sb.append(" [httpStatus=").append(httpStatus).append(']');
        }
        if (grobidErrorCode != null && !grobidErrorCode.isBlank()) {
            sb.append(" [grobidErrorCode=").append(grobidErrorCode).append(']');
        }
        return sb.toString();
    }
}
