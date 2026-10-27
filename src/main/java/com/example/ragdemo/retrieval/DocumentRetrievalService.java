package com.example.ragdemo.retrieval;

import java.util.List;

import com.example.ragdemo.retrieval.model.RetrievalRequest;
import com.example.ragdemo.retrieval.model.RetrievedChunk;

/**
 * 可复用的文献检索能力（Day 2 Step 1 提取）。
 * <p>
 * <b>职责</b>：根据"已确定的查询 + 检索参数"返回<b>结构化证据</b>。
 * <b>不负责</b>：会话记忆、query rewrite、prompt 拼装、调用大模型生成答案、持久化聊天 ——
 * 这些仍由 {@code ChatServiceImpl} 负责；后续 Agent Tool 也只复用本接口。
 * <p>
 * 传统 {@code /api/chat} 与将来的 Agent Tools（Day 2 Step 2）使用<b>同一个</b>实现。
 */
public interface DocumentRetrievalService {

    /**
     * 执行一次检索：query 向量化 → 原有 pgvector 检索 → 同等 score 过滤与排序 → 映射为结构化结果。
     *
     * @param request 检索请求（{@code query} 非空，{@code knowledgeBaseId} 必填）
     * @return 已按相似度降序、且已应用 minScore 过滤的结果；无命中返回空列表
     * @throws IllegalArgumentException 参数非法（request/query 为空、缺 knowledgeBaseId、topK ≤ 0）
     */
    List<RetrievedChunk> retrieve(RetrievalRequest request);
}
