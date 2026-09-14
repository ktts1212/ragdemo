package com.example.ragdemo.infrastructure.grobid.exception;

/**
 * GROBID 超时异常。
 * <p>
 * 用于：连接超时、读取超时、GROBID 处理失败返回 TIMEOUT 错误码。
 * 属于瞬时故障，在 retry 策略层可重试。
 */
public class GrobidTimeoutException extends GrobidException {

    public GrobidTimeoutException(String message) {
        super(message, null, "TIMEOUT", true, null);
    }

    public GrobidTimeoutException(String message, Integer httpStatus, Throwable cause) {
        super(message, httpStatus, "TIMEOUT", true, cause);
    }
}
