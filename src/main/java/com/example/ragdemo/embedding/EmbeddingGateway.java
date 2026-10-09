package com.example.ragdemo.embedding;

import java.util.List;

/**
 * 统一 Embedding 网关（skill §4.2）。
 * <p>
 * <b>为什么需要它</b>：项目里同时存在两条 embedding 调用路径
 * （{@code OpenAiClient.embed()} 与 LangChain4j {@code EmbeddingModel}）。
 * 生产 ingestion 与 query retrieval 必须走<b>同一个逻辑入口</b>，
 * 否则入库与查询可能使用不同的 baseUrl / model / dimension / 归一化配置 —— 这是静默的检索质量杀手。
 * <p>
 * {@code query} 与 {@code document} 语义分开保留：当前 bge-m3 不区分，
 * 但未来若换成需要 query instruction 的模型（如 bge 系列的部分版本），接口无需改动。
 */
public interface EmbeddingGateway {

    /** 单条查询文本 → 向量。 */
    float[] embedQuery(String text);

    /** 批量文档文本 → 向量列表，顺序与入参一致。 */
    List<float[]> embedDocuments(List<String> texts);
}
