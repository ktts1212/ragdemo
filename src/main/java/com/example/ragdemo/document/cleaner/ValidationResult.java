package com.example.ragdemo.document.cleaner;

import java.util.List;

/**
 * Canonical 校验结果（skill §28）。
 *
 * @param errors   硬问题（应阻断后续 ingest）
 * @param warnings 软问题（可入库但需关注）
 * @param metrics  质量度量
 */
public record ValidationResult(
        List<String> errors,
        List<String> warnings,
        DocumentQualityMetrics metrics
) {

    public boolean valid() {
        return errors.isEmpty();
    }
}
