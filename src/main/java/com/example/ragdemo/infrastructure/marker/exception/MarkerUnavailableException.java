package com.example.ragdemo.infrastructure.marker.exception;

/**
 * Marker 服务不可用异常（连接被拒 / 网络不可达 / 5xx）。
 * <p>
 * 可重试，重试耗尽后由上层决定处理方式。
 */
public class MarkerUnavailableException extends MarkerException {

    public MarkerUnavailableException(String message) {
        super(message, null, null, true, null);
    }

    public MarkerUnavailableException(String message, Integer httpStatus, Throwable cause) {
        super(message, httpStatus, null, true, cause);
    }
}
