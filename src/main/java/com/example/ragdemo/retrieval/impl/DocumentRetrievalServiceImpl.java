package com.example.ragdemo.retrieval.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.embedding.EmbeddingGateway;
import com.example.ragdemo.mapper.DocumentChunkMapper;
import com.example.ragdemo.model.dto.RetrievedChunkDto;
import com.example.ragdemo.retrieval.DocumentRetrievalService;
import com.example.ragdemo.retrieval.model.RetrievalRequest;
import com.example.ragdemo.retrieval.model.RetrievedChunk;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link DocumentRetrievalService} 的实现（Day 2 Step 1）。
 * <p>
 * <b>行为等价性说明</b>：本实现的每一步都刻意与重构前 {@code ChatServiceImpl} 中内联的那段代码一致，
 * 以做到"只搬动、不改语义"：
 * <ol>
 *   <li>向量化：改用共享网关 {@link EmbeddingGateway#embedQuery(String)} —— 它内部就是
 *       {@code embeddingModel.embed(text).content().vector()}，与旧内联调用<b>完全相同</b>
 *       （额外收益：Step 2 的工具复用同一入口）；</li>
 *   <li>pgvector 字面量：沿用旧实现的 {@code float[] → "[v1,v2,...]"} 转换（<b>不</b>换成
 *       double 版实现，避免任何精度/字面量差异）；</li>
 *   <li>检索：仍是原有 {@link DocumentChunkMapper#searchSimilarChunks}（SQL 未改，仍过滤
 *       {@code documents.status='indexed'}）；</li>
 *   <li>过滤/排序：仍是 {@code score != null && score >= minScore}，保持 Mapper 的距离升序。</li>
 * </ol>
 * <p>
 * 本类<b>不</b>读写聊天历史、<b>不</b>拼装 prompt、<b>不</b>调用 ChatModel。
 */
@Slf4j
@Service
public class DocumentRetrievalServiceImpl implements DocumentRetrievalService {

    private final EmbeddingGateway embeddingGateway;
    private final DocumentChunkMapper documentChunkMapper;
    private final RagProperties ragProperties;

    public DocumentRetrievalServiceImpl(EmbeddingGateway embeddingGateway,
            DocumentChunkMapper documentChunkMapper, RagProperties ragProperties) {
        this.embeddingGateway = embeddingGateway;
        this.documentChunkMapper = documentChunkMapper;
        this.ragProperties = ragProperties;
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrievalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("检索请求不能为空");
        }
        String query = request.query();
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索 query 不能为空");
        }
        Long knowledgeBaseId = request.knowledgeBaseId();
        if (knowledgeBaseId == null) {
            throw new IllegalArgumentException("knowledgeBaseId 不能为空：检索必须限定知识库范围");
        }

        // 默认参数取自现有配置，保证"缺省不改变旧接口结果"
        int topK = request.topK() != null ? request.topK() : ragProperties.getRetrieval().getTopK();
        double minScore = request.minScore() != null
                ? request.minScore()
                : ragProperties.getRetrieval().getMinScore();
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须大于 0");
        }

        float[] questionVector = embeddingGateway.embedQuery(query);
        String questionEmbedding = toPgVectorString(questionVector);
        List<RetrievedChunkDto> rows = documentChunkMapper.searchSimilarChunks(
                knowledgeBaseId, questionEmbedding, topK);
        if (rows == null || rows.isEmpty()) {
            log.debug("检索无命中: kbId={}, topK={}, minScore={}", knowledgeBaseId, topK, minScore);
            return List.of();
        }

        List<RetrievedChunk> chunks = rows.stream()
                .filter(chunk -> chunk.getScore() != null && chunk.getScore() >= minScore)
                .map(DocumentRetrievalServiceImpl::toRetrievedChunk)
                .toList();
        log.info("检索完成: kbId={}, topK={}, minScore={}, 命中={}/{}",
                knowledgeBaseId, topK, minScore, chunks.size(), rows.size());
        return chunks;
    }

    private static RetrievedChunk toRetrievedChunk(RetrievedChunkDto dto) {
        return new RetrievedChunk(
                dto.getChunkId(),
                dto.getDocumentId(),
                dto.getDocumentName(),
                dto.getChunkIndex(),
                dto.getPage(),
                dto.getContent(),
                dto.getScore());
    }

    /**
     * float[] → pgvector 字面量（与重构前 {@code ChatServiceImpl#toPgVectorString} 逐字一致）。
     */
    private static String toPgVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
