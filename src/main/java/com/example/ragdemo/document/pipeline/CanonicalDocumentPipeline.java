package com.example.ragdemo.document.pipeline;

import java.util.List;

import org.springframework.stereotype.Service;

import com.example.ragdemo.document.cleaner.CleaningResult;
import com.example.ragdemo.document.cleaner.PaperCleaner;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.parser.marker.MarkerDocumentParser;
import com.example.ragdemo.document.repair.ReadingOrderRepair;
import com.example.ragdemo.document.repair.ReadingOrderRepairAudit;
import com.example.ragdemo.infrastructure.marker.MarkerClient;
import com.example.ragdemo.infrastructure.marker.MarkerResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Canonical PDF → AcademicDocument 全链路编排器（skill §43）。
 * <pre>
 * PDF
 *  ↓ MarkerClient
 * Marker Raw JSON
 *  ↓ MarkerDocumentParser
 * Parsed AcademicDocument
 *  ↓ ReadingOrderRepair
 *  ↓ PaperCleaner
 * Canonical AcademicDocument（+ validation / quality metrics）
 * </pre>
 * <p>
 * 注意（skill §33/§42）：本编排器<b>尚未</b>接入正常 Kafka ingestion。
 * 它用于真实论文 QA 与后续 SectionAwareChunker 的上游，直到
 * {@code READY_FOR_INGESTION_INTEGRATION = YES} 后才允许接入 IngestionServiceImpl。
 */
@Slf4j
@Service
public class CanonicalDocumentPipeline {

    private final MarkerClient markerClient;
    private final MarkerDocumentParser markerDocumentParser;
    private final ReadingOrderRepair readingOrderRepair;
    private final PaperCleaner paperCleaner;

    public CanonicalDocumentPipeline(MarkerClient markerClient,
            MarkerDocumentParser markerDocumentParser,
            ReadingOrderRepair readingOrderRepair,
            PaperCleaner paperCleaner) {
        this.markerClient = markerClient;
        this.markerDocumentParser = markerDocumentParser;
        this.readingOrderRepair = readingOrderRepair;
        this.paperCleaner = paperCleaner;
    }

    /**
     * 执行完整解析链路。
     *
     * @param documentId 系统 documents.id
     * @param pdfBytes   PDF 内容
     * @param fileName   PDF 文件名
     * @return 全链路结果
     */
    public PipelineResult process(String documentId, byte[] pdfBytes, String fileName) {
        long start = System.currentTimeMillis();

        // 1. Marker API：PDF → Raw JSON
        MarkerResponse response = markerClient.parse(pdfBytes, fileName);

        // 2. Raw JSON → Parsed AcademicDocument
        AcademicDocument document = markerDocumentParser.parse(documentId, response.data());

        // 3. 阅读顺序修复
        List<ReadingOrderRepairAudit> audits = readingOrderRepair.repair(document);

        // 4. 清洗 + 校验
        CleaningResult cleaning = paperCleaner.clean(document, audits.size());

        log.info("Canonical pipeline 完成: documentId={}, markerElapsed={}s, repairCount={}, "
                        + "valid={}, durationMs={}ms",
                documentId, response.elapsedSeconds(), audits.size(),
                cleaning.valid(), System.currentTimeMillis() - start);

        return new PipelineResult(document, response.data(), response.elapsedSeconds(),
                audits, cleaning);
    }
}
