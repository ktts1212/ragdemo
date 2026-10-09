package com.example.ragdemo.retrieval.qa;

import java.util.List;

/**
 * Retrieval QA 的 gold 用例（skill §18）。
 * <p>
 * <b>判定优先级</b>（取第一个「gold 与 unit 都不为空」的信号，而不是 OR 全部）：
 * <pre>
 * 1. sourceBlockIds overlap
 * 2. expected SectionPath（unit 路径后缀匹配）
 * 3. expected Pages overlap
 * 4. expectedEvidenceText（旧 splitter 无 sourceBlockIds 时的兜底口径）
 * </pre>
 */
public record RetrievalGoldCase(
        String id,
        String paperId,
        String question,
        QuestionType questionType,
        boolean answerable,
        List<String> expectedSourceBlockIds,
        List<String> expectedSectionPath,
        List<Integer> expectedPages,
        String expectedEvidenceText
) {

    /** skill §18：至少覆盖这些类型 */
    public enum QuestionType {
        TEXT_FACT, METHOD, NUMERIC, COMPARISON, TABLE, FIGURE, LIST, REFERENCE, NO_ANSWER
    }

    public static RetrievalGoldCase of(String id, String paperId, String question,
            QuestionType type, String evidence, String... sourceBlockIds) {
        return new RetrievalGoldCase(id, paperId, question, type, true,
                List.of(sourceBlockIds), List.of(), List.of(), evidence);
    }

    public static RetrievalGoldCase unanswerable(String id, String paperId, String question) {
        return new RetrievalGoldCase(id, paperId, question, QuestionType.NO_ANSWER, false,
                List.of(), List.of(), List.of(), null);
    }
}
