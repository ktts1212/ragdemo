package com.example.ragdemo.document.parser.marker.exception;

/**
 * Marker Document 结构解析异常。
 * <p>
 * 仅在硬失败时抛出（skill §30）：
 * <ul>
 *   <li>顶层不是 Document</li>
 *   <li>children 为空</li>
 *   <li>JSON 无法映射 / 关键结构损坏</li>
 * </ul>
 * 未知 block type、没有 Abstract/References/Figure、hierarchy 不完整等不应硬失败。
 */
public class MarkerDocumentParseException extends RuntimeException {

    public MarkerDocumentParseException(String message) {
        super(message);
    }

    public MarkerDocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
