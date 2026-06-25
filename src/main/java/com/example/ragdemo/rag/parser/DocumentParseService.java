package com.example.ragdemo.rag.parser;

import java.io.InputStream;

import org.springframework.stereotype.Service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;

@Service
public class DocumentParseService {
    private final DocumentParserFactory parserFactory;

    public DocumentParseService(DocumentParserFactory parserFactory) {
        this.parserFactory = parserFactory;
    }

    public Document parse(InputStream inputStream, String fileName) {
        DocumentParser parser = parserFactory.getParser(fileName);
        return parser.parse(inputStream);
    }
}
