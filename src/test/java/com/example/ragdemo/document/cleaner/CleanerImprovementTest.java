package com.example.ragdemo.document.cleaner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PageRange;
import com.example.ragdemo.document.domain.PaperMetadata;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.CaptionBlock;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.ListBlock;
import com.example.ragdemo.document.domain.block.NoiseBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;

/**
 * 清洗流水线改造的单元测试（skill §8 A）。
 * <p>
 * 覆盖：title OPEN 清洗、DOI 位于 NoiseBlock 仍可恢复、keywords 解析、authors 解析、
 * 位置式 abstract、caption 前置关联、reference 合并拆分、空段落清理。
 */
class CleanerImprovementTest {

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

    private ParagraphBlock p(String id, String text, int page) {
        return ParagraphBlock.builder().id(id).sourceBlockId(id).text(text).order(0)
                .pageRange(PageRange.builder().startPage(page).endPage(page).build()).build();
    }

    private Section section(String title, DocumentBlock... blocks) {
        return Section.builder().id("sec-" + (title == null ? "unnamed" : title)).title(title).level(1)
                .path(new ArrayList<>(title == null ? List.of() : List.of(title)))
                .blocks(new ArrayList<>(List.of(blocks))).build();
    }

    private AcademicDocument doc(Section... sections) {
        return AcademicDocument.builder().documentId("doc-1")
                .metadata(PaperMetadata.builder().build())
                .sections(new ArrayList<>(List.of(sections)))
                .references(new ArrayList<>()).build();
    }

    // ==================== 结构修复：空主章节 section 保留 + skill §9 指标 ====================

    @Test
    void shouldKeepEmptyMajorAnchorSection() {
        // 真实场景：Marker 把 Methods/Datasets/Pre-processing 标成同级 → Methods 拿不到子节点；
        // 若把它当"空 section"删掉，Methods 标题就彻底丢失。
        Section methods = section("Methods");
        AcademicDocument document = doc(
                section("A Paper Title About ECG", p("/page/1/Text/1", "Body text here.", 1)),
                methods);

        CleaningResult result = paperCleaner.clean(document);

        assertThat(result.document().getSections())
                .extracting(Section::getTitle)
                .contains("Methods");
        assertThat(result.warnings()).anyMatch(w -> w.startsWith("EMPTY_ANCHOR_SECTION_KEPT"));
    }

    @Test
    void shouldStillRemoveEmptyNonAnchorSection() {
        Section declarations = section("Declarations");
        AcademicDocument document = doc(
                section("A Paper Title About ECG", p("/page/1/Text/1", "Body text here.", 1)),
                declarations);

        CleaningResult result = paperCleaner.clean(document);

        assertThat(result.document().getSections())
                .extracting(Section::getTitle)
                .doesNotContain("Declarations");
    }

    @Test
    void shouldReportMajorHeadingNestedUnderMajorHeading() {
        Section discussion = section("Discussion", p("/page/7/Text/1", "Discussion body.", 7));
        discussion.setChildren(new ArrayList<>(List.of(section("Methods"))));
        AcademicDocument document = doc(discussion);

        CleaningResult result = paperCleaner.clean(document);

        assertThat(result.validation().metrics().majorHeadingNestedUnderMajorHeadingCount())
                .isGreaterThanOrEqualTo(1);
        assertThat(result.warnings())
                .anyMatch(w -> w.startsWith("MAJOR_HEADING_NESTED_UNDER_MAJOR_HEADING"));
    }

    // ==================== A. Title 清洗 ====================

    @Test
    void shouldStripOpenTailFromTitle() {
        AcademicDocument document = doc(section(
                "Toward robust automated cardiovascular arrhythmia detection using "
                        + "self-supervised learning and 1-dimensional vision transformers OPEN",
                p("/page/1/Text/1", "Body text here.", 1)));

        CleaningResult result = paperCleaner.clean(document);

        assertThat(result.document().getMetadata().getTitle())
                .isEqualTo("Toward robust automated cardiovascular arrhythmia detection using "
                        + "self-supervised learning and 1-dimensional vision transformers")
                .doesNotContain("OPEN");
    }

    @Test
    void shouldNotStripOpenInsideTitle() {
        // "OPEN" 不是末尾独立 token（正文中）。
        // 用 FrontMatterCleaner 的标题清洗方法直接验证
        FrontMatterCleaner cleaner = new FrontMatterCleaner();
        assertThat(cleaner.sanitizePaperTitle("An OPEN Access Study of ECG"))
                .isEqualTo("An OPEN Access Study of ECG");
    }

    // ==================== E. DOI 位于 NoiseBlock ====================

    @Test
    void shouldRecoverDoiFromNoiseBlock() {
        NoiseBlock footer = NoiseBlock.builder().id("/page/1/PageFooter/9").sourceBlockId("/page/1/PageFooter/9")
                .text("Scientific Reports | https://doi.org/10.1038/s41598-026-41549-5")
                .order(1).noiseType("PAGE_FOOTER")
                .pageRange(PageRange.builder().startPage(1).endPage(1).build()).build();
        AcademicDocument document = doc(section("A Paper Title About ECG", footer));

        CleaningResult result = paperCleaner.clean(document);

        assertThat(result.document().getMetadata().getDoi())
                .isEqualTo("10.1038/s41598-026-41549-5");
    }

    // ==================== B/D. Authors / Keywords ====================

    @Test
    void shouldExtractKeywords() {
        AcademicDocument document = doc(section("A Paper Title About ECG",
                p("/page/1/Text/1", "Keywords Cardiac arrhythmia, Deep learning, Electrocardiography", 1)));

        CleaningResult result = paperCleaner.clean(document);

        assertThat(result.document().getMetadata().getKeywords())
                .containsExactly("Cardiac arrhythmia", "Deep learning", "Electrocardiography");
    }

    @Test
    void shouldExtractAuthors() {
        AcademicDocument document = doc(section("A Paper Title About ECG",
                p("/page/1/Text/1", "Mitchell Chatterjee1, Adrian D. C. Chan2 & Majid Komeili1", 1)));

        CleaningResult result = paperCleaner.clean(document);

        assertThat(result.document().getMetadata().getAuthors())
                .extracting(a -> a.getFullName())
                .containsExactly("Mitchell Chatterjee", "Adrian D. C. Chan", "Majid Komeili");
    }

    // ==================== 位置式 Abstract ====================

    @Test
    void shouldRecoverPositionalAbstractWithoutKeyword() {
        String abstractText = "Cardiovascular diseases are the leading cause of death worldwide. "
                + "With electrocardiogram machines becoming more accessible, passive monitoring for "
                + "arrhythmia detection is now possible. This work presents a robust framework that "
                + "leverages self-supervised learning to improve arrhythmia detection accuracy.";

        AcademicDocument document = doc(section("Toward robust arrhythmia detection",
                p("/page/1/Text/1", "Mitchell Chatterjee, Majid Komeili", 1),
                p("/page/1/Text/2", abstractText, 1),
                p("/page/1/Text/3", "Keywords Arrhythmia, Deep learning", 1)));

        CleaningResult result = paperCleaner.clean(document);

        assertThat(result.document().getAbstractText()).isEqualTo(abstractText);
        assertThat(result.document().getMetadata().getKeywords()).contains("Arrhythmia");
    }

    // ==================== Caption 双向关联 ====================

    @Test
    void shouldAssociateCaptionPlacedBeforeTable() {
        TableBlock table = TableBlock.builder().id("/page/1/Table/1")
                .sourceBlockIds(new ArrayList<>(List.of("/page/1/Table/1"))).order(1)
                .pageRange(PageRange.builder().startPage(1).endPage(1).build()).build();
        CaptionBlock caption = CaptionBlock.builder().id("/page/1/Caption/0")
                .sourceBlockId("/page/1/Caption/0").text("TABLE II: Comparison of methods.").order(0).build();
        AcademicDocument document = doc(section("Results", caption, table));

        CleaningResult result = paperCleaner.clean(document);

        Section results = result.document().getSections().get(0);
        TableBlock cleaned = (TableBlock) results.getBlocks().stream()
                .filter(b -> b instanceof TableBlock).findFirst().orElseThrow();
        assertThat(cleaned.getCaption()).isEqualTo("TABLE II: Comparison of methods.");
        assertThat(cleaned.getSourceBlockIds()).contains("/page/1/Caption/0");
    }

    // ==================== Reference 合并拆分 ====================

    @Test
    void shouldSplitMergedReferenceItem() {
        ListBlock refList = ListBlock.builder().id("/page/24/List/1").sourceBlockId("/page/24/List/1").order(1)
                .items(new ArrayList<>(List.of(
                        "[1] A. Author, \"First paper title here,\" Journal A, 2020. "
                                + "[2] B. Author, \"Second paper title here,\" Journal B, 2021. "
                                + "[3] C. Author, \"Third paper title here,\" Journal C, 2022.")))
                .build();
        AcademicDocument document = doc(section("References", refList));

        CleaningResult result = paperCleaner.clean(document);

        assertThat(result.document().getReferences()).hasSize(3);
    }

    // ==================== 空段落清理 ====================

    @Test
    void shouldRemoveBlankParagraph() {
        AcademicDocument document = doc(section("Results",
                p("/page/1/Text/1", "Real content.", 1),
                p("/page/1/Text/2", "", 1)));

        CleaningResult result = paperCleaner.clean(document);

        assertThat(result.document().getSections().get(0).getBlocks()).hasSize(1);
    }

    // ==================== §9.1 Case 1: root title/path 同步 ====================

    @Test
    void shouldSyncRootTitleAndPathAfterSanitize() {
        AcademicDocument document = doc(section("A Study of ECG Signals OPEN",
                p("/page/1/Text/1", "Body content.", 1)));

        CleaningResult result = paperCleaner.clean(document);

        assertThat(result.document().getMetadata().getTitle()).isEqualTo("A Study of ECG Signals");
        Section root = result.document().getSections().get(0);
        assertThat(root.getTitle()).isEqualTo("A Study of ECG Signals").doesNotContain("OPEN");
        assertThat(root.getPath()).containsExactly("A Study of ECG Signals");
    }

    // ==================== §9.1 Case 2/3: 提取后 consume ====================

    @Test
    void shouldConsumeAuthorParagraphAfterExtraction() {
        AcademicDocument document = doc(section("A Paper Title About ECG",
                p("/page/1/Text/1", "Mitchell Chatterjee, Majid Komeili", 1),
                p("/page/1/Text/2", "Real body content here.", 1)));

        CleaningResult result = paperCleaner.clean(document);

        assertThat(result.document().getMetadata().getAuthors()).hasSize(2);
        assertThat(result.document().getSections().get(0).getBlocks())
                .noneMatch(b -> b instanceof ParagraphBlock px
                        && px.getText() != null && px.getText().contains("Mitchell Chatterjee"))
                .anyMatch(b -> b instanceof ParagraphBlock px
                        && px.getText() != null && px.getText().contains("Real body content"));
    }

    @Test
    void shouldConsumeKeywordsParagraphAfterExtraction() {
        AcademicDocument document = doc(section("A Paper Title About ECG",
                p("/page/1/Text/1", "Keywords Arrhythmia, Deep learning", 1),
                p("/page/1/Text/2", "Real body content here.", 1)));

        CleaningResult result = paperCleaner.clean(document);

        assertThat(result.document().getMetadata().getKeywords())
                .containsExactly("Arrhythmia", "Deep learning");
        assertThat(result.document().getSections().get(0).getBlocks())
                .noneMatch(b -> b instanceof ParagraphBlock px
                        && px.getText() != null && px.getText().startsWith("Keywords"));
    }

    // ==================== §9.1 Case 4: affiliation 拆分去重 ====================

    @Test
    void shouldSplitTwoAffiliationsWithoutDuplicate() {
        com.example.ragdemo.document.domain.block.FootnoteBlock footnote =
                com.example.ragdemo.document.domain.block.FootnoteBlock.builder()
                        .id("/page/1/Footnote/1").sourceBlockId("/page/1/Footnote/1")
                        .text("1School of Computer Science, Carleton University, Ottawa K1S 5B6, Canada. "
                                + "2Department of Systems and Computer Engineering, Carleton University, "
                                + "Ottawa K1S 5B6, Canada. email: mitchell@carleton.ca")
                        .order(0).pageRange(PageRange.builder().startPage(1).endPage(1).build())
                        .build();
        AcademicDocument document = doc(section("A Paper Title About ECG", footnote,
                p("/page/1/Text/2", "Real body content.", 1)));

        CleaningResult result = paperCleaner.clean(document);

        var affiliations = result.document().getMetadata().getAffiliations();
        assertThat(affiliations).hasSize(2);
        assertThat(affiliations).extracting(a -> a.getInstitution())
                .anyMatch(i -> i != null && i.contains("School of Computer Science"))
                .anyMatch(i -> i != null && i.contains("Department of Systems"));
        // footnote 已消费
        assertThat(result.document().getSections().get(0).getBlocks())
                .allMatch(b -> b instanceof ParagraphBlock);
    }

    // ==================== §9.2: 层级断层修复 ====================

    @Test
    void shouldReattachOrphanRootWithChildren() {
        Section ptbxl = Section.builder().id("s-ptbxl").title("PTB-XL multi-label classification").level(2)
                .path(new ArrayList<>(List.of("Comparison of 1-dimensional transformers",
                        "PTB-XL multi-label classification")))
                .blocks(new ArrayList<>(List.of(p("/page/3/Text/1", "PTB-XL results.", 3)))).build();
        Section comparison = Section.builder().id("s-cmp")
                .title("Comparison of 1-dimensional transformers").level(1)
                .path(new ArrayList<>(List.of("Comparison of 1-dimensional transformers")))
                .blocks(new ArrayList<>()).children(new ArrayList<>(List.of(ptbxl))).build();
        Section title = Section.builder().id("s-title").title("Paper Title").level(1)
                .path(new ArrayList<>(List.of("Paper Title")))
                .blocks(new ArrayList<>(List.of(p("/page/1/Text/1", "Body.", 1)))).build();
        AcademicDocument document = AcademicDocument.builder().documentId("doc-1")
                .metadata(PaperMetadata.builder().title("Paper Title").build())
                .sections(new ArrayList<>(List.of(title, comparison)))
                .references(new ArrayList<>()).build();

        CleaningResult result = paperCleaner.clean(document);

        // Comparison 不再是独立顶层 root
        assertThat(result.document().getSections()).extracting(Section::getTitle)
                .containsExactly("Paper Title");
        Section reattached = result.document().getSections().get(0).getChildren().stream()
                .filter(s -> "Comparison of 1-dimensional transformers".equals(s.getTitle()))
                .findFirst().orElseThrow();
        assertThat(reattached.getLevel()).isEqualTo(2);
        assertThat(reattached.getPath())
                .containsExactly("Paper Title", "Comparison of 1-dimensional transformers");
        // 子树同步
        assertThat(reattached.getChildren().get(0).getLevel()).isEqualTo(3);
    }

    @Test
    void shouldNotReattachUncertainRootWithoutChildren() {
        // "Novel framework" 无子节点 → 证据不足，保持 root（不强行重挂）
        Section novel = Section.builder().id("s-novel").title("Novel framework").level(1)
                .path(new ArrayList<>(List.of("Novel framework")))
                .blocks(new ArrayList<>(List.of(p("/page/9/Text/1", "Some content.", 9)))).build();
        Section title = Section.builder().id("s-title").title("Paper Title").level(1)
                .path(new ArrayList<>(List.of("Paper Title")))
                .blocks(new ArrayList<>(List.of(p("/page/1/Text/1", "Body.", 1)))).build();
        AcademicDocument document = AcademicDocument.builder().documentId("doc-1")
                .metadata(PaperMetadata.builder().title("Paper Title").build())
                .sections(new ArrayList<>(List.of(title, novel)))
                .references(new ArrayList<>()).build();

        CleaningResult result = paperCleaner.clean(document);

        assertThat(result.document().getSections()).extracting(Section::getTitle)
                .contains("Paper Title", "Novel framework");
    }

    // ==================== §9.3: Caption 类型不匹配 → 保留 ====================

    @Test
    void shouldKeepCaptionWhenTypeMismatch() {
        // caption 是 Fig.（Figure 类型），但紧邻目标是 Table → 不关联，保留 CaptionBlock
        TableBlock table = TableBlock.builder().id("/page/1/Table/1")
                .sourceBlockIds(new ArrayList<>(List.of("/page/1/Table/1"))).order(1)
                .pageRange(PageRange.builder().startPage(1).endPage(1).build()).build();
        CaptionBlock caption = CaptionBlock.builder().id("/page/1/Caption/0")
                .sourceBlockId("/page/1/Caption/0").text("Fig. 1. A figure caption.").order(0).build();
        AcademicDocument document = doc(section("Results", caption, table));

        CleaningResult result = paperCleaner.clean(document);

        Section results = result.document().getSections().get(0);
        assertThat(results.getBlocks()).anyMatch(b -> b instanceof CaptionBlock); // 保留
        TableBlock cleanedTable = (TableBlock) results.getBlocks().stream()
                .filter(b -> b instanceof TableBlock).findFirst().orElseThrow();
        assertThat(cleanedTable.getCaption()).isNull(); // 未被误挂
    }

    // ==================== §8.1: affiliation 尾部 email 标签 ====================

    @Test
    void shouldStripTrailingEmailLabelFromAffiliation() {
        FrontMatterCleaner cleaner = new FrontMatterCleaner();
        // Case 1：无地址，仅孤立标签
        assertThat(cleaner.stripCorrespondenceLabels(
                "Department of Systems and Computer Engineering, Carleton University, "
                        + "Ottawa K1S 5B6, Canada. email:"))
                .isEqualTo("Department of Systems and Computer Engineering, Carleton University, "
                        + "Ottawa K1S 5B6, Canada.");
        // Case 2：带真实 email 地址 → 地址与标签都不残留
        assertThat(cleaner.stripCorrespondenceLabels(
                "Carleton University, Ottawa, Canada. email: mitchell@carleton.ca"))
                .isEqualTo("Carleton University, Ottawa, Canada.");
    }

    // ==================== §8.2: null-title section 一致性 ====================

    @Test
    void shouldMakeNullTitleSectionConsistent() {
        Section untitled = Section.builder().id("s-untitled").title(null).level(2)
                .path(new ArrayList<>(List.of("Discussion")))
                .blocks(new ArrayList<>(List.of(p("/page/7/Text/1", "Body content here.", 7))))
                .build();
        Section discussion = Section.builder().id("s-discussion").title("Discussion").level(1)
                .path(new ArrayList<>(List.of("Discussion")))
                .blocks(new ArrayList<>())
                .children(new ArrayList<>(List.of(untitled))).build();
        AcademicDocument document = AcademicDocument.builder().documentId("doc-1")
                .metadata(PaperMetadata.builder().title("Paper Title").build())
                .sections(new ArrayList<>(List.of(discussion)))
                .references(new ArrayList<>()).build();

        CleaningResult result = paperCleaner.clean(document);

        Section cleaned = result.document().getSections().get(0).getChildren().get(0);
        // 不新增 title、不删除正文
        assertThat(cleaned.getTitle()).isNull();
        assertThat(cleaned.getBlocks()).hasSize(1);
        // 内部一致：level = parent.level + 1
        assertThat(cleaned.getLevel()).isEqualTo(result.document().getSections().get(0).getLevel() + 1);
    }

    // ==================== §8.3: caption 跨"短 block"关联 / 跨长正文不关联 ====================

    @Test
    void shouldAssociateCaptionAcrossShortBlock() {
        com.example.ragdemo.document.domain.block.FigureBlock figure =
                com.example.ragdemo.document.domain.block.FigureBlock.builder()
                        .id("/page/9/Figure/1")
                        .sourceBlockIds(new ArrayList<>(List.of("/page/9/Figure/1"))).order(0)
                        .pageRange(PageRange.builder().startPage(9).endPage(9).build()).build();
        CaptionBlock caption = CaptionBlock.builder().id("/page/9/Caption/2")
                .sourceBlockId("/page/9/Caption/2").text("Fig. 3. AUROC score plot.").order(2).build();
        AcademicDocument document = doc(section("Results", figure,
                p("/page/9/Text/1", "see", 9), caption));

        CleaningResult result = paperCleaner.clean(document);

        var cleanedFigure = (com.example.ragdemo.document.domain.block.FigureBlock)
                result.document().getSections().get(0).getBlocks().stream()
                        .filter(b -> b instanceof com.example.ragdemo.document.domain.block.FigureBlock)
                        .findFirst().orElseThrow();
        assertThat(cleanedFigure.getCaption()).isEqualTo("Fig. 3. AUROC score plot.");
        assertThat(result.document().getSections().get(0).getBlocks())
                .noneMatch(b -> b instanceof CaptionBlock);
    }

    @Test
    void shouldNotAssociateCaptionAcrossLongParagraph() {
        com.example.ragdemo.document.domain.block.FigureBlock figure =
                com.example.ragdemo.document.domain.block.FigureBlock.builder()
                        .id("/page/9/Figure/1")
                        .sourceBlockIds(new ArrayList<>(List.of("/page/9/Figure/1"))).order(0)
                        .pageRange(PageRange.builder().startPage(9).endPage(9).build()).build();
        CaptionBlock caption = CaptionBlock.builder().id("/page/9/Caption/2")
                .sourceBlockId("/page/9/Caption/2").text("Fig. 3. AUROC score plot.").order(2).build();
        AcademicDocument document = doc(section("Results", figure,
                p("/page/9/Text/1",
                        "This is a long body paragraph that must not be crossed by caption recovery "
                                + "because it is real content, not a short descriptive label.", 9),
                caption));

        CleaningResult result = paperCleaner.clean(document);

        // 跨长正文 → 不关联，保留 CaptionBlock
        assertThat(result.document().getSections().get(0).getBlocks())
                .anyMatch(b -> b instanceof CaptionBlock);
    }
}
