package com.example.ragdemo.document.pipeline;

import java.util.List;

import com.example.ragdemo.document.cleaner.CleaningResult;
import com.example.ragdemo.document.cleaner.DocumentQualityMetrics;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.repair.ReadingOrderRepairAudit;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Canonical PDF → AcademicDocument 全链路处理结果。
 *
 * @param document             最终 Canonical AcademicDocument
 * @param rawDocument          Marker 原始 JSON（供人工对照 / 持久化）
 * @param markerElapsedSeconds Marker 服务侧解析耗时（秒）
 * @param repairAudits         ReadingOrderRepair 的审计记录
 * @param cleaning             PaperCleaner 清洗结果（含 warnings + validation/metrics）
 */
public record PipelineResult(
        AcademicDocument document,
        JsonNode rawDocument,
        Double markerElapsedSeconds,
        List<ReadingOrderRepairAudit> repairAudits,
        CleaningResult cleaning
) {

    public boolean valid() {
        return cleaning.valid();
    }

    public List<String> warnings() {
        return cleaning.warnings();
    }

    public DocumentQualityMetrics metrics() {
        return cleaning.validation().metrics();
    }

    public int repairCount() {
        return repairAudits == null ? 0 : repairAudits.size();
    }
}
