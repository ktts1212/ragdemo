package com.example.ragdemo.service.serviceImpl;

import com.example.ragdemo.mapper.DocumentChunkMapper;
import com.example.ragdemo.mapper.DocumentsMapper;
import com.example.ragdemo.mapper.IngestionTaskMapper;
import com.example.ragdemo.model.entity.KnowledgeDocument;
import com.example.ragdemo.model.entity.IngestionTask;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IngestionServiceImpl implements IngestionService {

    private final DocumentsMapper documentsMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final IngestionTaskMapper ingestionTaskMapper;
    private final MinioService minioService;
    private final DocumentParserService documentParserService;
    private final EmbeddingModel embeddingModel;
    private final TextSplitterService textSplitterService;

    public IngestionServiceImpl(DocumentsMapper documentsMapper,
                                DocumentChunkMapper documentChunkMapper,
                                IngestionTaskMapper ingestionTaskMapper,
                                MinioService minioService,
                                DocumentParserService documentParserService,
                                EmbeddingModel embeddingModel,
                                TextSplitterService textSplitterService) {
        this.documentsMapper = documentsMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.ingestionTaskMapper = ingestionTaskMapper;
        this.minioService = minioService;
        this.documentParserService = documentParserService;
        this.embeddingModel = embeddingModel;
        this.textSplitterService = textSplitterService;
    }

    @Override
    public void ingest(Long documentId, Long taskId) {
    }

    private void markTaskRunning(Long taskId) {
    }

    private void markTaskSuccess(Long taskId) {
    }

    private void markTaskFailed(Long taskId, String errorMessage) {
    }

    private void updateDocumentStatus(Long documentId, String status, int progress, String errorMessage) {
    }

    private void updateChunkCount(Long documentId, int count) {
    }

    private void saveChunksAndMarkIndexed(KnowledgeDocument doc, Long taskId,
                                          List<TextSegment> segments, List<Embedding> embeddings) {
    }
}
