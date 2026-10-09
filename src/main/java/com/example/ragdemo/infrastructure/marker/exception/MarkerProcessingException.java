package com.example.ragdemo.infrastructure.marker.exception;

/**
 * Marker 解析失败异常（文档/内容问题，或有明确 errorCode）。
 * <p>
 * 通常不可重试（同一份 PDF 重试结果相同），保留 errorCode 便于定位。
 */
public class MarkerProcessingException extends MarkerException {

    public MarkerProcessingException(String message, Integer httpStatus, String errorCode,
            boolean retryable, Throwable cause) {
        super(message, httpStatus, errorCode, retryable, cause);
    }

    public MarkerProcessingException(String message, Integer httpStatus, String errorCode) {
        super(message, httpStatus, errorCode, false, null);
    }
}
