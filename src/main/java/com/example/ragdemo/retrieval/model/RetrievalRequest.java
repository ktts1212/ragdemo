package com.example.ragdemo.retrieval.model;

/**
 * 检索请求（Day 2 Step 1）。
 * <p>
 * <b>职责边界</b>：{@code query} 是<b>已经确定用于检索</b>的查询 —— 会话历史改写（query rewrite）
 * 由调用方（如 {@code ChatServiceImpl}）在调用本层之前完成，本层<b>不做</b>改写、不读聊天历史、
 * 不调用大模型。
 *
 * @param query           已确定的检索查询（非空）
 * @param knowledgeBaseId 检索范围（必填，严禁跨知识库）
 * @param topK            返回条数；为 {@code null} 时使用 {@code rag.retrieval.top-k} 配置默认值
 * @param minScore        相似度下限；为 {@code null} 时使用 {@code rag.retrieval.min-score} 配置默认值
 */
public record RetrievalRequest(String query, Long knowledgeBaseId, Integer topK, Double minScore) {

    /** 使用配置默认 topK / minScore 的便捷构造。 */
    public static RetrievalRequest of(String query, Long knowledgeBaseId) {
        return new RetrievalRequest(query, knowledgeBaseId, null, null);
    }
}
