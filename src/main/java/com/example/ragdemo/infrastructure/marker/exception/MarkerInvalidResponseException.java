package com.example.ragdemo.infrastructure.marker.exception;

/**
 * Marker 无效响应异常（HTTP 2xx 但 body 空 / JSON 非法 / 缺少 data）。
 * <p>
 * 不可重试，除非有证据表明是瞬时损坏。
 */
public class MarkerInvalidResponseException extends MarkerException {

    public MarkerInvalidResponseException(String message, Integer httpStatus) {
        super(message, httpStatus, null, false, null);
    }

    public MarkerInvalidResponseException(String message, Integer httpStatus, Throwable cause) {
        super(message, httpStatus, null, false, cause);
    }
}
