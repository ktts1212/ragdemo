package com.example.ragdemo.document.chunk;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ragdemo.config.RagProperties;

/**
 * chunk 的 embeddingText 渲染器（skill §16/§17）。
 * <p>
 * 目的：让 embedding 同时获得<b>局部内容</b>与<b>章节语义</b>，避免只 embedding 裸正文。
 * <p>
 * 输出格式：
 * <pre>
 * Document: {title}
 * Section: {p1 &gt; p2 &gt; p3}
 *
 * Content:
 * {content}
 * </pre>
 * 规则：标题为空则省略 Document 行；路径段全为空白则省略 Section 行（空段会被过滤）；
 * 两者都不存在时直接返回 content 本身。
 */
@Component
public class ChunkTextRenderer {

    private static final String PATH_SEPARATOR = " > ";

    private final RagProperties properties;

    public ChunkTextRenderer(RagProperties properties) {
        this.properties = properties;
    }

    /**
     * @param documentTitle 论文标题（可为 null）
     * @param sectionPath   章节路径（可为 null；空白段会被过滤）
     * @param content       chunk 正文
     * @return 送入 embedding 的文本
     */
    public String renderEmbeddingText(String documentTitle, List<String> sectionPath, String content) {
        String body = content == null ? "" : content;
        List<String> headerLines = new ArrayList<>();
        if (includeTitle() && documentTitle != null && !documentTitle.isBlank()) {
            headerLines.add("Document: " + documentTitle.trim());
        }
        String path = joinPath(sectionPath);
        if (!path.isEmpty()) {
            headerLines.add("Section: " + path);
        }
        if (headerLines.isEmpty()) {
            return body;
        }
        return String.join("\n", headerLines) + "\n\nContent:\n" + body;
    }

    /** 拼接章节路径，过滤空白段（例如 ["Discussion", "", "Limitations"] → "Discussion > Limitations"）。 */
    public String joinPath(List<String> sectionPath) {
        if (sectionPath == null || sectionPath.isEmpty()) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        for (String segment : sectionPath) {
            if (segment != null && !segment.isBlank()) {
                segments.add(segment.trim());
            }
        }
        return String.join(PATH_SEPARATOR, segments);
    }

    private boolean includeTitle() {
        return properties.getChunker() == null || properties.getChunker().isHeaderIncludeTitle();
    }
}
