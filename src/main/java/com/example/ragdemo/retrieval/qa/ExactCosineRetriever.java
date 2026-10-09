package com.example.ragdemo.retrieval.qa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 精确（暴力）余弦检索（skill §16）。
 * <p>
 * Retrieval QA 第一阶段<b>必须</b>用精确检索：当前语料规模小，IVFFlat 的近似误差会污染
 * "分块策略 / embeddingText 策略"的对照结论。ANN 对比是后续独立一步。
 */
public final class ExactCosineRetriever {

    private ExactCosineRetriever() {
    }

    /**
     * @param units   语料
     * @param vectors 与 units 一一对应的向量
     * @param query   查询向量
     * @param topK    返回条数
     * @return 按余弦相似度降序的前 topK 个 unit（不足则全返回）
     */
    public static List<RetrievalUnit> topK(List<RetrievalUnit> units, List<float[]> vectors,
            float[] query, int topK) {
        if (units == null || units.isEmpty() || vectors == null
                || vectors.size() != units.size() || query == null || query.length == 0) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>(units.size());
        for (int i = 0; i < units.size(); i++) {
            scored.add(new Scored(units.get(i), cosine(query, vectors.get(i))));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<RetrievalUnit> result = new ArrayList<>(Math.min(topK, scored.size()));
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            result.add(scored.get(i).unit());
        }
        return result;
    }

    /** 余弦相似度；任一向量为零向量或长度不一致时返回 0（不抛异常，保证 QA 可继续）。 */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record Scored(RetrievalUnit unit, double score) {
    }
}
