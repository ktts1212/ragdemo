package com.example.ragdemo.document.cleaner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PageRange;
import com.example.ragdemo.document.domain.PaperMetadata;
import com.example.ragdemo.document.domain.Reference;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.CaptionBlock;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.ListBlock;
import com.example.ragdemo.document.domain.block.NoiseBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;

/**
 * Phase D 测试：PaperCleaner 9 组件 + 完整管道（skill §31 Layer 3）。
 */
class PaperCleanerTest {

    private PaperCleaner paperCleaner;

    @BeforeEach
    void setUp() {
        paperCleaner = new PaperCleaner(
                new FrontMatterPreScan(),
                new HeaderFooterCleaner(),
                new FrontMatterCleaner(),
                new AbstractRecovery(),
                new CaptionRecovery(),
                new ReferenceExtractor(new ReferenceItemSplitter()),
                new BackMatterClassifier(),
                new SectionHierarchyNormalizer(), new SectionHierarchyRepair(), new MajorAnchorReparenting(),
                new FloatBlockSectionReassigner(),
                new EmptyContentCleaner(),
                new CanonicalDocumentValidator());
    }

    // ==================== builders ====================

    private ParagraphBlock paragraph(String id, String text, int page) {
        return ParagraphBlock.builder()
                .id(id).sourceBlockId(id).sourceBlockIds(new ArrayList<>(List.of(id)))
                .text(text).order(0)
                .pageRange(PageRange.builder().startPage(page).endPage(page).build())
                .build();
    }

    private NoiseBlock noise(String id, String type) {
        return NoiseBlock.builder().id(id).sourceBlockId(id).text("noise").order(0).noiseType(type).build();
    }

    private Section section(String title, DocumentBlock... blocks) {
        return Section.builder()
                .id(title == null ? "sec-unnamed" : "sec-" + title.toLowerCase())
                .title(title).level(1)
                .path(new ArrayList<>(title == null ? List.of() : List.of(title)))
                .blocks(new ArrayList<>(List.of(blocks)))
                .build();
    }

    private AcademicDocument document(Section... sections) {
        return AcademicDocument.builder()
                .documentId("doc-1")
                .metadata(PaperMetadata.builder().build())
                .sections(new ArrayList<>(List.of(sections)))
                .references(new ArrayList<>())
                .build();
    }

    // ==================== 1. HeaderFooterCleaner ====================

    @Test
    void shouldRemoveHeaderFooterNoise() {
        AcademicDocument doc = document(section("Introduction",
                noise("/page/1/PageHeader/1", "PAGE_HEADER"),
                paragraph("/page/1/Text/2", "Body text.", 1),
                noise("/page/1/PageFooter/3", "PAGE_FOOTER")));

        CleaningResult result = paperCleaner.clean(doc);

        Section intro = result.document().getSections().get(0);
        assertThat(intro.getBlocks()).hasSize(1);
        assertThat(intro.getBlocks().get(0)).isInstanceOf(ParagraphBlock.class);
        assertThat(result.validation().metrics().headerFooterRemaining()).isZero();
    }

    // ==================== 2. FrontMatterCleaner ====================

    @Test
    void shouldExtractTitleAndDoiFromFrontMatter() {
        AcademicDocument doc = document(section(null,
                paragraph("/page/1/Text/1", "A Self-Supervised ECG Foundation Model", 1),
                paragraph("/page/1/Text/2", "https://doi.org/10.1038/s41467-026-72436-2", 1)));

        CleaningResult result = paperCleaner.clean(doc);

        assertThat(result.document().getMetadata().getTitle())
                .isEqualTo("A Self-Supervised ECG Foundation Model");
        assertThat(result.document().getMetadata().getDoi())
                .isEqualTo("10.1038/s41467-026-72436-2");
    }

    // ==================== 3. AbstractRecovery ====================

    @Test
    void shouldRecoverAbstractAndRemoveDuplicateSection() {
        AcademicDocument doc = document(
                section("Abstract",
                        paragraph("/page/1/Text/1", "We present a foundation model for ECG.", 1),
                        paragraph("/page/1/Text/2", "Experiments show state-of-the-art results.", 1)),
                section("Introduction", paragraph("/page/1/Text/3", "ECG is widely used.", 1)));

        CleaningResult result = paperCleaner.clean(doc);

        assertThat(result.document().getAbstractText())
                .contains("We present a foundation model")
                .contains("\n\n")
                .contains("state-of-the-art");
        // Abstract section 已移除，避免重复
        assertThat(result.document().getSections())
                .extracting(Section::getTitle)
                .doesNotContain("Abstract");
    }

    @Test
    void shouldRecoverInlineAbstractMarker() {
        // 真实语料形态：Abstract 标记在正文段落内（<i>Abstract</i>—正文），无 Abstract SectionHeader
        AcademicDocument doc = document(
                section("Learning ECG Representations via Poly-Window Contrastive Learning",
                        paragraph("/page/0/Text/3", "Yi Yuan, Joseph Van Duyn, Runze Yan", 1),
                        paragraph("/page/0/Text/11",
                                "Abstract—Electrocardiogram (ECG) analysis is foundational for cardiovascular "
                                        + "disease diagnosis, yet the performance of deep learning models is often "
                                        + "constrained by limited labeled data.", 1),
                        paragraph("/page/0/Text/14",
                                "Index Terms—Electrocardiogram, Self-supervised learning", 1)));

        CleaningResult result = paperCleaner.clean(doc);

        assertThat(result.document().getAbstractText())
                .startsWith("Electrocardiogram (ECG) analysis is foundational")
                .doesNotContain("Abstract—");
        // front matter（作者行 / 摘要 / 关键词）已全部消费 → 该 section 被 EmptyContentCleaner 清理
        assertThat(result.document().getSections()).isEmpty();
    }

    @Test
    void shouldNotMistakeBodyTextStartingWithAbstractWord() {
        // 正文中出现 "Abstract" 但不带分隔符 → 不应误判为 abstract
        AcademicDocument doc = document(
                section("Methods",
                        paragraph("/page/1/Text/1",
                                "Abstract representations learned from ECG signals are useful for downstream tasks.",
                                1)));

        CleaningResult result = paperCleaner.clean(doc);

        assertThat(result.document().getAbstractText()).isNull();
    }

    // ==================== 4. CaptionRecovery ====================

    @Test
    void shouldAssociateCaptionWithNearestFigure() {
        FigureBlock figure = FigureBlock.builder()
                .id("/page/1/Figure/2")
                .sourceBlockIds(new ArrayList<>(List.of("/page/1/Figure/2")))
                .order(1)
                .pageRange(PageRange.builder().startPage(1).endPage(1).build())
                .build();
        AcademicDocument doc = document(section("Results",
                paragraph("/page/1/Text/1", "Our model works.", 1),
                figure,
                CaptionBlock.builder().id("/page/1/Caption/3")
                        .sourceBlockId("/page/1/Caption/3").text("Figure 1 | Architecture.").order(2).build()));

        CleaningResult result = paperCleaner.clean(doc);

        Section results = result.document().getSections().get(0);
        FigureBlock cleaned = (FigureBlock) results.getBlocks().stream()
                .filter(b -> b instanceof FigureBlock).findFirst().orElseThrow();
        assertThat(cleaned.getCaption()).isEqualTo("Figure 1 | Architecture.");
        // caption 已并入 figure，独立 CaptionBlock 移除
        assertThat(results.getBlocks()).noneMatch(b -> b instanceof CaptionBlock);
        // sourceBlockIds 追踪
        assertThat(cleaned.getSourceBlockIds()).contains("/page/1/Caption/3");
    }

    // ==================== 5. ReferenceExtractor ====================

    @Test
    void shouldExtractReferencesFromReferencesSection() {
        ListBlock refList = ListBlock.builder()
                .id("/page/24/List/2").sourceBlockId("/page/24/List/2").order(1)
                .items(new ArrayList<>(List.of(
                        "1. Ribeiro AH, et al. Automatic diagnosis of the 12-lead ECG. Nat Commun. 2020.",
                        "2. Chen T, et al. A simple framework for contrastive learning. ICML. 2020.")))
                .build();
        AcademicDocument doc = document(
                section("Results", paragraph("/page/1/Text/1", "Results body.", 1)),
                section("References", refList));

        CleaningResult result = paperCleaner.clean(doc);

        List<Reference> references = result.document().getReferences();
        assertThat(references).hasSize(2);
        assertThat(references.get(0).getId()).isEqualTo("ref-1");
        assertThat(references.get(0).getRawText()).contains("Ribeiro");
        assertThat(references.get(0).getPublicationDate().getYear()).isEqualTo(2020);
        // References section 从正文移除
        assertThat(result.document().getSections())
                .extracting(Section::getTitle)
                .doesNotContain("References");
    }

    @Test
    void shouldExtractReferenceDoi() {
        ListBlock refList = ListBlock.builder()
                .id("/page/24/List/2").sourceBlockId("/page/24/List/2").order(1)
                .items(new ArrayList<>(List.of(
                        "1. Author A. Title. Journal. doi:10.1038/s41467-020-15432-4")))
                .build();
        AcademicDocument doc = document(section("References", refList));

        CleaningResult result = paperCleaner.clean(doc);

        assertThat(result.document().getReferences().get(0).getDoi())
                .isEqualTo("10.1038/s41467-020-15432-4");
    }

    @Test
    void shouldExtractReferencesByContentWhenNoReferencesSectionTitle() {
        // 真实语料场景：Marker 把 REFERENCES 标题识别为空，参考文献落在无标题 section 的 ListBlock 中
        ListBlock refList = ListBlock.builder()
                .id("/page/6/List/1").sourceBlockId("/page/6/List/1").order(1)
                .items(new ArrayList<>(List.of(
                        "C. L. Avery et al., Heart Disease and Stroke Statistics, 2024 update.",
                        "M. P. Turakhia and A. Y. Ng, Cardiologist-level arrhythmia detection, 2019.",
                        "A. v. d. Oord et al., Representation learning with contrastive predictive coding, 2018.")))
                .build();
        AcademicDocument doc = document(section("Conclusions", refList));

        CleaningResult result = paperCleaner.clean(doc);

        assertThat(result.document().getReferences()).hasSize(3);
        // ListBlock 被抽走后该 section 已无正文块。
        // 注意：标题 "Conclusions" 属主章节锚点，依 canonical_section_float_repair 的新规则
        // 空锚点 section 不再删除（保住标题结构），因此这里断言"不再持有任何内容块"，
        // 而不是断言 section 被整体删除。
        assertThat(result.document().getSections())
                .allSatisfy(section -> assertThat(section.getBlocks()).isEmpty());
    }

    @Test
    void shouldNotMistakeNormalListForReferences() {
        ListBlock normalList = ListBlock.builder()
                .id("/page/1/List/1").sourceBlockId("/page/1/List/1").order(1)
                .items(new ArrayList<>(List.of("Encoder", "Projection head", "Classifier")))
                .build();
        AcademicDocument doc = document(section("Methods", normalList));

        CleaningResult result = paperCleaner.clean(doc);

        assertThat(result.document().getReferences()).isEmpty();
        // 普通列表保留
        assertThat(result.document().getSections().get(0).getBlocks()).hasSize(1);
    }

    @Test
    void shouldKeepNonReferenceItemsInMixedList() {
        // 混合列表：参考文献 + 续接正文；只抽走参考文献，正文保留
        ListBlock mixedList = ListBlock.builder()
                .id("/page/6/List/1").sourceBlockId("/page/6/List/1").order(1)
                .items(new ArrayList<>(List.of(
                        "biomedical time series. However, several limitations remain in our study.",
                        "C. L. Avery et al., Heart Disease and Stroke Statistics, 2024 update.",
                        "M. P. Turakhia and A. Y. Ng, Cardiologist-level arrhythmia detection, 2019.")))
                .build();
        AcademicDocument doc = document(section("Conclusions", mixedList));

        CleaningResult result = paperCleaner.clean(doc);

        assertThat(result.document().getReferences()).hasSize(2);
        // 非参考文献条目保留在正文
        assertThat(result.document().getSections().get(0).getBlocks()).hasSize(1);
        ListBlock remaining = (ListBlock) result.document().getSections().get(0).getBlocks().get(0);
        assertThat(remaining.getItems()).hasSize(1);
        assertThat(remaining.getItems().get(0)).contains("limitations remain");
    }

    // ==================== 6. BackMatterClassifier ====================

    @Test
    void shouldRemovePublisherNoteButKeepDataAvailability() {
        AcademicDocument doc = document(
                section("Results", paragraph("/page/1/Text/1", "Results body.", 1)),
                section("Data availability", paragraph("/page/24/Text/2", "Data at zenodo.org.", 24)),
                section("Publisher note", paragraph("/page/24/Text/3", "Publisher note text.", 24)));

        CleaningResult result = paperCleaner.clean(doc);

        List<String> titles = result.document().getSections().stream().map(Section::getTitle).toList();
        assertThat(titles).contains("Data availability");
        assertThat(titles).doesNotContain("Publisher note");
    }

    // ==================== 7. SectionHierarchyNormalizer ====================

    @Test
    void shouldNormalizeMergedSectionTitle() {
        AcademicDocument doc = document(
                section("Data scaling\nPretraining benefits",
                        paragraph("/page/5/Text/1", "Body.", 5)));

        CleaningResult result = paperCleaner.clean(doc);

        // 含换行的合并标题会被归一为单行（FrontMatterCleaner 标题清洗 / SectionHierarchyNormalizer）
        assertThat(result.warnings()).anyMatch(w -> w.contains("合并标题") || w.contains("标题尾部噪声"));
        assertThat(result.document().getMetadata().getTitle())
                .isEqualTo("Data scaling Pretraining benefits");
    }

    // ==================== 8. EmptyContentCleaner ====================

    @Test
    void shouldRemoveEmptySection() {
        AcademicDocument doc = document(
                section("Results", paragraph("/page/1/Text/1", "Body.", 1)),
                Section.builder().id("sec-empty").title("Empty").level(1)
                        .blocks(new ArrayList<>()).build());

        CleaningResult result = paperCleaner.clean(doc);

        assertThat(result.document().getSections()).hasSize(1);
        assertThat(result.validation().metrics().emptySectionCount()).isZero();
    }

    // ==================== 9. CanonicalDocumentValidator ====================

    @Test
    void shouldProduceQualityMetrics() {
        AcademicDocument doc = document(
                section("Results",
                        paragraph("/page/1/Text/1", "Body one.", 1),
                        paragraph("/page/2/Text/2", "Body two.", 2)));

        CleaningResult result = paperCleaner.clean(doc, 3); // repairCount = 3

        DocumentQualityMetrics metrics = result.validation().metrics();
        assertThat(metrics.sectionCount()).isEqualTo(1);
        assertThat(metrics.paragraphCount()).isEqualTo(2);
        assertThat(metrics.repairCount()).isEqualTo(3);
        assertThat(result.validation().valid()).isTrue();
    }

    @Test
    void shouldDetectBase64InFigureAsError() {
        FigureBlock badFigure = FigureBlock.builder()
                .id("/page/1/Figure/1")
                .imageKey("data:image/png;base64,iVBORw0KGgo=")
                .order(0)
                .build();
        AcademicDocument doc = document(section("Results", badFigure));

        CleaningResult result = paperCleaner.clean(doc);

        assertThat(result.validation().metrics().base64RemainingCount()).isEqualTo(1);
        assertThat(result.validation().valid()).isFalse();
        assertThat(result.validation().errors()).anyMatch(e -> e.contains("base64"));
    }

    @Test
    void shouldReportCrossPageMergeCount() {
        ParagraphBlock merged = ParagraphBlock.builder()
                .id("/page/3/Text/1")
                .sourceBlockIds(new ArrayList<>(List.of("/page/3/Text/1", "/page/4/Text/1")))
                .text("merged across pages")
                .order(0)
                .pageRange(PageRange.builder().startPage(3).endPage(4).build())
                .build();
        AcademicDocument doc = document(section("Methods", merged));

        CleaningResult result = paperCleaner.clean(doc, 1);

        assertThat(result.validation().metrics().crossPageMergeCount()).isEqualTo(1);
    }

    // ==================== 完整管道 ====================

    @Test
    void fullPipeline_producesCanonicalDocument() {
        FigureBlock figure = FigureBlock.builder()
                .id("/page/1/Figure/2")
                .sourceBlockIds(new ArrayList<>(List.of("/page/1/Figure/2")))
                .order(1)
                .pageRange(PageRange.builder().startPage(1).endPage(1).build())
                .build();
        ListBlock refList = ListBlock.builder()
                .id("/page/24/List/2").sourceBlockId("/page/24/List/2").order(1)
                .items(new ArrayList<>(List.of("1. Ribeiro et al. 2020.")))
                .build();

        AcademicDocument doc = document(
                section(null,
                        paragraph("/page/1/Text/1", "A Self-Supervised ECG Foundation Model", 1)),
                section("Abstract", paragraph("/page/1/Text/2", "We present a model.", 1)),
                section("Results",
                        noise("/page/1/PageHeader/3", "PAGE_HEADER"),
                        paragraph("/page/1/Text/4", "Our model works well.", 1),
                        figure,
                        CaptionBlock.builder().id("/page/1/Caption/5")
                                .sourceBlockId("/page/1/Caption/5").text("Figure 1 | Arch.").order(2).build(),
                        noise("/page/1/PageFooter/6", "PAGE_FOOTER")),
                section("References", refList),
                section("Publisher note", paragraph("/page/24/Text/7", "note", 24)),
                Section.builder().id("sec-empty").title("Empty").level(1)
                        .blocks(new ArrayList<>()).build());

        CleaningResult result = paperCleaner.clean(doc, 0);

        assertThat(result.valid()).isTrue();
        assertThat(result.document().getMetadata().getTitle()).contains("Self-Supervised");
        assertThat(result.document().getAbstractText()).isEqualTo("We present a model.");
        assertThat(result.document().getReferences()).hasSize(1);
        assertThat(result.document().getSections()).extracting(Section::getTitle)
                .contains("Results")
                .doesNotContain("Abstract", "References", "Publisher note", "Empty");

        DocumentQualityMetrics metrics = result.validation().metrics();
        assertThat(metrics.figureCount()).isEqualTo(1);
        assertThat(metrics.referenceCount()).isEqualTo(1);
        assertThat(metrics.headerFooterRemaining()).isZero();
        assertThat(metrics.emptySectionCount()).isZero();
    }
}
