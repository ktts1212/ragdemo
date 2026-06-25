package com.example.ragdemo.rag.parser;

import org.springframework.stereotype.Component;

import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;

@Component
public class DocumentParserFactory {
    public DocumentParser getParser(String fileName) {
        String ext = getExtension(fileName);

        return switch (ext) {
            case "txt", "html", "htm", "md", "markdown" ->
                new TextDocumentParser();

            case "pdf" ->
                new ApachePdfBoxDocumentParser();

            case "doc", "docx", "ppt", "pptx", "xls", "xlsx" ->
                new ApachePoiDocumentParser();

            default ->
                throw new UnsupportedOperationException("Unsupported file type: " + ext);

            // V2 可以改成：
            // default -> new ApacheTikaDocumentParser();
        };
    }

    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }
}
