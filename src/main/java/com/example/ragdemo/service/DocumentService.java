package com.example.ragdemo.service;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.model.entity.DocumentChunk;
import com.example.ragdemo.repository.DocumentChunkRepository;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class DocumentService {

    private final OpenAiClient openAiClient;
    private final DocumentChunkRepository documentChunkRepository;
    private final RagProperties ragProperties;

    public DocumentService(OpenAiClient openAiClient,
                           DocumentChunkRepository documentChunkRepository,
                           RagProperties ragProperties) {
        this.openAiClient = openAiClient;
        this.documentChunkRepository = documentChunkRepository;
        this.ragProperties = ragProperties;
    }

    public int upload(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String docName = file.getOriginalFilename();
        if (docName == null || docName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        String lowerDocName = docName.toLowerCase();
        if (!lowerDocName.endsWith(".txt") && !lowerDocName.endsWith(".md")) {
            throw new IllegalArgumentException("当前仅支持 txt/md 文件");
        }

        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        if (content.isBlank()) {
            throw new IllegalArgumentException("文档内容不能为空");
        }

        // 学习项目里用“同名覆盖”：重复上传同名文件时，先删除旧 chunk，再写入新 chunk。
        documentChunkRepository.deleteByDocName(docName);

        DocumentSplitter splitter = DocumentSplitters.recursive(
                ragProperties.getChunk().getMaxChars(),
                ragProperties.getChunk().getOverlapChars()
        );
        List<TextSegment> segments = splitter.split(Document.from(content));

        for (int i = 0; i < segments.size(); i++) {
            String chunkText = segments.get(i).text();
            float[] embedding = openAiClient.embed(chunkText);

            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocName(docName);
            chunk.setChunkIndex(i);
            chunk.setContent(chunkText);
            chunk.setEmbedding(embedding);
            documentChunkRepository.save(chunk);
        }
        return segments.size();
    }
}
