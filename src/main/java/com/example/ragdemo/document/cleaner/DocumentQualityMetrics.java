package com.example.ragdemo.document.cleaner;

/**
 * Canonical AcademicDocument 质量度量（skill §29）。
 * <p>
 * 不要只返回 "parse success"，必须给出可观测的结构指标。
 */
public record DocumentQualityMetrics(
        int sectionCount,
        int paragraphCount,
        int figureCount,
        int tableCount,
        int referenceCount,
        int headerFooterRemaining,
        int unnamedSectionCount,
        int emptySectionCount,
        int base64RemainingCount,
        int crossPageMergeCount,
        int repairCount,
        // ===== skill §8 收尾检查指标（均只作 warning / metric）=====
        int frontMatterDuplicateCount,
        int orphanCaptionCount,
        int levelPathMismatchCount,
        int duplicateAffiliationCount,
        boolean rootTitleMismatch,
        // ===== skill §9 结构修复复核指标（canonical_section_float_repair）=====
        /** 主章节锚点嵌套在另一个主章节锚点之下（例：Methods 挂在 Discussion 下） */
        int majorHeadingNestedUnderMajorHeadingCount,
        /** 标题编号层级 <= 父节点编号层级（本应为同级却被父子化） */
        int sameDepthHeadingParentChildConflictCount,
        /** 修复后仍与正文引用不一致的浮动块数（独立复核，理想 0） */
        int floatReferenceSectionMismatchCount,
        /** 有编号但正文完全没有引用的浮动块数（保守不动） */
        int unresolvedFloatReferenceCount,
        /** path 末元素与 title 不一致的 section 数 */
        int sectionPathMismatchCount
) {
}
