package com.example.ragdemo.document.parser.marker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.CaptionBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.FormulaBlock;
import com.example.ragdemo.document.domain.block.ListBlock;
import com.example.ragdemo.document.domain.block.NoiseBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;
import com.example.ragdemo.document.image.FigureImageStorage;
import com.example.ragdemo.document.image.LocalFigureImageStorage;
import com.example.ragdemo.document.parser.marker.exception.MarkerDocumentParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MarkerDocumentParser 结构映射测试（skill §31 Layer 2）。
 * <p>
 * 基于 src/test/resources/marker/ 下的 Marker JSON fixture，不依赖 Marker 服务。
 */
class MarkerDocumentParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MarkerDocumentParser parser;

    private MarkerDocumentParser newParser() {
        MarkerTextExtractor extractor = new MarkerTextExtractor();
        MarkerBlockMapper blockMapper = new MarkerBlockMapper(extractor);
        MarkerSectionBuilder sectionBuilder = new MarkerSectionBuilder(extractor);
        FigureImageStorage imageStorage = new LocalFigureImageStorage("target/test-figure-images");
        return new MarkerDocumentParser(objectMapper, blockMapper, sectionBuilder, imageStorage);
    }

    @BeforeEach
    void setUp() {
        parser = newParser();
    }

    private JsonNode fixture(String name) throws Exception {
        try (var in = new ClassPathResource("marker/" + name).getInputStream()) {
            return objectMapper.readTree(in);
        }
    }

    // ==================== Signing: sections / page tracking ====================

    @Test
    void shouldParseSectionsAndPreserveOrder() throws Exception {
        AcademicDocument doc = parser.parse("doc-1", fixture("simple-paper.json"));

        // 第一个 SectionHeader 之前的内容归入无标题 section，不丢正文
        assertThat(doc.getSections()).hasSize(3);
        assertThat(doc.getSections().get(0).getTitle()).isNull();
        assertThat(doc.getSections().get(1).getTitle()).isEqualTo("Introduction");
        assertThat(doc.getSections().get(2).getTitle()).isEqualTo("Methods");
    }

    @Test
    void shouldTrackPageNumbers() throws Exception {
        AcademicDocument doc = parser.parse("doc-1", fixture("simple-paper.json"));

        Section introduction = doc.getSections().get(1);
        Section methods = doc.getSections().get(2);

        // 第 1 页的段落
        ParagraphBlock p1 = (ParagraphBlock) introduction.getBlocks().stream()
                .filter(b -> b instanceof ParagraphBlock).findFirst().orElseThrow();
        assertThat(p1.getPageRange().getStartPage()).isEqualTo(1);
        assertThat(p1.getPageRange().getEndPage()).isEqualTo(1);

        // 第 2 页的段落
        ParagraphBlock p2 = (ParagraphBlock) methods.getBlocks().get(0);
        assertThat(p2.getPageRange().getStartPage()).isEqualTo(2);
    }

    @Test
    void shouldMapHeaderFooterToNoiseBlock() throws Exception {
        AcademicDocument doc = parser.parse("doc-1", fixture("simple-paper.json"));

        Section introduction = doc.getSections().get(1);
        assertThat(introduction.getBlocks()).anyMatch(b ->
                b instanceof NoiseBlock && "PAGE_FOOTER".equals(((NoiseBlock) b).getNoiseType()));
        assertThat(doc.getSections().get(0).getBlocks()).anyMatch(b ->
                b instanceof NoiseBlock && "PAGE_HEADER".equals(((NoiseBlock) b).getNoiseType()));
    }

    @Test
    void shouldPreserveSourceBlockId() throws Exception {
        AcademicDocument doc = parser.parse("doc-1", fixture("simple-paper.json"));

        Section methods = doc.getSections().get(2);
        ParagraphBlock p = (ParagraphBlock) methods.getBlocks().get(0);
        assertThat(p.getSourceBlockId()).isEqualTo("/page/2/Text/2");
    }

    @Test
    void shouldBuildNestedSectionsFromSectionHierarchy() throws Exception {
        AcademicDocument doc = parser.parse("doc-h", fixture("section-hierarchy.json"));

        // 2 个顶层 section（II. CONCLUSIONS 回到 level 1）
        assertThat(doc.getSections()).hasSize(2);

        Section intro = doc.getSections().get(0);
        assertThat(intro.getTitle()).isEqualTo("I. INTRODUCTION");
        assertThat(intro.getLevel()).isEqualTo(1);
        assertThat(intro.getPath()).containsExactly("I. INTRODUCTION");
        assertThat(intro.getBlocks()).hasSize(1);
        assertThat(intro.getChildren()).hasSize(1);

        // A. Dataset 嵌套在 I. INTRODUCTION 下，path 为标题链（不是 blockId）
        Section dataset = intro.getChildren().get(0);
        assertThat(dataset.getTitle()).isEqualTo("A. Dataset");
        assertThat(dataset.getLevel()).isEqualTo(2);
        assertThat(dataset.getPath()).containsExactly("I. INTRODUCTION", "A. Dataset");
        assertThat(dataset.getChildren()).hasSize(1);

        // A.1 Preprocessing 嵌套在 A. Dataset 下
        Section preprocessing = dataset.getChildren().get(0);
        assertThat(preprocessing.getTitle()).isEqualTo("A.1 Preprocessing");
        assertThat(preprocessing.getLevel()).isEqualTo(3);
        assertThat(preprocessing.getPath())
                .containsExactly("I. INTRODUCTION", "A. Dataset", "A.1 Preprocessing");
        assertThat(((ParagraphBlock) preprocessing.getBlocks().get(0)).getText())
                .isEqualTo("Preprocessing text.");

        // II. CONCLUSIONS 是顶层，不是 I. INTRODUCTION 的子节点
        Section conclusions = doc.getSections().get(1);
        assertThat(conclusions.getTitle()).isEqualTo("II. CONCLUSIONS");
        assertThat(conclusions.getLevel()).isEqualTo(1);
        assertThat(intro.getChildren()).extracting(Section::getTitle)
                .doesNotContain("II. CONCLUSIONS");
    }

    @Test
    void shouldCorrectMisTaggedHierarchyUsingNumbering() throws Exception {
        // Marker 把 "III. RESULTS" 误标为 <h4> 且 hierarchy 挂到 "C. Dataset" 下；
        // 编号 "III." 表明它是一级章节 → 应纠正为顶层，而非 C. Dataset 的子节点。
        AcademicDocument doc = parser.parse("doc-m", fixture("mis-tagged-hierarchy.json"));

        assertThat(doc.getSections()).hasSize(2);
        Section intro = doc.getSections().get(0);
        assertThat(intro.getTitle()).isEqualTo("I. INTRODUCTION");
        assertThat(intro.getLevel()).isEqualTo(1);

        Section dataset = intro.getChildren().get(0);
        assertThat(dataset.getTitle()).isEqualTo("C. Dataset");
        assertThat(dataset.getLevel()).isEqualTo(2);

        // III. RESULTS 被纠正为顶层（level 1），不是 C. Dataset 的子节点
        Section results = doc.getSections().get(1);
        assertThat(results.getTitle()).isEqualTo("III. RESULTS");
        assertThat(results.getLevel()).isEqualTo(1);
        assertThat(results.getPath()).containsExactly("III. RESULTS");
        assertThat(dataset.getChildren()).isEmpty();
        assertThat(((ParagraphBlock) results.getBlocks().get(0)).getText())
                .isEqualTo("Results text.");
    }

    // ==================== Signing: figure ====================

    @Test
    void shouldParseFigureAndExternalizeBase64() throws Exception {
        AcademicDocument doc = parser.parse("doc-2", fixture("figures-paper.json"));

        Section results = doc.getSections().get(0);
        FigureBlock figure = (FigureBlock) results.getBlocks().stream()
                .filter(b -> b instanceof FigureBlock).findFirst().orElseThrow();

        assertThat(figure.getId()).isEqualTo("/page/1/Figure/3");
        assertThat(figure.getImageKey()).isNotBlank();
        assertThat(figure.getImageKey()).contains("figures/");
        // base64 绝不能进入领域模型
        assertThat(figure.getImageKey()).doesNotContain("base64");
        assertThat(figure.getSourceBlockIds()).containsExactly("/page/1/Figure/3");

        // Caption 独立保留（关联逻辑属 Cleaner）
        CaptionBlock caption = (CaptionBlock) results.getBlocks().stream()
                .filter(b -> b instanceof CaptionBlock).findFirst().orElseThrow();
        assertThat(caption.getText()).contains("Overall architecture");
    }

    // ==================== Signing: table / list / formula ====================

    @Test
    void shouldParseTableWithHtmlAndTsv() throws Exception {
        AcademicDocument doc = parser.parse("doc-3", fixture("tables-paper.json"));

        TableBlock table = (TableBlock) doc.getSections().get(0).getBlocks().stream()
                .filter(b -> b instanceof TableBlock).findFirst().orElseThrow();

        assertThat(table.getHtml()).contains("<table>");
        assertThat(table.getContent()).isEqualTo(
                "Model\tPTB-XL\tChapman\nECG-LFM\t0.951\t0.993");
    }

    @Test
    void shouldParseListAndFormula() throws Exception {
        AcademicDocument doc = parser.parse("doc-3", fixture("tables-paper.json"));

        ListBlock list = (ListBlock) doc.getSections().get(0).getBlocks().stream()
                .filter(b -> b instanceof ListBlock).findFirst().orElseThrow();
        assertThat(list.getItems()).containsExactly("Encoder", "Projection head", "Classifier");

        FormulaBlock formula = (FormulaBlock) doc.getSections().get(0).getBlocks().stream()
                .filter(b -> b instanceof FormulaBlock).findFirst().orElseThrow();
        assertThat(formula.getFormula()).contains("log");
    }

    // ==================== Signing: references 忠实保留 ====================

    @Test
    void shouldKeepReferencesSectionAsIs() throws Exception {
        AcademicDocument doc = parser.parse("doc-4", fixture("references-paper.json"));

        // Parser 不提取 References（属 Cleaner 的 ReferenceExtractor），只忠实保留
        assertThat(doc.getReferences()).isEmpty();
        Section refSection = doc.getSections().get(0);
        assertThat(refSection.getTitle()).isEqualTo("References");
        ListBlock list = (ListBlock) refSection.getBlocks().get(0);
        assertThat(list.getItems()).hasSize(3);
        assertThat(list.getItems().get(0)).contains("Ribeiro");
    }

    // ==================== Signing: unknown block ====================

    @Test
    void shouldNotFailOnUnknownBlockType() throws Exception {
        AcademicDocument doc = parser.parse("doc-5", fixture("unknown-blocks.json"));

        Section section = doc.getSections().get(0);
        // 未知 block 被忽略，但前后正文都保留
        assertThat(section.getBlocks()).hasSize(2);
        assertThat(((ParagraphBlock) section.getBlocks().get(0)).getText())
                .contains("Text before unknown block");
        assertThat(((ParagraphBlock) section.getBlocks().get(1)).getText())
                .contains("Text after unknown block");
    }

    // ==================== Signing: 硬失败 ====================

    @Test
    void shouldRejectNonDocumentRoot() throws Exception {
        assertThatThrownBy(() -> parser.parse("doc-6", fixture("invalid-root.json")))
                .isInstanceOf(MarkerDocumentParseException.class)
                .hasMessageContaining("Document");
    }

    @Test
    void shouldRejectEmptyChildren() throws Exception {
        assertThatThrownBy(() -> parser.parse("doc-6", fixture("empty-children.json")))
                .isInstanceOf(MarkerDocumentParseException.class);
    }

    @Test
    void shouldRejectNullInputs() {
        assertThatThrownBy(() -> parser.parse(null, objectMapper.createObjectNode()))
                .isInstanceOf(MarkerDocumentParseException.class);
        assertThatThrownBy(() -> parser.parse("doc-6", null))
                .isInstanceOf(MarkerDocumentParseException.class);
    }

    @Test
    void shouldRejectBlankDocumentId() {
        assertThatThrownBy(() -> parser.parse("  ", objectMapper.createObjectNode()))
                .isInstanceOf(MarkerDocumentParseException.class);
    }
}
