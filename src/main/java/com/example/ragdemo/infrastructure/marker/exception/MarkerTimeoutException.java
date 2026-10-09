package com.example.ragdemo.infrastructure.marker.exception;

/**
 * Marker 超时异常（连接超时 / 读取超时）。
 * <p>
 * Marker 解析一篇论文可能需要数十秒到数分钟，读取超时属于瞬时故障，可重试。
 */
public class MarkerTimeoutException extends MarkerException {

    public MarkerTimeoutException(String message) {
        super(message, null, null, true, null);
    }

    public MarkerTimeoutException(String message, Throwable cause) {
        super(message, null, null, true, cause);
    }
}
