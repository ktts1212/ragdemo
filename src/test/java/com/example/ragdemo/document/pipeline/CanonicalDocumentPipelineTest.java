package com.example.ragdemo.document.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.example.ragdemo.document.cleaner.AbstractRecovery;
import com.example.ragdemo.document.cleaner.BackMatterClassifier;
import com.example.ragdemo.document.cleaner.CanonicalDocumentValidator;
import com.example.ragdemo.document.cleaner.CaptionRecovery;
import com.example.ragdemo.document.cleaner.EmptyContentCleaner;
import com.example.ragdemo.document.cleaner.FrontMatterCleaner;
import com.example.ragdemo.document.cleaner.HeaderFooterCleaner;
import com.example.ragdemo.document.cleaner.PaperCleaner;
import com.example.ragdemo.document.cleaner.ReferenceExtractor;
import com.example.ragdemo.document.cleaner.SectionHierarchyNormalizer;
import com.example.ragdemo.document.domain.Reference;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.FigureBlock;
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
import com.example.ragdemo.infrastructure.marker.MarkerHealth;
import com.example.ragdemo.infrastructure.marker.MarkerResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Phase E 全链路端到端测试：
 * <pre>
 * stub MarkerClient → MarkerDocumentParser → ReadingOrderRepair → PaperCleaner → Validator
 * </pre>
 * 使用真实结构的 full-paper.json fixture，不依赖 Marker 服务。
 */
class CanonicalDocumentPipelineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CanonicalDocumentPipeline pipeline;

    /** 返回固定 fixture 的 MarkerClient stub */
    private static final class StubMarkerClient implements MarkerClient {
        private final JsonNode data;

        StubMarkerClient(JsonNode data) {
            this.data = data;
        }

        @Override
        public MarkerResponse parse(byte[] pdfBytes, String fileName) {
            return new MarkerResponse(true, fileName, 61.2, data, null, null);
        }

        @Override
        public MarkerHealth health() {
            return new MarkerHealth("ok");
        }

        @Override
        public boolean isAlive() {
            return true;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        JsonNode data;
        try (var in = new ClassPathResource("marker/full-paper.json").getInputStream()) {
            data = objectMapper.readTree(in);
        }

        MarkerTextExtractor textExtractor = new MarkerTextExtractor();
        MarkerBlockMapper blockMapper = new MarkerBlockMapper(textExtractor);
        MarkerSectionBuilder sectionBuilder = new MarkerSectionBuilder(textExtractor);
        FigureImageStorage imageStorage = new LocalFigureImageStorage("target/test-figure-images");
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
                new SectionHierarchyNormalizer(), new com.example.ragdemo.document.cleaner.SectionHierarchyRepair(),
                new com.example.ragdemo.document.cleaner.MajorAnchorReparenting(),
                new com.example.ragdemo.document.cleaner.FloatBlockSectionReassigner(),
                new com.example.ragdemo.document.cleaner.EmptyContentCleaner(),
                new CanonicalDocumentValidator());

        pipeline = new CanonicalDocumentPipeline(
                new StubMarkerClient(data), parser, repair, cleaner);
    }

    @Test
    void fullChain_producesValidCanonicalDocument() {
        PipelineResult result = pipeline.process("doc-42", new byte[]{1, 2, 3}, "paper.pdf");

        // 校验通过
        assertThat(result.valid()).isTrue();
        assertThat(result.markerElapsedSeconds()).isEqualTo(61.2);
    }

    @Test
    void fullChain_extractsMetadata() {
        PipelineResult result = pipeline.process("doc-42", new byte[]{1, 2, 3}, "paper.pdf");

        assertThat(result.document().getMetadata().getTitle())
                .contains("Self-Supervised Electrocardiogram");
        assertThat(result.document().getMetadata().getDoi())
                .isEqualTo("10.1038/s41467-026-72436-2");
    }

    @Test
    void fullChain_recoversAbstractAndRemovesSection() {
        PipelineResult result = pipeline.process("doc-42", new byte[]{1, 2, 3}, "paper.pdf");

        assertThat(result.document().getAbstractText())
                .isEqualTo("We present a foundation model for ECG.");
        assertThat(result.document().getSections())
                .extracting(Section::getTitle)
                .doesNotContain("Abstract");
    }

    @Test
    void fullChain_repairsCrossPageContinuation() {
        PipelineResult result = pipeline.process("doc-42", new byte[]{1, 2, 3}, "paper.pdf");

        // 跨页续接被修复（A 结尾无标点 + 中间 PageFooter 透明 + B 首字母小写）
        assertThat(result.repairCount()).isEqualTo(1);
        assertThat(result.metrics().crossPageMergeCount()).isEqualTo(1);

        // 合并后的段落来自两个 page block
        boolean merged = result.document().getSections().stream()
                .filter(s -> "Introduction".equals(s.getTitle()))
                .flatMap(s -> s.getBlocks().stream())
                .anyMatch(b -> b instanceof com.example.ragdemo.document.domain.block.ParagraphBlock p
                        && p.getText().contains("monitored for twelve hours")
                        && p.getSourceBlockIds().size() == 2);
        assertThat(merged).isTrue();
    }

    @Test
    void fullChain_externalizesFigureImageAndAssociatesCaption() {
        PipelineResult result = pipeline.process("doc-42", new byte[]{1, 2, 3}, "paper.pdf");

        FigureBlock figure = result.document().getSections().stream()
                .flatMap(s -> s.getBlocks().stream())
                .filter(b -> b instanceof FigureBlock)
                .map(b -> (FigureBlock) b)
                .findFirst()
                .orElseThrow();

        // base64 已外部化
        assertThat(figure.getImageKey()).isNotBlank().doesNotContain("base64");
        // caption 已关联
        assertThat(figure.getCaption()).contains("Overall architecture");
        // 指标：无 base64 残留
        assertThat(result.metrics().base64RemainingCount()).isZero();
    }

    @Test
    void fullChain_extractsReferencesAndRemovesSection() {
        PipelineResult result = pipeline.process("doc-42", new byte[]{1, 2, 3}, "paper.pdf");

        List<Reference> references = result.document().getReferences();
        assertThat(references).hasSize(2);
        assertThat(references.get(0).getRawText()).contains("Ribeiro");
        assertThat(result.document().getSections())
                .extracting(Section::getTitle)
                .doesNotContain("References");
    }

    @Test
    void fullChain_removesPublisherNoteBackMatter() {
        PipelineResult result = pipeline.process("doc-42", new byte[]{1, 2, 3}, "paper.pdf");

        assertThat(result.document().getSections())
                .extracting(Section::getTitle)
                .doesNotContain("Publisher note");
    }

    @Test
    void fullChain_removesHeaderFooterNoise() {
        PipelineResult result = pipeline.process("doc-42", new byte[]{1, 2, 3}, "paper.pdf");

        assertThat(result.metrics().headerFooterRemaining()).isZero();
    }

    @Test
    void fullChain_metricsAreConsistent() {
        PipelineResult result = pipeline.process("doc-42", new byte[]{1, 2, 3}, "paper.pdf");

        var metrics = result.metrics();
        assertThat(metrics.figureCount()).isEqualTo(1);
        assertThat(metrics.referenceCount()).isEqualTo(2);
        assertThat(metrics.sectionCount()).isGreaterThanOrEqualTo(3);
        assertThat(metrics.emptySectionCount()).isZero();
        assertThat(metrics.base64RemainingCount()).isZero();
        assertThat(metrics.repairCount()).isEqualTo(1);
    }
}
