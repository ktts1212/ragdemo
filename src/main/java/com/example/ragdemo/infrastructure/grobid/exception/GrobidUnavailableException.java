package com.example.ragdemo.infrastructure.grobid.exception;

/**
 * GROBID 不可用异常。
 * <p>
 * 用于：连接被拒、DNS/网络不可达、HTTP 503（引擎/解析池耗尽或临时不可用）。
 * 可重试，重试耗尽后抛出。
 */
public class GrobidUnavailableException extends GrobidException {

    public GrobidUnavailableException(String message) {
        super(message, null, null, true, null);
    }

    public GrobidUnavailableException(String message, Integer httpStatus, Throwable cause) {
        super(message, httpStatus, null, true, cause);
    }
}
