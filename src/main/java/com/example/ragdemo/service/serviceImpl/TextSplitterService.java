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

    private static final int MAX_SEGMENT_SIZE_IN_CHARS = 800;
    private static final int MAX_OVERLAP_SIZE_IN_CHARS = 120;

    private final DocumentSplitter splitter = DocumentSplitters.recursive(
            MAX_SEGMENT_SIZE_IN_CHARS,
            MAX_OVERLAP_SIZE_IN_CHARS);

    public List<TextSegment> split(Document document) {
        return splitter.split(document);
    }
}
