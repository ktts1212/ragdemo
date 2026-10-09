package com.example.ragdemo.rag.ingest;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ragdemo.mapper.DocumentChunkMapper;
import com.example.ragdemo.model.param.DocumentChunkInsertParam;

import lombok.extern.slf4j.Slf4j;

/**
 * chunk 的事务化替换写入（skill §14）。
 * <p>
 * 严格顺序（embedding 必须已在事务<b>外</b>算好）：
 * <pre>
 * 1. deleteByDocumentId   （幂等：支持重试/重跑）
 * 2. 批量 insert
 * 3. updateChunkCount
 * 4. status = indexed
 * 5. commit
 * </pre>
 * 之所以单独成 bean：{@code @Transactional} 需经代理调用才生效，
 * 若与编排逻辑同在一个类里自调用会静默失效。
 */
@Slf4j
@Service
public class ChunkPersistenceService {

    private final DocumentChunkMapper documentChunkMapper;
    private final IngestionStatusUpdater statusUpdater;

    public ChunkPersistenceService(DocumentChunkMapper documentChunkMapper,
            IngestionStatusUpdater statusUpdater) {
        this.documentChunkMapper = documentChunkMapper;
        this.statusUpdater = statusUpdater;
    }

    /**
     * 用当前 chunk 集合替换该文档的全部 chunk，并把文档置为 indexed。
     *
     * @param params 已算好 embedding 的插入参数（顺序即 chunk_index 顺序）
     */
    @Transactional
    public void replaceChunks(long documentId, List<DocumentChunkInsertParam> params) {
        if (params == null || params.isEmpty()) {
            throw new IllegalArgumentException("chunk 集合为空，拒绝写入: documentId=" + documentId);
        }
        documentChunkMapper.deleteByDocumentId(documentId);
        for (DocumentChunkInsertParam param : params) {
            documentChunkMapper.insertChunkWithEmbedding(param);
        }
        statusUpdater.updateChunkCount(documentId, params.size());
        statusUpdater.updateDocumentStatus(documentId, "indexed", 100, null);
        log.info("chunk 事务写入完成: documentId={}, chunkCount={}", documentId, params.size());
    }
}
