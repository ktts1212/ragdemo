package com.example.ragdemo.rag.ingest;

import org.springframework.stereotype.Component;

import com.example.ragdemo.config.RagProperties;

/**
 * 入库解析模式三级决策器（skill §9）。
 * <p>
 * <b>优先级：上传参数 &gt; 配置默认 &gt; MIME/扩展名自动判断</b>
 * <pre>
 * resolve(requested=ACADEMIC, ...)          → ACADEMIC        （① 显式）
 * resolve(requested=null,   配置=GENERIC)    → GENERIC         （② 配置默认）
 * resolve(requested=null,   配置=AUTO, .pdf)  → ACADEMIC        （③ 自动）
 * resolve(requested=null,   配置=AUTO, .md)   → GENERIC
 * </pre>
 * 说明：显式传入 {@code AUTO} 与传 null 语义相同（都视为"未指定"），继续下一级。
 */
@Component
public class ParseModeResolver {

    private static final String PDF_EXTENSION = ".pdf";
    private static final String PDF_MIME = "application/pdf";

    private final RagProperties ragProperties;

    public ParseModeResolver(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * @param requested   上传时显式指定的模式（可为 null）
     * @param fileName    原始文件名（用于扩展名判断）
     * @param contentType 上传的 content-type（可为 null）
     * @return 最终确定的模式（<b>不会</b>返回 {@link ParseMode#AUTO}）
     */
    public ParseMode resolve(ParseMode requested, String fileName, String contentType) {
        // ① 上传参数（显式）
        if (requested != null && requested != ParseMode.AUTO) {
            return requested;
        }
        // ② 配置默认
        ParseMode configured = ragProperties.getParser() == null
                ? null
                : ragProperties.getParser().getParseMode();
        if (configured != null && configured != ParseMode.AUTO) {
            return configured;
        }
        // ③ 自动判断
        return autoDetect(fileName, contentType);
    }

    /** AUTO：PDF（扩展名或 MIME）→ ACADEMIC；其余 → GENERIC。 */
    public ParseMode autoDetect(String fileName, String contentType) {
        if (isPdf(fileName, contentType)) {
            return ParseMode.ACADEMIC;
        }
        return ParseMode.GENERIC;
    }

    private boolean isPdf(String fileName, String contentType) {
        if (fileName != null && fileName.toLowerCase().endsWith(PDF_EXTENSION)) {
            return true;
        }
        return contentType != null && contentType.toLowerCase().contains(PDF_MIME);
    }
}
