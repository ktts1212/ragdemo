package com.example.ragdemo.document.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.document.chunk.AcademicChunk;
import com.example.ragdemo.document.chunk.CharBasedTokenEstimator;
import com.example.ragdemo.document.chunk.ChunkQualityMetrics;
import com.example.ragdemo.document.chunk.ChunkQualityReporter;
import com.example.ragdemo.document.chunk.ChunkTextRenderer;
import com.example.ragdemo.document.chunk.ChunkTextSplitter;
import com.example.ragdemo.document.chunk.ChunkTokenEstimator;
import com.example.ragdemo.document.chunk.ChunkType;
import com.example.ragdemo.document.chunk.ChunkingResult;
import com.example.ragdemo.document.chunk.DocumentLevelChunker;
import com.example.ragdemo.document.chunk.FigureTableChunker;
import com.example.ragdemo.document.chunk.ListChunker;
import com.example.ragdemo.document.chunk.SectionAwareChunker;
import com.example.ragdemo.document.chunk.SectionBlockChunker;
import com.example.ragdemo.document.cleaner.AbstractRecovery;
import com.example.ragdemo.document.cleaner.BackMatterClassifier;
import com.example.ragdemo.document.cleaner.CanonicalDocumentValidator;
import com.example.ragdemo.document.cleaner.CleaningResult;
import com.example.ragdemo.document.cleaner.FloatBlockSectionReassigner;
import com.example.ragdemo.document.cleaner.CaptionRecovery;
import com.example.ragdemo.document.cleaner.DocumentQualityMetrics;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.TableBlock;
import com.example.ragdemo.document.repair.ReadingOrderRepairAudit;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.ragdemo.document.cleaner.EmptyContentCleaner;
import com.example.ragdemo.document.cleaner.FrontMatterCleaner;
import com.example.ragdemo.document.cleaner.HeaderFooterCleaner;
import com.example.ragdemo.document.cleaner.PaperCleaner;
import com.example.ragdemo.document.cleaner.ReferenceExtractor;
import com.example.ragdemo.document.cleaner.SectionHierarchyNormalizer;
import com.example.ragdemo.document.image.FigureImageStorage;
import com.example.ragdemo.document.image.LocalFigureImageStorage;
import com.example.ragdemo.document.parser.marker.MarkerBlockMapper;
import com.example.ragdemo.document.parser.marker.MarkerDocumentParser;
import com.example.ragdemo.document.parser.marker.MarkerSectionBuilder;
import com.example.ragdemo.document.parser.marker.MarkerTextExtractor;
import com.example.ragdemo.document.repair.FigureAssociatedTextDetector;
import com.example.ragdemo.document.repair.ReadingOrderRepair;
import com.example.ragdemo.document.repair.ReadingOrderRepairConfig;
import com.example.ragdemo.infrastructure.marker.MarkerClient;
import com.example.ragdemo.infrastructure.marker.MarkerHttpClient;
import com.example.ragdemo.infrastructure.marker.MarkerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Phase E — 真实论文端到端 QA（skill §31 Layer 4 / §32）。
 * <p>
 * 前置条件（缺任一项自动跳过，不阻塞 CI）：
 * <ol>
 *   <li>把真实论文 PDF 放到 {@code src/test/resources/marker/real-paper.pdf}</li>
 *   <li>Marker API 可达（SSH Tunnel 127.0.0.1:18001）</li>
 * </ol>
 * 运行：{@code mvn test -Dtest=RealPaperQaTest}
 * <p>
 * 输出 skill §32 要求的全部指标，供人工对照 Marker raw JSON 与 Canonical AcademicDocument。
 */
class RealPaperQaTest {

    /** 默认路径；可用 -Dmarker.qa.pdf=<path> 覆盖为任意真实 PDF */
    private static final String DEFAULT_PDF_PATH = "src/test/resources/marker/real-paper.pdf";
    private static final String PDF_PATH_PROPERTY = "marker.qa.pdf";
    private static final String MARKER_BASE_URL = "http://127.0.0.1:18001";

    private static Path resolvePdfPath() {
        String override = System.getProperty(PDF_PATH_PROPERTY);
        return Path.of(override != null && !override.isBlank() ? override : DEFAULT_PDF_PATH);
    }

    @Test
    void realPaper_fullPipeline_reportsMetrics() throws Exception {
        Path pdfPath = resolvePdfPath();
        Assumptions.assumeTrue(Files.exists(pdfPath),
                "未提供真实论文 PDF，跳过。放入 " + pdfPath.toAbsolutePath()
                        + " 或加 -D" + PDF_PATH_PROPERTY + "=<path> 可启用");
        byte[] pdfBytes = Files.readAllBytes(pdfPath);

        MarkerProperties properties = new MarkerProperties();
        properties.setBaseUrl(MARKER_BASE_URL);
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setReadTimeout(Duration.ofSeconds(900));
        MarkerClient markerClient = new MarkerHttpClient(properties);

        Assumptions.assumeTrue(markerClient.isAlive(),
                "Marker API 不可达（SSH Tunnel 未开启），跳过真实论文 QA");

        CanonicalDocumentPipeline pipeline = buildPipeline(markerClient);

        long start = System.currentTimeMillis();
        PipelineResult result = pipeline.process("real-paper-1", pdfBytes, "real-paper.pdf");
        long totalMs = System.currentTimeMillis() - start;

        DocumentQualityMetrics metrics = result.metrics();
        System.out.println("=========== Real Paper QA (skill §32) ===========");
        System.out.println("marker elapsedSeconds : " + result.markerElapsedSeconds());
        System.out.println("total durationMs      : " + totalMs);
        System.out.println("valid                 : " + result.valid());
        System.out.println("page count (pageEnd)  : " + maxPage(result));
        System.out.println("sectionCount          : " + metrics.sectionCount());
        System.out.println("paragraphCount        : " + metrics.paragraphCount());
        System.out.println("figureCount           : " + metrics.figureCount());
        System.out.println("tableCount            : " + metrics.tableCount());
        System.out.println("referenceCount        : " + metrics.referenceCount());
        System.out.println("headerFooterRemaining : " + metrics.headerFooterRemaining());
        System.out.println("unnamedSectionCount   : " + metrics.unnamedSectionCount());
        System.out.println("emptySectionCount     : " + metrics.emptySectionCount());
        System.out.println("base64RemainingCount  : " + metrics.base64RemainingCount());
        System.out.println("crossPageMergeCount   : " + metrics.crossPageMergeCount());
        System.out.println("repairCount           : " + metrics.repairCount());
        System.out.println("warningCount          : " + result.warnings().size());
        System.out.println("errors                : " + result.cleaning().validation().errors());
        System.out.println("title                 : " + result.document().getMetadata().getTitle());
        System.out.println("=================================================");

        // 基本不变量（不硬编码具体值，容忍不同论文）
        assertThat(result.document().getSections()).isNotEmpty();
        assertThat(metrics.base64RemainingCount()).isZero();
        assertThat(metrics.headerFooterRemaining()).isZero();
        assertThat(result.valid()).isTrue();
    }

    /**
     * 批量真实论文 QA：处理 -Dmarker.qa.dir=<dir> 下所有 PDF，逐篇输出 skill §32 指标。
     */
    @Test
    void realPaperBatch_directory_reportsMetricsPerPaper() throws Exception {
        String dirProperty = System.getProperty("marker.qa.dir");
        Assumptions.assumeTrue(dirProperty != null && !dirProperty.isBlank(),
                "未提供 -Dmarker.qa.dir，跳过批量 QA");
        Path dir = Path.of(dirProperty);
        Assumptions.assumeTrue(Files.isDirectory(dir), "目录不存在: " + dir);

        List<Path> pdfs;
        try (var stream = Files.list(dir)) {
            pdfs = stream.filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                    .sorted()
                    .toList();
        }
        Assumptions.assumeTrue(!pdfs.isEmpty(), "目录中没有 PDF 文件");

        MarkerProperties properties = new MarkerProperties();
        properties.setBaseUrl(MARKER_BASE_URL);
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setReadTimeout(Duration.ofSeconds(900));
        MarkerClient markerClient = new MarkerHttpClient(properties);
        Assumptions.assumeTrue(markerClient.isAlive(),
                "Marker API 不可达（SSH Tunnel 未开启），跳过批量 QA");

        CanonicalDocumentPipeline pipeline = buildPipeline(markerClient);

        int success = 0;
        List<String> failures = new ArrayList<>();
        System.out.println("=========== Batch Real Paper QA (skill §32) ===========");
        for (Path pdf : pdfs) {
            String name = pdf.getFileName().toString();
            try {
                long start = System.currentTimeMillis();
                PipelineResult result = pipeline.process(name, Files.readAllBytes(pdf), name);
                long ms = System.currentTimeMillis() - start;
                DocumentQualityMetrics m = result.metrics();
                System.out.printf(
                        "[OK]   %-30s %7dms | sections=%-3d paragraphs=%-4d figures=%-3d tables=%-3d "
                                + "refs=%-3d hfLeft=%-2d base64=%-2d crossPage=%-2d repairs=%-2d "
                                + "unnamed=%-2d warn=%-2d valid=%s%n",
                        name, ms, m.sectionCount(), m.paragraphCount(), m.figureCount(),
                        m.tableCount(), m.referenceCount(), m.headerFooterRemaining(),
                        m.base64RemainingCount(), m.crossPageMergeCount(), m.repairCount(),
                        m.unnamedSectionCount(), result.warnings().size(), result.valid());
                success++;
            } catch (Exception e) {
                System.out.printf("[FAIL] %-30s %s%n", name, e.getMessage());
                failures.add(name + ": " + e.getMessage());
            }
        }
        System.out.println("======================================================");
        System.out.println("total=" + pdfs.size() + ", success=" + success + ", failed=" + failures.size());
        failures.forEach(f -> System.out.println("  FAILED -> " + f));

        assertThat(success).isGreaterThan(0);
    }

    /**
     * 保存产物供人工核对：原始 Marker JSON + 清洗后 Canonical 文档 + 摘要。
     * <p>
     * 输出到 {@code src/test/output/}：
     * <pre>
     * src/test/output/marker-raw/{paper}.json      Marker 原始输出
     * src/test/output/canonical/{paper}.json       清洗后 Canonical 文档
     * src/test/output/summary.txt                  逐篇指标 + section 树
     * </pre>
     * 运行：{@code mvn test -Dtest=RealPaperQaTest#realPaperBatch_savesOutputsForReview -Dmarker.qa.dir=src/test/resources/pdfs}
     */
    @Test
    void realPaperBatch_savesOutputsForReview() throws Exception {
        String dirProperty = System.getProperty("marker.qa.dir");
        Assumptions.assumeTrue(dirProperty != null && !dirProperty.isBlank(),
                "未提供 -Dmarker.qa.dir，跳过");
        Path dir = Path.of(dirProperty);
        Assumptions.assumeTrue(Files.isDirectory(dir), "目录不存在: " + dir);

        List<Path> pdfs;
        try (var stream = Files.list(dir)) {
            pdfs = stream.filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                    .sorted()
                    .toList();
        }
        Assumptions.assumeTrue(!pdfs.isEmpty(), "目录中没有 PDF 文件");

        MarkerProperties properties = new MarkerProperties();
        properties.setBaseUrl(MARKER_BASE_URL);
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setReadTimeout(Duration.ofSeconds(900));
        MarkerClient markerClient = new MarkerHttpClient(properties);
        Assumptions.assumeTrue(markerClient.isAlive(),
                "Marker API 不可达（SSH Tunnel 未开启），跳过");

        CanonicalDocumentPipeline pipeline = buildPipeline(markerClient);

        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        Path outRoot = Path.of("src/test/output");
        Path rawDir = outRoot.resolve("marker-raw");
        Path canonicalDir = outRoot.resolve("canonical");
        Files.createDirectories(rawDir);
        Files.createDirectories(canonicalDir);

        StringBuilder summary = new StringBuilder();
        summary.append("=== Marker 解析 & 清洗 产物核对摘要 ===\n\n");

        for (Path pdf : pdfs) {
            String name = pdf.getFileName().toString();
            String stem = name.toLowerCase().endsWith(".pdf")
                    ? name.substring(0, name.length() - 4) : name;
            String safeStem = stem.replaceAll("[^a-zA-Z0-9._-]", "_");
            try {
                PipelineResult result = pipeline.process(name, Files.readAllBytes(pdf), name);

                // 1. 原始 Marker JSON
                Files.writeString(rawDir.resolve(safeStem + ".json"),
                        json.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(result.rawDocument()));

                // 2. 清洗后 Canonical 文档
                Files.writeString(canonicalDir.resolve(safeStem + ".json"),
                        json.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(result.document()));

                // 3. 摘要
                DocumentQualityMetrics m = result.metrics();
                summary.append("### ").append(name).append('\n');
                summary.append("title       : ").append(result.document().getMetadata().getTitle()).append('\n');
                summary.append("abstract    : ")
                        .append(preview(result.document().getAbstractText(), 220)).append('\n');
                summary.append("metrics     : sections=").append(m.sectionCount())
                        .append(" paragraphs=").append(m.paragraphCount())
                        .append(" figures=").append(m.figureCount())
                        .append(" tables=").append(m.tableCount())
                        .append(" refs=").append(m.referenceCount())
                        .append(" hfLeft=").append(m.headerFooterRemaining())
                        .append(" base64=").append(m.base64RemainingCount())
                        .append(" crossPage=").append(m.crossPageMergeCount())
                        .append(" repairs=").append(m.repairCount()).append('\n');
                summary.append("valid       : ").append(result.valid())
                        .append("   warnings=").append(result.warnings().size()).append('\n');
                summary.append("section 树  :\n");
                appendSectionTitles(summary, result.document().getSections(), 0);
                summary.append('\n');
            } catch (Exception e) {
                summary.append("### ").append(name).append("   [FAILED] ")
                        .append(e.getMessage()).append("\n\n");
            }
        }

        Files.writeString(outRoot.resolve("summary.txt"), summary.toString());
        System.out.println("产物已写入: " + outRoot.toAbsolutePath());
        System.out.println(summary);
    }

    private void appendSectionTitles(StringBuilder sb, List<Section> sections, int depth) {
        if (sections == null) {
            return;
        }
        for (Section section : sections) {
            sb.append("  ".repeat(depth + 1)).append("- ")
                    .append(section.getTitle() == null ? "(untitled)" : section.getTitle())
                    .append("  [blocks=")
                    .append(section.getBlocks() == null ? 0 : section.getBlocks().size())
                    .append("]\n");
            appendSectionTitles(sb, section.getChildren(), depth + 1);
        }
    }

    private String preview(String text, int max) {
        if (text == null) {
            return "null";
        }
        String t = text.replaceAll("\\s+", " ");
        return t.length() > max ? t.substring(0, max) + "..." : t;
    }

    /**
     * 单篇保存产物供人工核对：原始 Marker JSON + 清洗后 Canonical 文档 + 单篇摘要。
     * <p>
     * 输出到 {@code src/test/output/}：
     * <pre>
     * src/test/output/marker-raw/{paper}.json     Marker 原始输出
     * src/test/output/canonical/{paper}.json      清洗后 Canonical 文档
     * src/test/output/summary-{paper}.txt         单篇摘要（指标 + section 树）
     * </pre>
     * 运行：{@code mvn test -Dtest=RealPaperQaTest#realPaper_savesOutputForReview -Dmarker.qa.pdf=<path>}
     */
    @Test
    void realPaper_savesOutputForReview() throws Exception {
        String pdfProperty = System.getProperty("marker.qa.pdf");
        Assumptions.assumeTrue(pdfProperty != null && !pdfProperty.isBlank(),
                "未提供 -Dmarker.qa.pdf，跳过");
        Path pdfPath = Path.of(pdfProperty);
        Assumptions.assumeTrue(Files.exists(pdfPath), "PDF 不存在: " + pdfPath);

        MarkerProperties properties = new MarkerProperties();
        properties.setBaseUrl(MARKER_BASE_URL);
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setReadTimeout(Duration.ofSeconds(900));
        MarkerClient markerClient = new MarkerHttpClient(properties);
        Assumptions.assumeTrue(markerClient.isAlive(),
                "Marker API 不可达（SSH Tunnel 未开启），跳过");

        CanonicalDocumentPipeline pipeline = buildPipeline(markerClient);

        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        Path outRoot = Path.of("src/test/output");
        Path rawDir = outRoot.resolve("marker-raw");
        Path canonicalDir = outRoot.resolve("canonical");
        Files.createDirectories(rawDir);
        Files.createDirectories(canonicalDir);

        String name = pdfPath.getFileName().toString();
        String stem = name.toLowerCase().endsWith(".pdf")
                ? name.substring(0, name.length() - 4) : name;
        String safeStem = stem.replaceAll("[^a-zA-Z0-9._-]", "_");

        PipelineResult result = pipeline.process(name, Files.readAllBytes(pdfPath), name);

        // 1. 原始 Marker JSON
        Files.writeString(rawDir.resolve(safeStem + ".json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(result.rawDocument()));

        // 2. 清洗后 Canonical 文档
        Files.writeString(canonicalDir.resolve(safeStem + ".json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(result.document()));

        // 3. 单篇摘要
        DocumentQualityMetrics m = result.metrics();
        StringBuilder summary = new StringBuilder();
        summary.append("### ").append(name).append('\n');
        summary.append("title       : ").append(result.document().getMetadata().getTitle()).append('\n');
        summary.append("abstract    : ")
                .append(preview(result.document().getAbstractText(), 220)).append('\n');
        summary.append("metrics     : sections=").append(m.sectionCount())
                .append(" paragraphs=").append(m.paragraphCount())
                .append(" figures=").append(m.figureCount())
                .append(" tables=").append(m.tableCount())
                .append(" refs=").append(m.referenceCount())
                .append(" hfLeft=").append(m.headerFooterRemaining())
                .append(" base64=").append(m.base64RemainingCount())
                .append(" crossPage=").append(m.crossPageMergeCount())
                .append(" repairs=").append(m.repairCount()).append('\n');
        summary.append("valid       : ").append(result.valid())
                .append("   warnings=").append(result.warnings().size()).append('\n');
        summary.append("section 树  :\n");
        appendSectionTitles(summary, result.document().getSections(), 0);

        Files.writeString(outRoot.resolve("summary-" + safeStem + ".txt"), summary.toString());
        System.out.println("产物已写入: " + outRoot.toAbsolutePath());
        System.out.println("  marker-raw/" + safeStem + ".json");
        System.out.println("  canonical/" + safeStem + ".json");
        System.out.println("  summary-" + safeStem + ".txt");
        System.out.println(summary);
    }

    /**
     * 直接对<b>已存在的 Marker 原始 JSON</b> 跑清洗（不调用 Marker API），结果写入 {@code src/test/output/canonical}。
     * <p>
     * 运行：{@code mvn test -Dtest=RealPaperQaTest#rawJson_cleanOnly_savesOutput -Dmarker.qa.raw=<raw json 路径>}
     */
    @Test
    void rawJson_cleanOnly_savesOutput() throws Exception {
        String rawProperty = System.getProperty("marker.qa.raw");
        Assumptions.assumeTrue(rawProperty != null && !rawProperty.isBlank(),
                "未提供 -Dmarker.qa.raw，跳过");
        Path rawPath = Path.of(rawProperty);
        Assumptions.assumeTrue(Files.exists(rawPath), "文件不存在: " + rawPath);

        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode raw = json.readTree(Files.readString(rawPath));

        // 只跑 Parser + Repair + Cleaner（无需 Marker API）
        MarkerTextExtractor textExtractor = new MarkerTextExtractor();
        MarkerDocumentParser parser = new MarkerDocumentParser(
                json,
                new MarkerBlockMapper(textExtractor),
                new MarkerSectionBuilder(textExtractor),
                new LocalFigureImageStorage("target/figure-images"));
        ReadingOrderRepair repair = new ReadingOrderRepair(
                new ReadingOrderRepairConfig(), new FigureAssociatedTextDetector());
        PaperCleaner cleaner = new PaperCleaner(
                new com.example.ragdemo.document.cleaner.FrontMatterPreScan(),
                new HeaderFooterCleaner(),
                new FrontMatterCleaner(),
                new AbstractRecovery(),
                new CaptionRecovery(),
                new ReferenceExtractor(new com.example.ragdemo.document.cleaner.ReferenceItemSplitter()),
                new BackMatterClassifier(),
                new SectionHierarchyNormalizer(), new com.example.ragdemo.document.cleaner.SectionHierarchyRepair(), new com.example.ragdemo.document.cleaner.MajorAnchorReparenting(),
                new com.example.ragdemo.document.cleaner.FloatBlockSectionReassigner(),
                new com.example.ragdemo.document.cleaner.EmptyContentCleaner(),
                new CanonicalDocumentValidator());

        String fileName = rawPath.getFileName().toString();
        AcademicDocument document = parser.parse(fileName, raw);
        List<ReadingOrderRepairAudit> audits = repair.repair(document);
        var cleaning = cleaner.clean(document, audits.size());

        // 写入 src/test/output/canonical
        Path canonicalDir = Path.of("src/test/output/canonical");
        Files.createDirectories(canonicalDir);
        String stem = fileName.replaceAll("\\.json$", "");
        String safeStem = stem.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path outFile = canonicalDir.resolve(safeStem + ".json");
        Files.writeString(outFile,
                json.writerWithDefaultPrettyPrinter().writeValueAsString(cleaning.document()));

        DocumentQualityMetrics m = cleaning.validation().metrics();
        System.out.println("=========== 清洗测试（raw JSON → canonical）===========");
        System.out.println("input  : " + rawPath.toAbsolutePath());
        System.out.println("output : " + outFile.toAbsolutePath());
        System.out.println("title       : " + cleaning.document().getMetadata().getTitle());
        System.out.println("doi         : " + cleaning.document().getMetadata().getDoi());
        System.out.println("authorCount : " + cleaning.document().getMetadata().getAuthors().size());
        System.out.println("keywords    : " + cleaning.document().getMetadata().getKeywords());
        System.out.println("affiliations: " + cleaning.document().getMetadata().getAffiliations().size());
        System.out.println("abstract    : "
                + preview(cleaning.document().getAbstractText(), 200));
        System.out.println("metrics     : sections=" + m.sectionCount()
                + " paragraphs=" + m.paragraphCount()
                + " figures=" + m.figureCount()
                + " tables=" + m.tableCount()
                + " refs=" + m.referenceCount()
                + " hfLeft=" + m.headerFooterRemaining()
                + " base64=" + m.base64RemainingCount());
        System.out.println("valid       : " + cleaning.valid()
                + "  warnings=" + cleaning.warnings().size());
        cleaning.warnings().forEach(w -> System.out.println("  warn: " + w));
        System.out.println("section 树  :");
        StringBuilder tree = new StringBuilder();
        appendSectionTitles(tree, cleaning.document().getSections(), 0);
        System.out.println(tree);
        System.out.println("====================================================");

        assertThat(cleaning.document().getSections()).isNotEmpty();
    }

    /**
     * 从 <b>已保存的 Marker 原始 JSON 目录</b>批量跑清洗（不调用 Marker API），
     * 结果写入 {@code src/test/output/canonical}。
     * <p>
     * 适用：清洗层改动后的回归——直接用 marker-raw 里的原始 JSON 复跑清洗，避免重复调用 Marker。
     * <p>
     * 运行：{@code mvn test -Dtest=RealPaperQaTest#rawJsonDir_cleanOnly_savesOutput -Dmarker.qa.rawDir=src/test/output/marker-raw}
     */
    @Test
    void rawJsonDir_cleanOnly_savesOutput() throws Exception {
        String dirProperty = System.getProperty("marker.qa.rawDir");
        Assumptions.assumeTrue(dirProperty != null && !dirProperty.isBlank(),
                "未提供 -Dmarker.qa.rawDir，跳过");
        Path dir = Path.of(dirProperty);
        Assumptions.assumeTrue(Files.isDirectory(dir), "目录不存在: " + dir);

        List<Path> rawFiles;
        try (var stream = Files.list(dir)) {
            rawFiles = stream.filter(p -> p.toString().toLowerCase().endsWith(".json"))
                    .sorted().toList();
        }
        Assumptions.assumeTrue(!rawFiles.isEmpty(), "目录中没有 JSON 文件");

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Path canonicalDir = Path.of("src/test/output/canonical");
        Files.createDirectories(canonicalDir);

        System.out.println("=========== 从 marker-raw 批量清洗 ===========");
        for (Path rawPath : rawFiles) {
            String name = rawPath.getFileName().toString();
            try {
                JsonNode raw = mapper.readTree(Files.readString(rawPath));
                MarkerTextExtractor textExtractor = new MarkerTextExtractor();
                MarkerDocumentParser parser = new MarkerDocumentParser(mapper,
                        new MarkerBlockMapper(textExtractor),
                        new MarkerSectionBuilder(textExtractor),
                        new LocalFigureImageStorage("target/figure-images"));
                ReadingOrderRepair repair = new ReadingOrderRepair(
                        new ReadingOrderRepairConfig(), new FigureAssociatedTextDetector());
                PaperCleaner cleaner = new PaperCleaner(
                        new com.example.ragdemo.document.cleaner.FrontMatterPreScan(),
                        new HeaderFooterCleaner(),
                        new FrontMatterCleaner(),
                        new AbstractRecovery(),
                        new CaptionRecovery(),
                        new ReferenceExtractor(new com.example.ragdemo.document.cleaner.ReferenceItemSplitter()),
                        new BackMatterClassifier(),
                        new SectionHierarchyNormalizer(), new com.example.ragdemo.document.cleaner.SectionHierarchyRepair(), new com.example.ragdemo.document.cleaner.MajorAnchorReparenting(),
                        new com.example.ragdemo.document.cleaner.FloatBlockSectionReassigner(),
                        new com.example.ragdemo.document.cleaner.EmptyContentCleaner(),
                        new CanonicalDocumentValidator());

                AcademicDocument document = parser.parse(name, raw);
                List<ReadingOrderRepairAudit> audits = repair.repair(document);
                var cleaning = cleaner.clean(document, audits.size());

                String safeStem = name.replaceAll("\\.json$", "").replaceAll("[^a-zA-Z0-9._-]", "_");
                Files.writeString(canonicalDir.resolve(safeStem + ".json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(cleaning.document()));

                DocumentQualityMetrics m = cleaning.validation().metrics();
                System.out.printf(
                        "[OK]   %-40s sections=%-3d paras=%-4d fig=%-2d tab=%-2d refs=%-3d valid=%s warn=%d%n",
                        name.substring(0, Math.min(40, name.length())), m.sectionCount(),
                        m.paragraphCount(), m.figureCount(), m.tableCount(), m.referenceCount(),
                        cleaning.valid(), cleaning.warnings().size());
            } catch (Exception e) {
                System.out.printf("[FAIL] %-40s %s%n",
                        name.substring(0, Math.min(40, name.length())), e.getMessage());
            }
        }
        System.out.println("==============================================");
    }

    /**
     * Phase 4 — 从 <b>已保存的 canonical JSON 目录</b>批量跑 SectionAwareChunker 并输出 Chunk QA（skill §25~§27）。
     * <p>
     * 不需要 Marker API / PDF：直接反序列化 canonical 产物，因此可作为 Chunker 改动后的快速回归。
     * <p>
     * 运行：{@code mvn test -Dtest=RealPaperQaTest#canonicalJson_chunkQa}（可选 {@code -Dchunker.qa.canonicalDir=<dir>}）
     */
    @Test
    void canonicalJson_chunkQa() throws Exception {
        String dirProperty = System.getProperty("chunker.qa.canonicalDir");
        Path dir = Path.of(dirProperty != null && !dirProperty.isBlank()
                ? dirProperty
                : "src/test/output/canonical");
        Assumptions.assumeTrue(Files.isDirectory(dir), "目录不存在: " + dir);

        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.filter(p -> p.toString().toLowerCase().endsWith(".json")).sorted().toList();
        }
        Assumptions.assumeTrue(!files.isEmpty(), "目录中没有 canonical JSON");

        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        SectionAwareChunker chunker = buildChunker(new RagProperties());

        System.out.println("=========== SectionAwareChunker 真实论文 Chunk QA ===========");
        for (Path file : files) {
            AcademicDocument document = json.readValue(Files.readString(file), AcademicDocument.class);
            ChunkingResult result = chunker.chunk(document);
            ChunkQualityMetrics m = result.metrics();

            System.out.println("[OK] " + file.getFileName());
            System.out.println("     chunks=" + m.chunkCount()
                    + " text=" + m.textChunkCount()
                    + " list=" + m.listChunkCount()
                    + " fig=" + m.figureChunkCount()
                    + " tab=" + m.tableChunkCount()
                    + " cap=" + m.captionChunkCount()
                    + " mixed=" + m.mixedChunkCount()
                    + " abstract=" + m.abstractChunkCount()
                    + " refs=" + m.referenceChunkCount());
            System.out.println("     tokens avg=" + String.format("%.1f", m.avgTokens())
                    + " p50=" + m.p50Tokens() + " p90=" + m.p90Tokens()
                    + " max=" + m.maxTokens() + " min=" + m.minTokens());
            System.out.println("     oversized=" + m.oversizedChunkCount()
                    + " undersized=" + m.undersizedChunkCount()
                    + " empty=" + m.emptyChunkCount()
                    + " noise=" + m.noiseChunkCount()
                    + " crossSection=" + m.crossSectionChunkCount()
                    + " splitParagraph=" + m.splitParagraphCount());
            System.out.println("     skippedFigure=" + m.skippedFigureCount()
                    + " skippedTable=" + m.skippedTableCount()
                    + " skippedReference=" + m.skippedReferenceCount()
                    + " referenceRatio=" + String.format("%.2f", m.referenceChunkRatio())
                    + " sectionCoverage=" + String.format("%.2f", m.sectionCoverageRatio()));
            result.warnings().forEach(w -> System.out.println("     warn: " + w));

            // 硬不变量：任何真实论文上都必须成立
            assertThat(m.emptyChunkCount()).as("存在空 chunk").isZero();
            assertThat(m.noiseChunkCount()).as("NOISE 进入了 chunk").isZero();
            assertThat(m.crossSectionChunkCount()).as("chunk 跨了 Section").isZero();
            result.chunks().forEach(chunk -> assertThat(chunk.getContent()).isNotBlank());
        }

        Path target = files.stream()
                .filter(f -> f.getFileName().toString().contains("Toward_robust"))
                .findFirst().orElse(null);
        if (target != null) {
            reportTargetPaperCases(target, json, chunker);
        }
        System.out.println("============================================================");
    }

    /**
     * 抽样导出目标论文的 chunk，供<b>人工检查</b>分块质量。
     * <p>
     * 抽样策略：每种 ChunkType 定点取 1 个 + §27 Case1 跨页段落 + 最小/最大 chunk + 最深嵌套 sectionPath，
     * 其余用<b>固定种子(42)</b>随机补齐，因此结果可复现。
     * <p>
     * 运行：{@code mvn test -Dtest=RealPaperQaTest#canonicalJson_chunkSampleForReview -Dchunker.qa.sample=true}
     */
    @Test
    void canonicalJson_chunkSampleForReview() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getProperty("chunker.qa.sample")),
                "未提供 -Dchunker.qa.sample=true，跳过");

        Path dir = Path.of("src/test/output/canonical");
        Assumptions.assumeTrue(Files.isDirectory(dir), "canonical 目录不存在: " + dir);

        Path target;
        try (var stream = Files.list(dir)) {
            target = stream.filter(p -> p.toString().contains("Toward_robust")).findFirst().orElse(null);
        }
        Assumptions.assumeTrue(target != null, "未找到目标论文 canonical JSON");

        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        AcademicDocument document = json.readValue(Files.readString(target), AcademicDocument.class);
        ChunkingResult result = buildChunker(new RagProperties()).chunk(document);

        List<AcademicChunk> samples = selectChunkSamples(result.chunks(), 18);
        Path out = Path.of("src/test/output/reports", "目标论文_chunk抽样_人工检查.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, renderChunkSampleReport(document, result, samples), StandardCharsets.UTF_8);

        System.out.println("chunk 抽样报告已写入: " + out.toAbsolutePath()
                + " （" + samples.size() + " / " + result.chunkCount() + " 个 chunk）");
        assertThat(samples).isNotEmpty();
    }

    /**
     * 抽样：定点覆盖 → 固定种子随机补齐。
     * 定点项 = 每类型首个 + Case1 跨页块 + 最小/最大 chunk + 最深 sectionPath 的 TEXT。
     */
    private List<AcademicChunk> selectChunkSamples(List<AcademicChunk> chunks, int limit) {
        Map<String, AcademicChunk> picked = new LinkedHashMap<>();

        // 定点 1：每种 ChunkType 的第一个
        for (ChunkType type : ChunkType.values()) {
            chunks.stream().filter(chunk -> chunk.getChunkType() == type).findFirst()
                    .ifPresent(chunk -> picked.put(chunk.getChunkId(), chunk));
        }
        // 定点 2：§27 Case1 跨页 repaired paragraph
        chunks.stream()
                .filter(chunk -> chunk.getSourceBlockIds().contains("/page/3/Text/11")
                        && chunk.getSourceBlockIds().contains("/page/4/Text/1"))
                .findFirst().ifPresent(chunk -> picked.put(chunk.getChunkId(), chunk));
        // 定点 3：最小 / 最大 chunk
        chunks.stream().min(Comparator.comparingInt(AcademicChunk::getTokenCount))
                .ifPresent(chunk -> picked.put(chunk.getChunkId(), chunk));
        chunks.stream().max(Comparator.comparingInt(AcademicChunk::getTokenCount))
                .ifPresent(chunk -> picked.put(chunk.getChunkId(), chunk));
        // 定点 4：嵌套最深的 TEXT chunk（验证 sectionPath 归属）
        chunks.stream()
                .filter(chunk -> chunk.getChunkType() == ChunkType.TEXT)
                .filter(chunk -> chunk.getSectionPath() != null && chunk.getSectionPath().size() >= 3)
                .findFirst().ifPresent(chunk -> picked.put(chunk.getChunkId(), chunk));

        // 随机补齐（固定种子 → 可复现）。
        // REFERENCE 已由"定点 1"覆盖 1 个：它占全部 chunk 的 ~52%，若参与随机会严重过采样，
        // 挤掉正文类型的检查名额，因此随机池排除 REFERENCE。
        List<AcademicChunk> remaining = new ArrayList<>(chunks.stream()
                .filter(chunk -> !picked.containsKey(chunk.getChunkId()))
                .filter(chunk -> chunk.getChunkType() != ChunkType.REFERENCE)
                .toList());
        Collections.shuffle(remaining, new Random(42));
        for (AcademicChunk chunk : remaining) {
            if (picked.size() >= limit) {
                break;
            }
            picked.put(chunk.getChunkId(), chunk);
        }
        return picked.values().stream()
                .sorted(Comparator.comparingInt(AcademicChunk::getOrder))
                .toList();
    }

    /** 渲染人工检查用的抽样报告（Markdown）。 */
    private String renderChunkSampleReport(AcademicDocument document, ChunkingResult result,
            List<AcademicChunk> samples) {
        ChunkQualityMetrics m = result.metrics();
        StringBuilder sb = new StringBuilder();

        sb.append("# 目标论文 chunk 抽样（人工检查用）\n\n");
        sb.append("> 论文：").append(document.getMetadata().getTitle()).append("\n");
        sb.append("> documentId：`").append(document.getDocumentId()).append("`\n");
        sb.append("> 抽样数：**").append(samples.size()).append(" / ").append(result.chunkCount())
                .append("**（定点覆盖 + 固定种子 42 随机补齐，结果可复现）\n");
        sb.append("> 复现命令：`mvn test -Dtest=RealPaperQaTest#canonicalJson_chunkSampleForReview -Dchunker.qa.sample=true`\n\n");

        sb.append("## 全局指标\n\n```text\n");
        sb.append("chunkCount  = ").append(m.chunkCount()).append('\n');
        sb.append("tokens      = avg ").append(String.format("%.1f", m.avgTokens()))
                .append(" / p50 ").append(m.p50Tokens())
                .append(" / p90 ").append(m.p90Tokens())
                .append(" / max ").append(m.maxTokens())
                .append(" / min ").append(m.minTokens()).append('\n');
        sb.append("分类        = text ").append(m.textChunkCount())
                .append(" / list ").append(m.listChunkCount())
                .append(" / figure ").append(m.figureChunkCount())
                .append(" / table ").append(m.tableChunkCount())
                .append(" / formula ").append(m.formulaChunkCount())
                .append(" / footnote ").append(m.footnoteChunkCount())
                .append(" / caption ").append(m.captionChunkCount())
                .append(" / mixed ").append(m.mixedChunkCount())
                .append(" / abstract ").append(m.abstractChunkCount())
                .append(" / reference ").append(m.referenceChunkCount()).append('\n');
        sb.append("异常        = oversized ").append(m.oversizedChunkCount())
                .append(" / undersized ").append(m.undersizedChunkCount())
                .append(" / empty ").append(m.emptyChunkCount())
                .append(" / noise ").append(m.noiseChunkCount())
                .append(" / crossSection ").append(m.crossSectionChunkCount())
                .append(" / splitParagraph ").append(m.splitParagraphCount()).append('\n');
        sb.append("跳过        = figure ").append(m.skippedFigureCount())
                .append(" / table ").append(m.skippedTableCount())
                .append(" / reference ").append(m.skippedReferenceCount()).append('\n');
        sb.append("referenceChunkRatio = ").append(String.format("%.2f", m.referenceChunkRatio()))
                .append("   sectionCoverageRatio = ").append(String.format("%.2f", m.sectionCoverageRatio()))
                .append('\n');
        sb.append("```\n\n");

        sb.append("## 字段说明\n\n");
        sb.append("- `embeddingText` 即**送入 embedding 的完整文本**：前两行是 `Document:` / `Section:` 上下文前缀，"
                + "其后与 `content` 原文一致（无前缀时二者相同）。\n");
        sb.append("- `tokenCount` **只统计 `content`**（不含前缀），因此实际喂给模型的 token 会略多于该值。\n");
        sb.append("- `sourceBlockIds` 可回溯至 Marker 原始 block（如 `/page/3/Text/11`），用于定位原文与页码。\n");
        sb.append("- `overlapBlockIds` 仅在 `rag.chunker.overlap-enabled=true` 时非空（本次为默认关闭）。\n\n");

        sb.append("## 各类型检查要点\n\n");
        sb.append("| 类型 | 检查要点 |\n|---|---|\n");
        sb.append("| TEXT | 自然段是否被拆断？跨页段是否为完整逻辑单元？`sectionPath` 归属是否正确？ |\n");
        sb.append("| LIST | 所有 item 是否完整？有无被误当普通段落？ |\n");
        sb.append("| MIXED | 短前置说明与紧随列表是否成对出现？ |\n");
        sb.append("| FIGURE | caption 是否完整？有无 `imageKey`/base64 泄漏？ |\n");
        sb.append("| TABLE | caption + 表头 + 数据行是否齐全？有无 html 泄漏？ |\n");
        sb.append("| CAPTION | orphan caption 是否被保留、内容是否有意义？ |\n");
        sb.append("| REFERENCE | 是否只含单条文献？有无 title/doi 缺失导致的空壳？ |\n");
        sb.append("| ABSTRACT | 是否为完整摘要？ |\n\n");
        sb.append("---\n\n");

        for (int i = 0; i < samples.size(); i++) {
            AcademicChunk chunk = samples.get(i);
            sb.append("## [").append(i + 1).append("] ").append(chunk.getChunkType())
                    .append(" — `").append(chunk.getChunkId()).append("`\n\n");
            sb.append("| 字段 | 值 |\n|---|---|\n");
            sb.append("| order | ").append(chunk.getOrder()).append(" |\n");
            sb.append("| tokenCount / charCount | ").append(chunk.getTokenCount())
                    .append(" / ").append(chunk.getCharCount()).append(" |\n");
            sb.append("| 页码 | ").append(chunk.getStartPage()).append(" - ")
                    .append(chunk.getEndPage()).append(" |\n");
            sb.append("| sectionLevel | ").append(chunk.getSectionLevel()).append(" |\n");
            sb.append("| sectionPath | ")
                    .append(chunk.getSectionPath() == null || chunk.getSectionPath().isEmpty()
                            ? "（不属于任何 Section）"
                            : String.join(" > ", chunk.getSectionPath()))
                    .append(" |\n");
            sb.append("| blockTypes | ").append(chunk.getBlockTypes()).append(" |\n");
            sb.append("| sourceBlockIds | ").append(String.join(", ", chunk.getSourceBlockIds()))
                    .append(" |\n");
            sb.append("| overlapBlockIds | ")
                    .append(chunk.getOverlapBlockIds().isEmpty() ? "（无）"
                            : String.join(", ", chunk.getOverlapBlockIds()))
                    .append(" |\n");
            sb.append("| oversized / undersized | ").append(chunk.isOversized())
                    .append(" / ").append(chunk.isUndersized()).append(" |\n");
            sb.append("| contentHash | `").append(chunk.getContentHash()).append("` |\n");
            if (!chunk.getReferenceIds().isEmpty()) {
                sb.append("| referenceIds | ").append(String.join(", ", chunk.getReferenceIds()))
                        .append(" |\n");
            }
            sb.append('\n');
            sb.append("**embeddingText**\n\n```text\n").append(chunk.getEmbeddingText())
                    .append("\n```\n\n");
            sb.append("---\n\n");
        }
        return sb.toString();
    }

    /**
     * 导出 Figure/Table 浮动归属修复的 audit 记录（skill §8），供<b>人工抽查移动是否合理</b>。
     * <p>
     * 运行：{@code mvn test -Dtest=RealPaperQaTest#floatAudit_forReview -Dchunker.qa.floatAudit=true}
     */
    @Test
    void floatAudit_forReview() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getProperty("chunker.qa.floatAudit")),
                "未提供 -Dchunker.qa.floatAudit=true，跳过");
        Path dir = Path.of("src/test/output/marker-raw");
        Assumptions.assumeTrue(Files.isDirectory(dir), "marker-raw 目录不存在: " + dir);

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        List<Path> rawFiles;
        try (var stream = Files.list(dir)) {
            rawFiles = stream.filter(p -> p.toString().toLowerCase().endsWith(".json")).sorted().toList();
        }
        Assumptions.assumeTrue(!rawFiles.isEmpty(), "marker-raw 中没有 JSON");

        StringBuilder sb = new StringBuilder();
        sb.append("# Figure/Table 浮动归属修复 audit 抽查\n\n");
        sb.append("> 用途：人工确认每次重挂是否合理（skill §8 可追踪性）\n");
        sb.append("> 复现：`mvn test -Dtest=RealPaperQaTest#floatAudit_forReview -Dchunker.qa.floatAudit=true`\n\n");
        sb.append("## 判定规则\n\n");
        sb.append("- 编号来自 `label` 优先、`caption` 兜底；**类型+编号完全匹配**才可能移动\n");
        sb.append("- 定义性引用（`shown in` / `see Table` …）优先；否则取浮动块**之前最近**的匹配引用\n");
        sb.append("- 门槛：引用 Section ≠ 当前 Section **且** 页距 ≤ 3（无页码时 order 距 ≤ 120）\n");
        sb.append("- 无引用 / 超距 / 不确定 → **不移动**（技能明确禁止为归零计数强行选择）\n\n");
        sb.append("## 抽查要点\n\n");
        sb.append("1. `pageDistance=0` 且 `orderDistance` 很小但跨了 Section → 典型 float 错位，应移\n");
        sb.append("2. 前文某段\"定义性引用\"了属于后文的图 → 需确认是否被贪心拉走（风险模式）\n");
        sb.append("3. `reason=nearest-preceding-reference` 的条目要重点看：是否只是\"引用\"而非\"归属\"\n\n");
        sb.append("---\n\n");

        int totalMoved = 0;
        int totalBlocks = 0;
        for (Path rawPath : rawFiles) {
            String name = rawPath.getFileName().toString();
            JsonNode raw = mapper.readTree(Files.readString(rawPath));
            CleaningResult cleaning = cleanFromRawJson(mapper, raw, name);

            List<FloatBlockSectionReassigner.Audit> audits = cleaning.floatAudits() == null
                    ? List.of()
                    : cleaning.floatAudits();
            totalMoved += audits.size();
            totalBlocks += countBlocks(cleaning.document().getSections(), FigureBlock.class)
                    + countBlocks(cleaning.document().getSections(), TableBlock.class);

            sb.append("## ").append(name).append("\n\n");
            sb.append("重挂 ").append(audits.size()).append(" 次。\n\n");
            if (!audits.isEmpty()) {
                sb.append("| # | blockId | 编号 | from → to | 引用块 | 页距 | order距 | 依据 | 引用原文 |\n");
                sb.append("|---|---|---|---|---|---|---|---|---|\n");
                for (int i = 0; i < audits.size(); i++) {
                    FloatBlockSectionReassigner.Audit audit = audits.get(i);
                    sb.append("| ").append(i + 1)
                            .append(" | `").append(audit.blockId()).append("`")
                            .append(" | ").append(audit.identifier())
                            .append(" | `").append(audit.oldSectionId()).append("` → `")
                            .append(audit.newSectionId()).append("`")
                            .append(" | `").append(audit.referenceBlockId()).append("`")
                            .append(" | ").append(audit.pageDistance())
                            .append(" | ").append(audit.orderDistance())
                            .append(" | ").append(audit.reason())
                            .append(" | ").append(preview(audit.referenceText(), 70))
                            .append(" |\n");
                }
                sb.append('\n');
            }
            for (String warning : cleaning.warnings()) {
                if (warning.startsWith("FLOAT_REASSIGN_SKIPPED_BY_DISTANCE")) {
                    sb.append("- ⚠️ ").append(warning).append("\n");
                }
            }
            sb.append('\n');
        }

        sb.append("---\n\n## 汇总\n\n");
        sb.append("- 论文数：").append(rawFiles.size()).append("\n");
        sb.append("- 浮动块总数（Figure+Table）：").append(totalBlocks).append("\n");
        sb.append("- 实际重挂次数：").append(totalMoved)
                .append("（移动率 ")
                .append(totalBlocks == 0 ? "0.00" : String.format("%.2f", (double) totalMoved / totalBlocks))
                .append("）\n");

        Path out = Path.of("src/test/output/reports", "浮动归属修复_audit_抽查.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("float audit 报告已写入: " + out.toAbsolutePath()
                + " （重挂 " + totalMoved + " 次 / 浮动块 " + totalBlocks + "）");
        assertThat(totalMoved).isGreaterThanOrEqualTo(0);
    }

    /** 从 Marker 原始 JSON 跑 Parser → Repair → Cleaner（不出网、不调 Marker）。 */
    private CleaningResult cleanFromRawJson(ObjectMapper mapper, JsonNode raw, String name) {
        MarkerTextExtractor textExtractor = new MarkerTextExtractor();
        MarkerDocumentParser parser = new MarkerDocumentParser(mapper,
                new MarkerBlockMapper(textExtractor), new MarkerSectionBuilder(textExtractor),
                new LocalFigureImageStorage("target/figure-images"));
        ReadingOrderRepair repair = new ReadingOrderRepair(
                new ReadingOrderRepairConfig(), new FigureAssociatedTextDetector());
        PaperCleaner cleaner = new PaperCleaner(
                new com.example.ragdemo.document.cleaner.FrontMatterPreScan(),
                new HeaderFooterCleaner(),
                new FrontMatterCleaner(),
                new AbstractRecovery(),
                new CaptionRecovery(),
                new ReferenceExtractor(new com.example.ragdemo.document.cleaner.ReferenceItemSplitter()),
                new BackMatterClassifier(),
                new SectionHierarchyNormalizer(), new com.example.ragdemo.document.cleaner.SectionHierarchyRepair(), new com.example.ragdemo.document.cleaner.MajorAnchorReparenting(),
                new FloatBlockSectionReassigner(),
                new com.example.ragdemo.document.cleaner.EmptyContentCleaner(),
                new CanonicalDocumentValidator());
        AcademicDocument document = parser.parse(name, raw);
        List<ReadingOrderRepairAudit> audits = repair.repair(document);
        return cleaner.clean(document, audits.size());
    }

    /**
     * 层级纠偏前的<b>证据采集</b>：Marker rawLevel 与最终树的差异、编号冲突、同 rawLevel 却父子化。
     * <p>
     * 运行：{@code mvn test -Dtest=RealPaperQaTest#hierarchyDiagnostic_forReview -Dchunker.qa.hierarchyDiag=true}
     */
    @Test
    void hierarchyDiagnostic_forReview() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getProperty("chunker.qa.hierarchyDiag")),
                "未提供 -Dchunker.qa.hierarchyDiag=true，跳过");
        Path dir = Path.of("src/test/output/marker-raw");
        Assumptions.assumeTrue(Files.isDirectory(dir), "marker-raw 目录不存在: " + dir);

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        List<Path> rawFiles;
        try (var stream = Files.list(dir)) {
            rawFiles = stream.filter(p -> p.toString().toLowerCase().endsWith(".json")).sorted().toList();
        }
        Assumptions.assumeTrue(!rawFiles.isEmpty(), "marker-raw 中没有 JSON");

        for (Path rawPath : rawFiles) {
            JsonNode raw = mapper.readTree(Files.readString(rawPath));
            CleaningResult cleaning = cleanFromRawJson(mapper, raw, rawPath.getFileName().toString());
            List<Section> all = flattenSections(cleaning.document().getSections());
            int withRawLevel = (int) all.stream().filter(s -> s.getRawLevel() != null).count();
            int numberedConflict = 0;
            int sameRawNested = 0;
            int rawVsTreeMismatch = 0;

            System.out.println("=== " + rawPath.getFileName());
            System.out.println("    sections=" + all.size() + "  有 rawLevel=" + withRawLevel);
            for (Section section : all) {
                if (section.getRawLevel() != null && section.getLevel() != null
                        && !section.getRawLevel().equals(section.getLevel())) {
                    rawVsTreeMismatch++;
                }
            }
            for (Section parent : all) {
                for (Section child : parent.getChildren()) {
                    Integer parentRaw = parent.getRawLevel();
                    Integer childRaw = child.getRawLevel();
                    if (parentRaw != null && childRaw != null && childRaw <= parentRaw) {
                        sameRawNested++;
                        System.out.println("    [SAME_RAW_NESTED] raw=" + parentRaw + "/" + childRaw
                                + "  " + parent.getTitle() + "  >  " + child.getTitle());
                    }
                    Integer parentNum = numberingDepth(parent.getTitle());
                    Integer childNum = numberingDepth(child.getTitle());
                    if (parentNum != null && childNum != null && childNum <= parentNum) {
                        numberedConflict++;
                        System.out.println("    [NUMBERED_CONFLICT] num=" + parentNum + "/" + childNum
                                + " raw=" + parentRaw + "/" + childRaw
                                + "  " + parent.getTitle() + "  >  " + child.getTitle());
                    }
                }
            }
            System.out.println("    汇总: rawVsTreeMismatch=" + rawVsTreeMismatch
                    + " numberedConflict=" + numberedConflict
                    + " sameRawNested=" + sameRawNested);
        }
    }

    private List<Section> flattenSections(List<Section> sections) {
        List<Section> all = new ArrayList<>();
        collectSections(sections, all);
        return all;
    }

    private void collectSections(List<Section> sections, List<Section> accumulator) {
        if (sections == null) {
            return;
        }
        for (Section section : sections) {
            if (section == null) {
                continue;
            }
            accumulator.add(section);
            collectSections(section.getChildren(), accumulator);
        }
    }

    /** 标题编号深度："2.1" → 2 / "A." → 2 / "III." → 1 / 无编号 → null。 */
    private Integer numberingDepth(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String value = title.trim();
        if (value.matches("^[A-Z]\\.\\d+.*")) {
            return 3;
        }
        // 罗马数字：多字符（II./III./IV.）→ 一级；单字符仅当后续标题全大写才判为罗马数字
        // （避免把字母子节 "C. Dataset" 误判为一级 —— 该规则与生产 HeadingNumbering 一致）
        java.util.regex.Matcher romanCandidate = java.util.regex.Pattern
                .compile("^([IVXLC]+)\\.\\s+(.*)$").matcher(value);
        if (romanCandidate.matches() && (romanCandidate.group(1).length() > 1
                || romanCandidate.group(2).equals(romanCandidate.group(2).toUpperCase()))) {
            return 1;
        }
        if (value.matches("^\\d+(\\.\\d+)+\\.?\\s+.*")) {
            return value.split("\\s+")[0].replaceAll("\\.$", "").split("\\.").length;
        }
        if (value.matches("^[A-Z]\\.\\s+.*")) {
            return 2;
        }
        if (value.matches("^\\d+\\.?\\s+.*")) {
            return 1;
        }
        return null;
    }

    /**
     * 生成【无编号 · rawLevel 相同却父子】诊断报告（<b>只诊断，不自动修</b>），供人工判断
     * 是否存在足够稳定的 skill §5.3（无编号同级纠偏）规则。
     * <p>
     * 运行：{@code mvn test -Dtest=RealPaperQaTest#unnumberedRawLevelPairReport_forReview -Dchunker.qa.pairReport=true}
     */
    @Test
    void unnumberedRawLevelPairReport_forReview() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getProperty("chunker.qa.pairReport")),
                "未提供 -Dchunker.qa.pairReport=true，跳过");
        Path dir = Path.of("src/test/output/marker-raw");
        Assumptions.assumeTrue(Files.isDirectory(dir), "marker-raw 目录不存在: " + dir);

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        List<Path> rawFiles;
        try (var stream = Files.list(dir)) {
            rawFiles = stream.filter(p -> p.toString().toLowerCase().endsWith(".json")).sorted().toList();
        }
        Assumptions.assumeTrue(!rawFiles.isEmpty(), "marker-raw 中没有 JSON");

        StringBuilder sb = new StringBuilder();
        sb.append("# 无编号 · rawLevel 相同却父子 —— 诊断报告（不自动修）\n\n");
        sb.append("> 生成命令：`mvn test -Dtest=RealPaperQaTest#unnumberedRawLevelPairReport_forReview -Dchunker.qa.pairReport=true`\n");
        sb.append("> 目的：人工逐条判断后，再决定是否值得为 skill §5.3（无编号同级纠偏）建立稳定规则\n\n");

        sb.append("## 为什么只诊断、不自动修\n\n");
        sb.append("`rawLevel` 相同**不是**可靠的同级信号。实测中大量 pair 是**合法父子**，例如\n");
        sb.append("`raw=2/2  \"3 Method\" > \"3.1 Event Reconstruction\"` —— 编号 3 / 3.1 证明这是真父子。\n");
        sb.append("因此凡是**无编号**的这类 pair，本报告只列出、不修改。\n\n");

        sb.append("## 字段说明\n\n");
        sb.append("| 字段 | 含义 |\n|---|---|\n");
        sb.append("| rawLevel p/c | Marker 声明的 heading depth（父 / 子） |\n");
        sb.append("| treeLevel p/c | 最终树中的 level（父 / 子） |\n");
        sb.append("| childBlocks / childChars | 子节点自身的内容量（内容多 → 更像真章节标题） |\n");
        sb.append("| childGrand | 子节点的子节点数 |\n");
        sb.append("| parentBlocks | 父节点自身的内容量 |\n");
        sb.append("| prevSibling | 前一个同级标题（看局部模式） |\n\n");

        sb.append("## 判定提示\n\n");
        sb.append("1. 同一父下**多个子**都呈「rawLevel 相同 + 各有独立正文」→ 更像「同级被错嵌套」\n");
        sb.append("2. 子标题是父标题的**语义下位**（`Event Reconstruction` ⊂ `Method`）→ 真父子，不要动\n");
        sb.append("3. 子节点**自身无正文且无子节点** → 更可能是 Marker 层级噪声\n");
        sb.append("4. 只有当①同一父下 ≥2 例、②语义上互相并列、③子各有独立正文 三条同时成立时，\n");
        sb.append("   才值得考虑写 §5.3 规则；否则维持现状\n\n");
        sb.append("---\n\n");

        int totalPairs = 0;
        int pairsWithContent = 0;
        for (Path rawPath : rawFiles) {
            JsonNode raw = mapper.readTree(Files.readString(rawPath));
            CleaningResult cleaning = cleanFromRawJson(mapper, raw, rawPath.getFileName().toString());
            List<Section> all = flattenSections(cleaning.document().getSections());

            List<String> rows = new ArrayList<>();
            for (Section parent : all) {
                List<Section> children = parent.getChildren();
                for (int i = 0; i < children.size(); i++) {
                    Section child = children.get(i);
                    Integer parentRaw = parent.getRawLevel();
                    Integer childRaw = child.getRawLevel();
                    if (parentRaw == null || childRaw == null || !childRaw.equals(parentRaw)) {
                        continue;
                    }
                    // 只看无编号标题（有编号的已由 §5.2 覆盖）
                    if (numberingDepth(parent.getTitle()) != null
                            || numberingDepth(child.getTitle()) != null) {
                        continue;
                    }
                    int childChars = blockChars(child);
                    if (childChars > 0) {
                        pairsWithContent++;
                    }
                    String prev = i > 0 ? children.get(i - 1).getTitle() : "-";
                    rows.add("| " + escape(parent.getTitle()) + " | " + escape(child.getTitle())
                            + " | " + parentRaw + " / " + childRaw
                            + " | " + parent.getLevel() + " / " + child.getLevel()
                            + " | " + (child.getBlocks() == null ? 0 : child.getBlocks().size())
                            + " / " + childChars
                            + " | " + (child.getChildren() == null ? 0 : child.getChildren().size())
                            + " | " + (parent.getBlocks() == null ? 0 : parent.getBlocks().size())
                            + " | " + escape(prev) + " |\n");
                }
            }

            totalPairs += rows.size();
            sb.append("## ").append(rawPath.getFileName()).append("\n\n");
            sb.append("候选 pair：").append(rows.size()).append("\n\n");
            if (rows.isEmpty()) {
                sb.append("（无）\n\n");
            } else {
                sb.append("| 父标题 | 子标题 | rawLevel p/c | treeLevel p/c | childBlocks/chars | childGrand | parentBlocks | prevSibling |\n");
                sb.append("|---|---|---|---|---|---|---|---|\n");
                rows.forEach(sb::append);
                sb.append('\n');
            }
        }

        sb.append("---\n\n## 汇总\n\n");
        sb.append("- 论文数：").append(rawFiles.size()).append("\n");
        sb.append("- 无编号 · rawLevel 相同却父子 的候选 pair 总数：**").append(totalPairs).append("**\n");
        sb.append("- 其中「子节点自身有正文」：**").append(pairsWithContent)
                .append("**（更值得关注；其余更像是 Marker 层级噪声）\n");

        Path out = Path.of("src/test/output/reports", "无编号同rawLevel父子对_诊断.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("无编号同 rawLevel 父子对诊断报告已写入: " + out.toAbsolutePath()
                + " （候选 " + totalPairs + " 对，其中有正文 " + pairsWithContent + "）");
        assertThat(totalPairs).isGreaterThanOrEqualTo(0);
    }

    /** section 自有 block 的文本字符数合计。 */
    private int blockChars(Section section) {
        if (section.getBlocks() == null) {
            return 0;
        }
        int chars = 0;
        for (DocumentBlock block : section.getBlocks()) {
            if (block instanceof com.example.ragdemo.document.domain.block.ParagraphBlock paragraph
                    && paragraph.getText() != null) {
                chars += paragraph.getText().length();
            } else if (block instanceof com.example.ragdemo.document.domain.block.TableBlock table
                    && table.getContent() != null) {
                chars += table.getContent().length();
            } else if (block instanceof com.example.ragdemo.document.domain.block.FigureBlock figure
                    && figure.getCaption() != null) {
                chars += figure.getCaption().length();
            }
        }
        return chars;
    }

    private String escape(String value) {
        if (value == null || value.isBlank()) {
            return "（空标题）";
        }
        return value.replace("|", "\\|").replace("\n", " ");
    }

    /** skill §27 的六个重点抽查 Case（仅针对目标论文，文件不存在时跳过）。 */
    private void reportTargetPaperCases(Path target, ObjectMapper json, SectionAwareChunker chunker)
            throws Exception {
        AcademicDocument document = json.readValue(Files.readString(target), AcademicDocument.class);
        ChunkingResult result = chunker.chunk(document);
        ChunkQualityMetrics m = result.metrics();
        System.out.println("--- skill §27 六 Case：" + target.getFileName() + " ---");

        // Case 1：跨页 repaired paragraph 必须保持为一个逻辑单元
        boolean crossPageIntact = result.chunks().stream().anyMatch(chunk ->
                chunk.getSourceBlockIds().contains("/page/3/Text/11")
                        && chunk.getSourceBlockIds().contains("/page/4/Text/1"));
        System.out.println("Case1 跨页段落 /page/3/Text/11 + /page/4/Text/1 同一 chunk : " + crossPageIntact);
        assertThat(crossPageIntact).as("§27 Case1 跨页 repaired paragraph 被按页拆开").isTrue();

        // Case 2：main contributions LIST 尽量整体（≥3 项）
        long multiItemLists = result.chunks().stream()
                .filter(chunk -> chunk.getChunkType() == ChunkType.LIST
                        || chunk.getChunkType() == ChunkType.MIXED)
                .filter(chunk -> chunk.getContent().lines()
                        .filter(line -> line.startsWith("- ")).count() >= 3)
                .count();
        System.out.println("Case2 含 ≥3 项的 LIST/MIXED chunk 数 : " + multiItemLists);
        assertThat(multiItemLists).as("§27 Case2 列表被切碎").isGreaterThan(0);

        // Case 3：Figure caption 不丢
        long figureBlocks = countBlocks(document.getSections(), FigureBlock.class);
        System.out.println("Case3 Figure blocks=" + figureBlocks
                + " figureChunks=" + m.figureChunkCount()
                + " skipped(无文本)=" + m.skippedFigureCount());
        assertThat(m.figureChunkCount() + m.skippedFigureCount())
                .as("§27 Case3 有 Figure 既未成 chunk 也未计数")
                .isEqualTo((int) figureBlocks);

        // Case 4：Table caption/content 不得被切成无上下文碎片
        long tableBlocks = countBlocks(document.getSections(), TableBlock.class);
        System.out.println("Case4 Table blocks=" + tableBlocks + " tableChunks=" + m.tableChunkCount());
        assertThat(m.tableChunkCount()).as("§27 Case4 表格丢失").isGreaterThanOrEqualTo((int) tableBlocks);
        result.chunksOf(ChunkType.TABLE)
                .forEach(chunk -> assertThat(chunk.getContent()).contains("\t"));

        // Case 5：Discussion / 子 Section 不允许跨 subsection
        System.out.println("Case5 crossSectionChunkCount = " + m.crossSectionChunkCount());
        assertThat(m.crossSectionChunkCount()).isZero();

        // Case 6：References 必须来自 references[]，不与正文混
        int referenceCount = document.getReferences() == null ? 0 : document.getReferences().size();
        System.out.println("Case6 references=" + referenceCount
                + " referenceChunks=" + m.referenceChunkCount()
                + " skipped=" + m.skippedReferenceCount()
                + " referenceRatio=" + String.format("%.2f", m.referenceChunkRatio()));
        assertThat(m.referenceChunkCount() + m.skippedReferenceCount())
                .as("§27 Case6 参考文献条目数不一致").isEqualTo(referenceCount);
        result.chunksOf(ChunkType.REFERENCE)
                .forEach(chunk -> assertThat(chunk.getSectionId()).isNull());
        result.chunksOf(ChunkType.TEXT)
                .forEach(chunk -> assertThat(chunk.getReferenceIds()).isEmpty());
    }

    private long countBlocks(List<Section> sections, Class<?> type) {
        if (sections == null) {
            return 0;
        }
        long count = 0;
        for (Section section : sections) {
            if (section.getBlocks() != null) {
                for (DocumentBlock block : section.getBlocks()) {
                    if (type.isInstance(block)) {
                        count++;
                    }
                }
            }
            count += countBlocks(section.getChildren(), type);
        }
        return count;
    }

    /** 手工装配 SectionAwareChunker（与 Spring 装配保持一致，便于无容器测试）。 */
    private SectionAwareChunker buildChunker(RagProperties properties) {
        ChunkTokenEstimator estimator = new CharBasedTokenEstimator();
        ChunkTextSplitter textSplitter = new ChunkTextSplitter(estimator);
        ChunkTextRenderer renderer = new ChunkTextRenderer(properties);
        ListChunker listChunker = new ListChunker(estimator, textSplitter, properties);
        FigureTableChunker figureTableChunker = new FigureTableChunker(estimator, properties);
        SectionBlockChunker sectionBlockChunker = new SectionBlockChunker(
                estimator, textSplitter, listChunker, figureTableChunker, properties);
        DocumentLevelChunker documentLevelChunker = new DocumentLevelChunker(
                estimator, textSplitter, properties);
        return new SectionAwareChunker(estimator, textSplitter, renderer, sectionBlockChunker,
                documentLevelChunker, new ChunkQualityReporter(), properties);
    }

    private int maxPage(PipelineResult result) {
        int max = 0;
        for (var section : result.document().getSections()) {
            for (var block : section.getBlocks()) {
                if (block.getPageRange() != null && block.getPageRange().getEndPage() != null) {
                    max = Math.max(max, block.getPageRange().getEndPage());
                }
            }
        }
        return max;
    }

    private CanonicalDocumentPipeline buildPipeline(MarkerClient markerClient) {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MarkerTextExtractor textExtractor = new MarkerTextExtractor();
        MarkerBlockMapper blockMapper = new MarkerBlockMapper(textExtractor);
        MarkerSectionBuilder sectionBuilder = new MarkerSectionBuilder(textExtractor);
        FigureImageStorage imageStorage = new LocalFigureImageStorage("target/figure-images");
        MarkerDocumentParser parser = new MarkerDocumentParser(
                objectMapper, blockMapper, sectionBuilder, imageStorage);

        ReadingOrderRepairConfig repairConfig = new ReadingOrderRepairConfig();
        ReadingOrderRepair repair = new ReadingOrderRepair(repairConfig, new FigureAssociatedTextDetector());

        PaperCleaner cleaner = new PaperCleaner(
                new com.example.ragdemo.document.cleaner.FrontMatterPreScan(),
                new HeaderFooterCleaner(),
                new FrontMatterCleaner(),
                new AbstractRecovery(),
                new CaptionRecovery(),
                new ReferenceExtractor(new com.example.ragdemo.document.cleaner.ReferenceItemSplitter()),
                new BackMatterClassifier(),
                new SectionHierarchyNormalizer(), new com.example.ragdemo.document.cleaner.SectionHierarchyRepair(), new com.example.ragdemo.document.cleaner.MajorAnchorReparenting(),
                new com.example.ragdemo.document.cleaner.FloatBlockSectionReassigner(),
                new com.example.ragdemo.document.cleaner.EmptyContentCleaner(),
                new CanonicalDocumentValidator());

        return new CanonicalDocumentPipeline(markerClient, parser, repair, cleaner);
    }
}
