package com.example.ragdemo.infrastructure.grobid;

/**
 * GROBID processFulltextDocument 成功响应。
 * <p>
 * 核心数据仍是 TEI XML 字符串，本层不做任何 XML 解析。
 *
 * @param teiXml    TEI XML 原文（非 null）
 * @param durationMs 本次解析耗时（毫秒）
 */
public record GrobidResponse(
        String teiXml,
        long durationMs
) {
}
