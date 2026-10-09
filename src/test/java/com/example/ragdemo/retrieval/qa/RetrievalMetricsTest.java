package com.example.ragdemo.retrieval.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ragdemo.retrieval.qa.RetrievalGoldCase.QuestionType;

/**
 * Retrieval 指标单测（skill §18 / §19）—— 纯离线，无任何外部依赖。
 */
class RetrievalMetricsTest {

    private static RetrievalUnit unit(String id, String... sourceBlockIds) {
        return RetrievalUnit.of(id, "text " + id, "TEXT", List.of(sourceBlockIds), List.of(), List.of());
    }

    @Test
    @DisplayName("Recall@k 与 MRR：命中 rank1 / rank3 / 未命中")
    void computesRecallAndMrr() {
        List<RetrievalGoldCase> gold = new ArrayList<>(List.of(
                RetrievalGoldCase.of("q1", "p1", "question 1", QuestionType.TEXT_FACT, null, "/b/1"),
                RetrievalGoldCase.of("q2", "p1", "question 2", QuestionType.TABLE, null, "/b/2"),
                RetrievalGoldCase.of("q3", "p1", "question 3", QuestionType.FIGURE, null, "/b/3")));

        List<List<RetrievalUnit>> ranked = new ArrayList<>(List.of(
                List.of(unit("u1", "/b/1"), unit("u9", "/b/9")),                      // rank 1
                List.of(unit("u8", "/b/8"), unit("u7", "/b/7"), unit("u2", "/b/2")),  // rank 3
                List.of(unit("u6", "/b/6"), unit("u5", "/b/5"))));                    // miss

        RetrievalMetrics metrics = RetrievalMetrics.compute(gold, ranked);

        assertThat(metrics.caseCount()).isEqualTo(3);
        assertThat(metrics.recallAt1()).isEqualTo(1.0 / 3);
        assertThat(metrics.recallAt3()).isEqualTo(2.0 / 3);
        assertThat(metrics.recallAt5()).isEqualTo(2.0 / 3);
        assertThat(metrics.recallAt10()).isEqualTo(2.0 / 3);
        assertThat(metrics.hitRateAt5()).isEqualTo(2.0 / 3);
        assertThat(metrics.mrr()).isCloseTo((1.0 + 1.0 / 3 + 0.0) / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(metrics.recallAt5ByType())
                .containsEntry("TEXT_FACT", 1.0)
                .containsEntry("TABLE", 1.0)
                .containsEntry("FIGURE", 0.0);
    }

    @Test
    @DisplayName("no-answer 用例不计入 recall（阈值分析属单独一层，skill §20）")
    void unanswerableCasesAreExcluded() {
        List<RetrievalGoldCase> gold = List.of(
                RetrievalGoldCase.of("q1", "p1", "answerable", QuestionType.TEXT_FACT, null, "/b/1"),
                RetrievalGoldCase.unanswerable("q2", "p1", "out-of-corpus"));

        RetrievalMetrics metrics = RetrievalMetrics.compute(gold,
                List.of(List.of(unit("u1", "/b/1")), List.of(unit("u1", "/b/1"))));

        assertThat(metrics.caseCount()).isEqualTo(1);
        assertThat(metrics.recallAt1()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("命中判定按优先级取第一个「双方都非空」的信号：blockIds > sectionPath > pages > evidence")
    void hitMatchingUsesFirstAvailableSignal() {
        // 1) blockIds 优先：blockId 命中即算，忽略 evidence 不匹配
        RetrievalGoldCase byBlock = new RetrievalGoldCase("q1", "p", "q", QuestionType.TEXT_FACT, true,
                List.of("/b/1"), List.of("Sec"), List.of(3), "NOT-PRESENT");
        assertThat(RetrievalMetrics.isHit(byBlock, unit("u1", "/b/1"))).isTrue();
        assertThat(RetrievalMetrics.isHit(byBlock, unit("u2", "/b/2"))).isFalse();

        // 2) gold 无 blockIds → 回退 sectionPath 后缀匹配
        RetrievalGoldCase bySection = new RetrievalGoldCase("q2", "p", "q", QuestionType.TEXT_FACT, true,
                List.of(), List.of("4.1 Datasets"), List.of(), null);
        RetrievalUnit deep = RetrievalUnit.of("u3", "t", "TEXT", List.of(),
                List.of("4. Methods", "4.1 Datasets"), List.of());
        assertThat(RetrievalMetrics.isHit(bySection, deep)).isTrue();

        // 3) 只有 pages → 页码交集
        RetrievalGoldCase byPage = new RetrievalGoldCase("q3", "p", "q", QuestionType.FIGURE, true,
                List.of(), List.of(), List.of(9), null);
        RetrievalUnit paged = RetrievalUnit.of("u4", "t", "FIGURE", List.of(), List.of(), List.of(8, 9, 10));
        assertThat(RetrievalMetrics.isHit(byPage, paged)).isTrue();

        // 4) 最后兜底 evidence 文本包含（旧 splitter 的共同口径）
        RetrievalGoldCase byEvidence = new RetrievalGoldCase("q4", "p", "q", QuestionType.TEXT_FACT, true,
                List.of(), List.of(), List.of(), "AUROC score");
        RetrievalUnit textual = RetrievalUnit.of("u5", "the AUROC score is 0.95", "TEXT",
                List.of(), List.of(), List.of());
        assertThat(RetrievalMetrics.isHit(byEvidence, textual)).isTrue();

        // 5) 所有信号都不匹配 → 未命中
        assertThat(RetrievalMetrics.isHit(byEvidence, unit("u6", "/b/6"))).isFalse();
    }

    @Test
    @DisplayName("空输入不抛异常，返回零用例指标")
    void emptyInputIsSafe() {
        RetrievalMetrics metrics = RetrievalMetrics.compute(List.of(), List.of());
        assertThat(metrics.caseCount()).isZero();
        assertThat(metrics.mrr()).isZero();
        assertThat(metrics.summary()).contains("cases=0");
    }
}
