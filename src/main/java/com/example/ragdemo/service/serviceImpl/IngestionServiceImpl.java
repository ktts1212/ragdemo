package com.example.ragdemo.service.serviceImpl;

import com.example.ragdemo.mapper.DocumentChunkMapper;
import com.example.ragdemo.mapper.DocumentsMapper;
import com.example.ragdemo.model.entity.KnowledgeDocument;
import com.example.ragdemo.rag.ingest.AcademicIngestionService;
import com.example.ragdemo.rag.ingest.IngestionStatusUpdater;
import com.example.ragdemo.rag.ingest.ParseMode;
import com.example.ragdemo.rag.parser.DocumentParseService;
import com.example.ragdemo.service.IngestionService;
import com.example.ragdemo.service.MinioService;
import com.example.ragdemo.model.param.DocumentChunkInsertParam;
import com.example.ragdemo.util.VectorUtils;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class IngestionServiceImpl implements IngestionService {

    private final DocumentsMapper documentsMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final MinioService minioService;
    private final DocumentParseService documentParserService;
    private final EmbeddingModel embeddingModel;
    private final TextSplitterService textSplitterService;
    private final IngestionStatusUpdater statusUpdater;
    private final AcademicIngestionService academicIngestionService;

    public IngestionServiceImpl(DocumentsMapper documentsMapper,
            DocumentChunkMapper documentChunkMapper,
            MinioService minioService,
            DocumentParseService documentParserService,
            EmbeddingModel embeddingModel,
            TextSplitterService textSplitterService,
            IngestionStatusUpdater statusUpdater,
            AcademicIngestionService academicIngestionService) {
        this.documentsMapper = documentsMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.minioService = minioService;
        this.documentParserService = documentParserService;
        this.embeddingModel = embeddingModel;
        this.textSplitterService = textSplitterService;
        this.statusUpdater = statusUpdater;
        this.academicIngestionService = academicIngestionService;
    }

    @Override
    public void ingest(Long documentId, Long taskId) {
        /* 解析文档业务步骤 */
        // 1. 更新IngestionTask表任务状态
        statusUpdater.markTaskRunning(taskId);
        // 2. 获取文档原始信息
        KnowledgeDocument knowledgeDocument = documentsMapper.selectById(documentId);
        if (knowledgeDocument == null) {
            statusUpdater.markTaskFailed(taskId, "文档不存在");
            return;
        }
        // 3.检查文档是否已处理过
        if ("indexed".equals(knowledgeDocument.getStatus())) {
            statusUpdater.markTaskSuccess(taskId);
            return;
        }

        // 4. 分流：ACADEMIC → 学术链路；否则 GENERIC（skill §9）
        ParseMode parseMode = ParseMode.from(knowledgeDocument.getParseMode());
        log.info("入库分流: documentId={}, parseMode={}", documentId, parseMode);
        try {
            if (parseMode == ParseMode.ACADEMIC) {
                // 学术链路自己管理 parsing/chunking/embedding/indexed 与任务状态
                academicIngestionService.ingest(knowledgeDocument, taskId);
            } else {
                ingestGeneric(knowledgeDocument, taskId);
            }
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            statusUpdater.updateDocumentStatus(documentId, "failed", 100, message);
            statusUpdater.markTaskFailed(taskId, message);
        }
    }

    /** GENERIC 链路（原有逻辑，行为不变）。 */
    private void ingestGeneric(KnowledgeDocument knowledgeDocument, Long taskId) {
        Long documentId = knowledgeDocument.getId();
        // 5. 更新文档状态
        updateDocumentStatus(documentId, "parsing", 20, null);

        // 5. 从 MinIO 下载原始文件
        try (InputStream inputStream = minioService.download(knowledgeDocument.getStoragePath())) {

            // 6. 解析文档
            Document document = documentParserService.parse(
                    inputStream,
                    knowledgeDocument.getFileName());

            if (document == null || document.text() == null || document.text().isBlank()) {
                updateDocumentStatus(documentId, "failed", 100, "文档未提取到有效文本");
                markTaskFailed(taskId, "文档未提取到有效文本");
                return;
            }

            // 7. 更新状态：分块中
            updateDocumentStatus(documentId, "chunking", 45, null);

            // 8. 文档分块
            List<TextSegment> segments = textSplitterService.split(document);

            if (segments == null || segments.isEmpty()) {
                updateDocumentStatus(documentId, "failed", 100, "文档分块结果为空");
                markTaskFailed(taskId, "文档分块结果为空");
                return;
            }

            // 9. 更新 chunk 数量
            updateChunkCount(documentId, segments.size());

            // 10. 更新状态：向量化中
            updateDocumentStatus(documentId, "embedding", 70, null);

            // 11. 生成 embedding
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

            // 12. 保存 chunk + embedding，并标记 indexed
            saveChunksAndMarkIndexed(
                    knowledgeDocument,
                    taskId,
                    segments,
                    embeddings);

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            updateDocumentStatus(documentId, "failed", 100, msg);
            markTaskFailed(taskId, msg);
        }
    }

    // ==================== 状态更新（统一委托给 IngestionStatusUpdater，两条链路共用一份实现） ====================

    private void markTaskRunning(Long taskId) {
        statusUpdater.markTaskRunning(taskId);
    }

    private void markTaskSuccess(Long taskId) {
        statusUpdater.markTaskSuccess(taskId);
    }

    private void markTaskFailed(Long taskId, String errorMessage) {
        statusUpdater.markTaskFailed(taskId, errorMessage);
    }

    private void updateDocumentStatus(Long documentId, String status, int progress, String errorMessage) {
        statusUpdater.updateDocumentStatus(documentId, status, progress, errorMessage);
    }

    private void updateChunkCount(Long documentId, int count) {
        statusUpdater.updateChunkCount(documentId, count);
    }

    private void saveChunksAndMarkIndexed(KnowledgeDocument doc, Long taskId,
            List<TextSegment> segments, List<Embedding> embeddings) {
        // 1. 先清空旧 chunk（支持重试幂等）
        documentChunkMapper.deleteByDocumentId(doc.getId());

        // 2. 逐条插入 chunk + embedding
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            Embedding embedding = embeddings.get(i);

            DocumentChunkInsertParam param = new DocumentChunkInsertParam();
            param.setDocumentId(doc.getId());
            param.setKnowledgeBaseId(doc.getKnowledgeBaseId());
            param.setChunkIndex(i);
            param.setContent(segment.text());
            param.setEmbedding(VectorUtils.toPgVector(embedding.vector()));

            documentChunkMapper.insertChunkWithEmbedding(param);
        }

        // 3. 更新文档状态为 indexed + 标记任务成功
        updateDocumentStatus(doc.getId(), "indexed", 100, null);
        markTaskSuccess(taskId);

        log.info("chunk 入库完成: documentId={}, chunkCount={}", doc.getId(), segments.size());
    }
}
