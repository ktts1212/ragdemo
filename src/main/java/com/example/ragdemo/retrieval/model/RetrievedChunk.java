package com.example.ragdemo.retrieval.model;

/**
 * 检索命中的结构化证据（Day 2 Step 1）。
 * <p>
 * <b>只承载数据库确实返回的字段</b>（见 {@code mapper/DocumentChunkMapper.xml#searchSimilarChunks}
 * 的 SELECT 别名）：{@code chunkId / documentId / documentName / page / chunkIndex / content / score}。
 * 当前 SQL <b>没有</b>返回 {@code sectionPath}、{@code title} 等字段 —— 因此这里不虚构，
 * 相关缺口记录在交付报告的"遗留问题"中，留待 Day 2 Step 2 按需扩展（本步骤不改 SQL）。
 * <p>
 * 之所以用 record：便于后续 Agent Tool 直接序列化为 JSON（只读、无行为）。
 */
public record RetrievedChunk(
        Long chunkId,
        Long documentId,
        String documentName,
        Integer chunkIndex,
        Integer page,
        String content,
        Double score) {
}
