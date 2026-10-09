package com.example.ragdemo.document.cleaner;

import java.util.List;

import com.example.ragdemo.document.domain.AcademicDocument;

/**
 * PaperCleaner 整条清洗管道的产出。
 *
 * @param document   清洗后的 Canonical AcademicDocument
 * @param warnings   清洗过程中产生的全部 warning
 * @param validation 最终校验结果（含质量度量）
 */
public record CleaningResult(
        AcademicDocument document,
        List<String> warnings,
        ValidationResult validation,
        List<FloatBlockSectionReassigner.Audit> floatAudits
) {

    public boolean valid() {
        return validation.valid();
    }

    /** Figure/Table 浮动归属修复的审计记录数（skill §8 可追踪性）。 */
    public int floatReassignedCount() {
        return floatAudits == null ? 0 : floatAudits.size();
    }
}
