package com.example.ragdemo.infrastructure.grobid.exception;

/**
 * GROBID 无效响应异常。
 * <p>
 * 用于：HTTP 200 但响应体为 null/空白、响应体不是合理的 XML、响应编码无效、HTTP 204（无可用内容）。
 * 除非存在瞬时损坏的证据，否则不可重试。
 */
public class GrobidInvalidResponseException extends GrobidException {

    public GrobidInvalidResponseException(String message, Integer httpStatus) {
        super(message, httpStatus, null, false, null);
    }
}
