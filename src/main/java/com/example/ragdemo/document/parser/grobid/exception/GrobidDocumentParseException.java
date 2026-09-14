package com.example.ragdemo.document.parser.grobid.exception;

/**
 * TEI XML 解析异常。
 * <p>
 * 仅在以下情况抛出：
 * <ul>
 *   <li>TEI 为 null 或空白</li>
 *   <li>XML 不是 well-formed</li>
 *   <li>根节点不是可识别的 TEI</li>
 *   <li>XML 解析器初始化失败</li>
 * </ul>
 * <p>
 * title / DOI / abstract / reference / pageRange 等可选字段缺失时不得抛出本异常。
 */
public class GrobidDocumentParseException extends RuntimeException {

    public GrobidDocumentParseException(String message) {
        super(message);
    }

    public GrobidDocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
