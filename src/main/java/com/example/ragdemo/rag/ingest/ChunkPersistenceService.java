package com.example.ragdemo.rag.ingest;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ragdemo.mapper.DocumentChunkMapper;
import com.example.ragdemo.mapper.IngestionTaskMapper;
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
    private final IngestionTaskMapper ingestionTaskMapper;

    public ChunkPersistenceService(DocumentChunkMapper documentChunkMapper,
            IngestionStatusUpdater statusUpdater,
            IngestionTaskMapper ingestionTaskMapper) {
        this.documentChunkMapper = documentChunkMapper;
        this.statusUpdater = statusUpdater;
        this.ingestionTaskMapper = ingestionTaskMapper;
    }

    /**
     * 用当前 chunk 集合替换该文档的全部 chunk，并把文档置为 indexed。
     * <p>
     * <b>行为自 Step 4 起未变</b>（旧链路/GENERIC 继续使用本方法，无 fencing）。
     *
     * @param params 已算好 embedding 的插入参数（顺序即 chunk_index 顺序）
     */
    @Transactional
    public void replaceChunks(long documentId, List<DocumentChunkInsertParam> params) {
        doReplace(documentId, params);
    }

    /**
     * Step 5：<b>带 fencing 的替换写入</b>（图入库路径使用）。
     * <p>
     * fencing 校验与业务写入在<b>同一个事务</b>内完成：先 {@code SELECT ... FOR UPDATE}
     * 锁住任务行并校验 {@code owner + fencing_token + status='running'}，通过后才
     * delete → insert → chunk_count → indexed。因此：
     * <ul>
     *   <li>租约已被他人接管的旧 Worker 提交时会被拒绝（不会写坏数据）；</li>
     *   <li>"校验通过"与"写入提交"之间不存在 TOCTOU：并发抢占的 UPDATE 会被行锁
     *       阻塞到本事务结束。</li>
     * </ul>
     *
     * @throws IllegalStateException 执行权已失效（过期 Worker 的迟到提交）
     */
    @Transactional
    public void replaceChunksGuarded(long documentId, List<DocumentChunkInsertParam> params,
            long taskId, String leaseOwner, long fencingToken) {
        Long owned = ingestionTaskMapper.lockOwnedToken(taskId, leaseOwner, fencingToken);
        if (owned == null) {
            throw new IllegalStateException("FENCING_REJECTED: 执行权已失效，拒绝写入 taskId=" + taskId
                    + " owner=" + leaseOwner + " token=" + fencingToken);
        }
        doReplace(documentId, params);
    }

    /** 原事务体的唯一实现（两个入口共用，保证旧链路行为逐字不变）。 */
    private void doReplace(long documentId, List<DocumentChunkInsertParam> params) {
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
