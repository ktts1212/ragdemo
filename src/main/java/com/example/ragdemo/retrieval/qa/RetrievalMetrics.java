package com.example.ragdemo.retrieval.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索指标（skill §19）。
 * <p>
 * 只统计 {@code answerable=true} 的用例；no-answer 用例的阈值分析属单独一层（skill §20）。
 *
 * @param caseCount       参与统计的可答用例数
 * @param recallAt1       top-1 命中率
 * @param recallAt3       top-3 命中率
 * @param recallAt5       top-5 命中率
 * @param recallAt10      top-10 命中率
 * @param mrr             首个命中的平均倒数排名（未命中记 0）
 * @param hitRateAt5      等价于 recallAt5，单独列出以贴合 skill 命名
 * @param recallAt5ByType 按 questionType 分组的 top-5 命中率
 */
public record RetrievalMetrics(
        int caseCount,
        double recallAt1,
        double recallAt3,
        double recallAt5,
        double recallAt10,
        double mrr,
        double hitRateAt5,
        Map<String, Double> recallAt5ByType
) {

    private static final List<Integer> KS = List.of(1, 3, 5, 10);

    public static RetrievalMetrics compute(List<RetrievalGoldCase> cases,
            List<List<RetrievalUnit>> rankedPerCase) {
        if (cases == null || cases.isEmpty()) {
            return new RetrievalMetrics(0, 0, 0, 0, 0, 0, 0, Map.of());
        }

        int[] hits = new int[KS.size()];
        double reciprocalRankSum = 0;
        int answerable = 0;
        Map<String, int[]> byType = new LinkedHashMap<>();

        for (int i = 0; i < cases.size(); i++) {
            RetrievalGoldCase gold = cases.get(i);
            if (!gold.answerable()) {
                continue;
            }
            answerable++;
            List<RetrievalUnit> ranked = i < rankedPerCase.size()
                    ? rankedPerCase.get(i)
                    : List.of();
            int rank = firstHitRank(gold, ranked);

            if (rank > 0) {
                reciprocalRankSum += 1.0 / rank;
            }
            for (int k = 0; k < KS.size(); k++) {
                if (rank > 0 && rank <= KS.get(k)) {
                    hits[k]++;
                }
            }
            int[] typeCounter = byType.computeIfAbsent(gold.questionType().name(), key -> new int[2]);
            typeCounter[0]++;
            if (rank > 0 && rank <= 5) {
                typeCounter[1]++;
            }
        }

        if (answerable == 0) {
            return new RetrievalMetrics(0, 0, 0, 0, 0, 0, 0, Map.of());
        }

        Map<String, Double> byTypeRates = new LinkedHashMap<>();
        byType.forEach((type, counter) ->
                byTypeRates.put(type, counter[0] == 0 ? 0.0 : (double) counter[1] / counter[0]));

        return new RetrievalMetrics(
                answerable,
                (double) hits[0] / answerable,
                (double) hits[1] / answerable,
                (double) hits[2] / answerable,
                (double) hits[3] / answerable,
                reciprocalRankSum / answerable,
                (double) hits[2] / answerable,
                byTypeRates);
    }

    /** 最早命中的 1-based 排名；未命中返回 0。 */
    static int firstHitRank(RetrievalGoldCase gold, List<RetrievalUnit> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < ranked.size(); i++) {
            if (isHit(gold, ranked.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * 命中判定：按优先级取第一个「双方都非空」的信号（skill §18）。
     * 这样新旧链路可共用同一套 gold 而不互相占便宜。
     */
    static boolean isHit(RetrievalGoldCase gold, RetrievalUnit unit) {
        if (gold == null || unit == null || !gold.answerable()) {
            return false;
        }
        if (hasValues(gold.expectedSourceBlockIds()) && hasValues(unit.sourceBlockIds())) {
            return gold.expectedSourceBlockIds().stream().anyMatch(unit.sourceBlockIds()::contains);
        }
        if (hasValues(gold.expectedSectionPath()) && hasValues(unit.sectionPath())) {
            return endsWith(unit.sectionPath(), gold.expectedSectionPath());
        }
        if (hasValues(gold.expectedPages()) && hasValues(unit.pages())) {
            return gold.expectedPages().stream().anyMatch(unit.pages()::contains);
        }
        if (isNotBlank(gold.expectedEvidenceText()) && isNotBlank(unit.text())) {
            return unit.text().contains(gold.expectedEvidenceText());
        }
        return false;
    }

    private static boolean endsWith(List<String> actual, List<String> expected) {
        if (actual.size() < expected.size()) {
            return false;
        }
        return new ArrayList<>(actual.subList(actual.size() - expected.size(), actual.size()))
                .equals(new ArrayList<>(expected));
    }

    private static boolean hasValues(List<?> values) {
        return values != null && !values.isEmpty();
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 便于报告输出的一行摘要。 */
    public String summary() {
        return String.format("cases=%d R@1=%.3f R@3=%.3f R@5=%.3f R@10=%.3f MRR=%.3f",
                caseCount, recallAt1, recallAt3, recallAt5, recallAt10, mrr);
    }
}
