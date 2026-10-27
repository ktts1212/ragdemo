package com.example.ragdemo.workflow.ingestion.node;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.document.pipeline.CanonicalDocumentPipeline;
import com.example.ragdemo.document.pipeline.PipelineResult;
import com.example.ragdemo.rag.ingest.IngestionStatusUpdater;
import com.example.ragdemo.service.MinioService;
import com.example.ragdemo.workflow.ingestion.IngestionErrorCodes;
import com.example.ragdemo.workflow.ingestion.IngestionState;
import com.example.ragdemo.workflow.ingestion.artifact.IngestionArtifactService;
import com.example.ragdemo.workflow.testkit.WorkflowFaultInjector;

import lombok.extern.slf4j.Slf4j;

/**
 * 解析节点：{@code source} → Marker → Parser → ReadingOrderRepair → Cleaner → canonical.json。
 * <p>
 * <b>为什么 parse 与 clean 合并成一个节点</b>：现有
 * {@link CanonicalDocumentPipeline#process} 是<b>一体化调用</b>（内部含 Marker/Parser/Repair/Cleaner），
 * step4 §4 明确允许先合并，但要求"明确 Marker 原始产物落盘时机仍存在的额外重算窗口"：
 * 本节点先做 provenance 校验，命中才复用；未命中则整段重算（<b>Marker 也会重跑</b>），
 * 也就是说不存在仅重跑 Cleaner 的细粒度恢复。
 * <p>
 * <b>副作用顺序</b>：先写 marker-raw / canonical / provenance sidecar，全部成功后才返回节点完成 ——
 * 这样"产物已写但 checkpoint 未提交"时重跑，会因 provenance 命中而直接复用。
 */
@Slf4j
@Component
public class ParseArtifactNode {

    private final MinioService minioService;
    private final CanonicalDocumentPipeline pipeline;
    private final IngestionArtifactService artifacts;
    private final IngestionStatusUpdater statusUpdater;
    private final RagProperties ragProperties;
    private final WorkflowFaultInjector faultInjector;

    public ParseArtifactNode(MinioService minioService, CanonicalDocumentPipeline pipeline,
            IngestionArtifactService artifacts, IngestionStatusUpdater statusUpdater,
            RagProperties ragProperties, WorkflowFaultInjector faultInjector) {
        this.minioService = minioService;
        this.pipeline = pipeline;
        this.artifacts = artifacts;
        this.statusUpdater = statusUpdater;
        this.ragProperties = ragProperties;
        this.faultInjector = faultInjector;
    }

    public Map<String, Object> apply(IngestionState state) {
        long dbDocumentId = state.dbDocumentId();
        int version = state.documentVersion();
        String canonicalKey = artifacts.canonicalKey(dbDocumentId, version);
        String markerRawKey = artifacts.markerRawKey(dbDocumentId, version);

        if (artifacts.isCanonicalReusable(dbDocumentId, version, state.checksum(), canonicalKey)) {
            log.info("复用已有 canonical 产物（provenance 校验通过）: key={}", canonicalKey);
            return result(markerRawKey, canonicalKey, "PARSED_REUSED");
        }

        byte[] pdfBytes = download(state.sourceKey(), dbDocumentId);
        statusUpdater.updateDocumentStatus(dbDocumentId, "parsing", 30, null);

        PipelineResult result = pipeline.process(state.checksum(), pdfBytes, state.fileName());
        if (!result.valid()) {
            throw new IllegalStateException(IngestionErrorCodes.INVALID_CANONICAL
                    + result.cleaning().validation().errors());
        }
        if (!result.warnings().isEmpty()) {
            log.warn("canonical warning-only 入库: documentId={}, warnings={}",
                    dbDocumentId, result.warnings());
        }

        // 先落盘，再返回完成
        artifacts.writeJson(markerRawKey, result.rawDocument());
        artifacts.writeJson(canonicalKey, result.document());
        artifacts.writeCanonicalMeta(dbDocumentId, version, result.document(), state.checksum(),
                ragProperties.getParser().getBackend());

        // 故障注入点（生产 no-op）：A1 —— 产物已写、节点未返回（Checkpoint 未提交）
        faultInjector.at(WorkflowFaultInjector.Points.PARSE_AFTER_ARTIFACTS,
                Map.of("taskId", state.taskId(), "documentId", dbDocumentId));

        return result(markerRawKey, canonicalKey, "PARSED");
    }

    private Map<String, Object> result(String markerRawKey, String canonicalKey, String status) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put(IngestionState.MARKER_RAW_KEY, markerRawKey);
        delta.put(IngestionState.CANONICAL_KEY, canonicalKey);
        delta.put(IngestionState.STATUS, status);
        return delta;
    }

    private byte[] download(String sourceKey, long dbDocumentId) {
        try (InputStream in = minioService.download(sourceKey)) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(
                    IngestionErrorCodes.DOWNLOAD_FAILED + sourceKey + " documentId=" + dbDocumentId, e);
        }
    }
}
