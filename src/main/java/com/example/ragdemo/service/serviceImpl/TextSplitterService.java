package com.example.ragdemo.service.serviceImpl;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文本切分服务：使用 LangChain4j 递归切分
 */
@Service
public class TextSplitterService {

    private final DocumentSplitter splitter;

    public TextSplitterService() {
        // TODO: maxChars 和 overlapChars 改为从 RagProperties 注入
        this.splitter = DocumentSplitters.recursive(800, 120);
    }

    /**
     * 将一段长文本切分为多个 TextSegment
     *
     * @param text 待切分的完整文本
     * @return 切分后的文本段列表
     */
    public List<TextSegment> split(String text) {
        return null;
    }
}
