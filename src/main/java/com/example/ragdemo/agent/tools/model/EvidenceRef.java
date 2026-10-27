package com.example.ragdemo.agent.tools.model;

import java.util.List;

/**
 * 可追溯原始证据引用（Step 2 §4）。
 * <p>
 * <b>语义边界</b>：本对象只描述"在原文哪里、用什么方式、读到了什么"，
 * 不含任何结论/推断/换算。<br>
 * {@code verifiedAgainstOriginal=false} 表示该证据<b>未</b>与原始 PDF 逐字核验
 * （例如仅来自 Canonical 解析结果），此时必须带 {@code UNVERIFIED_ALIGNMENT} 类警告。
 *
 * @param documentId              documents.id
 * @param documentVersion         解析版本（documents.version）
 * @param sourceType              来源维度
 * @param pageNumber              1-based 页码；无法确定时为 null（禁止猜）
 * @param locator                 结构化定位
 * @param extractionMethod        提取方法维度
 * @param content                 原文证据片段（已按硬上限截断）
 * @param truncated               content 是否被截断
 * @param verifiedAgainstOriginal 是否已与原始 PDF 核验
 * @param warnings                提取不确定性与冲突提示
 */
public record EvidenceRef(Long documentId, Integer documentVersion, SourceType sourceType,
        Integer pageNumber, Locator locator, ExtractionMethod extractionMethod, String content,
        boolean truncated, boolean verifiedAgainstOriginal, List<String> warnings) {

    public EvidenceRef {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
