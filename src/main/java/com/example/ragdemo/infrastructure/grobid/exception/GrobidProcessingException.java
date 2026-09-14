package com.example.ragdemo.infrastructure.grobid.exception;

/**
 * GROBID 处理失败异常。
 * <p>
 * 用于 GROBID 收到了文档但无法处理（文档/内容本身的问题）：
 * BAD_INPUT_DATA / NO_BLOCKS / TOO_MANY_BLOCKS / TOO_MANY_TOKENS / PARSING_ERROR / 400 等。
 * 通常不可重试；部分错误码（TIMEOUT/GENERAL）可重试，由 {@link #isRetryable()} 标识。
 */
public class GrobidProcessingException extends GrobidException {

    public GrobidProcessingException(String message, Integer httpStatus, String grobidErrorCode,
            boolean retryable, Throwable cause) {
        super(message, httpStatus, grobidErrorCode, retryable, cause);
    }

    public GrobidProcessingException(String message, Integer httpStatus, String grobidErrorCode,
            boolean retryable) {
        super(message, httpStatus, grobidErrorCode, retryable, null);
    }
}
