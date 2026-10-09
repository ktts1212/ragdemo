package com.example.ragdemo.document.cleaner;

import java.util.Locale;
import java.util.Set;

/**
 * 主章节锚点（major heading anchor）判定（skill §4.2）。
 * <p>
 * 用途：
 * <ul>
 *   <li>{@link SectionHierarchyNormalizer}：判断某 root 是否为主章节（主章节保持 root，不做重挂）</li>
 *   <li>{@link EmptyContentCleaner}：<b>不删除</b>标题命中锚点的空 section —— 空得只剩标题的
 *       {@code Methods} 是真实结构，删除会让后续 section 丢失父级语义</li>
 * </ul>
 * 归一化：lowercase / trim / 空白折叠 / 去数字前缀 / 去尾部冒号；同时兼容"仅保留字母"的宽松形式，
 * 以避免相比既有实现出现行为回退。
 */
final class MajorSectionAnchors {

    /** 既有主章节集（保持向后兼容）∪ skill §4.2 列出的 canonical 主标题集 */
    private static final Set<String> ANCHORS = Set.of(
            "introduction",
            "background",
            "related work",
            "methods",
            "method",
            "methodology",
            "materials and methods",
            "experimental setup",
            "experiments",
            "experiment",
            "results",
            "discussion",
            "conclusion",
            "conclusions",
            "limitations",
            "references",
            "supplementary material",
            "ablation",
            "ablations",
            "evaluation");

    private MajorSectionAnchors() {
    }

    /** 是否为高置信主章节锚点（严格归一化或宽松归一化任一命中即算）。 */
    static boolean isMajorAnchor(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        return ANCHORS.contains(normalize(title)) || ANCHORS.contains(normalizeLoose(title));
    }

    /**
     * 严格归一化：去编号前缀（阿拉伯 / 罗马）→ 空白折叠 → 去尾部冒号。
     * <p>
     * <b>罗马前缀必须剥离</b>：{@code "II. METHODS"} / {@code "III. RESULTS"} / {@code "IV. CONCLUSIONS"}
     * 是学术 PDF 常见的一级编号风格。若不剥离，它们会被误判为"非主章节"，
     * 进而被 Rule A（`repairRootLevelDrop`）错误下挂。
     * <p>
     * 安全性：罗马数字仅含 {@code i v x l c}，且模式要求编号后<b>紧随分隔符（. 或 )）或空白</b>，
     * 因此 {@code limitations} / {@code introduction} / {@code conclusions} / {@code references}
     * 等真实锚点<b>不会</b>被误剥（剥离只做一次前缀匹配，且之后仍需与锚点集精确相等）。
     */
    static String normalize(String title) {
        if (title == null) {
            return null;
        }
        String value = title.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        value = value.replaceAll("^\\d+(\\.\\d+)*[.)]?\\s*", "");
        value = value.replaceAll("^[ivxlc]+(?:[.)]\\s*|\\s+)", "");
        value = value.replaceAll("[:：]+$", "").trim();
        return value;
    }

    /** 宽松归一化：仅保留字母与空格（兼容既有实现）。 */
    static String normalizeLoose(String title) {
        if (title == null) {
            return null;
        }
        return title.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim();
    }
}
