package com.example.ragdemo.document.repair;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PageRange;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.NoiseBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;

/**
 * ReadingOrderRepair 单元测试（skill §31 Layer 3）。
 * <p>
 * 覆盖：跨页 sentence continuation、Figure 打断、PageHeader/Footer 打断、
 * figure-associated text 保护、maxSkipBlocks 上限、audit 可追踪。
 */
class ReadingOrderRepairTest {

    private ReadingOrderRepair repair;

    @BeforeEach
    void setUp() {
        ReadingOrderRepairConfig config = new ReadingOrderRepairConfig();
        config.setMaxSkipBlocks(8);
        config.setTransparentNoiseEnabled(true);
        config.setFigureAssociationEnabled(true);
        repair = new ReadingOrderRepair(config, new FigureAssociatedTextDetector());
    }

    private ParagraphBlock paragraph(String id, String text, int page) {
        return ParagraphBlock.builder()
                .id(id)
                .sourceBlockId(id)
                .sourceBlockIds(new ArrayList<>(List.of(id)))
                .text(text)
                .order(0)
                .pageRange(PageRange.builder().startPage(page).endPage(page).build())
                .build();
    }

    private NoiseBlock footer(String id, String text, int page) {
        return NoiseBlock.builder()
                .id(id).sourceBlockId(id).text(text).order(0)
                .pageRange(PageRange.builder().startPage(page).endPage(page).build())
                .noiseType("PAGE_FOOTER")
                .build();
    }

    private FigureBlock figure(String id, int page) {
        return FigureBlock.builder()
                .id(id).order(0)
                .pageRange(PageRange.builder().startPage(page).endPage(page).build())
                .build();
    }

    private AcademicDocument document(DocumentBlock... blocks) {
        Section section = Section.builder()
                .id("sec-1")
                .title("Methods")
                .level(1)
                .path(new ArrayList<>(List.of("Methods")))
                .blocks(new ArrayList<>(List.of(blocks)))
                .build();
        return AcademicDocument.builder()
                .documentId("doc-1")
                .sections(new ArrayList<>(List.of(section)))
                .build();
    }

    // ==================== 跨页续接 ====================

    @Test
    void shouldMergeAcrossPageFooterNoise() {
        ParagraphBlock a = paragraph("/page/3/Text/1",
                "The patients were admitted to the hospital and monitored for", 3);
        NoiseBlock noise = footer("/page/3/PageFooter/2", "Page 3 of 29", 3);
        ParagraphBlock b = paragraph("/page/4/Text/1",
                "twelve hours before the procedure.", 4);

        AcademicDocument doc = document(a, noise, b);
        List<ReadingOrderRepairAudit> audits = repair.repair(doc);

        Section section = doc.getSections().get(0);
        assertThat(section.getBlocks()).hasSize(1);
        ParagraphBlock merged = (ParagraphBlock) section.getBlocks().get(0);
        assertThat(merged.getText())
                .isEqualTo("The patients were admitted to the hospital and monitored for "
                        + "twelve hours before the procedure.");
        // 跨页后 pageRange 跨度变大
        assertThat(merged.getPageRange().getStartPage()).isEqualTo(3);
        assertThat(merged.getPageRange().getEndPage()).isEqualTo(4);
        // block 追踪
        assertThat(merged.getSourceBlockIds())
                .containsExactly("/page/3/Text/1", "/page/4/Text/1");
        // audit
        assertThat(audits).hasSize(1);
        ReadingOrderRepairAudit audit = audits.get(0);
        assertThat(audit.leftBlockId()).isEqualTo("/page/3/Text/1");
        assertThat(audit.rightBlockId()).isEqualTo("/page/4/Text/1");
        assertThat(audit.skippedBlockIds()).containsExactly("/page/3/PageFooter/2");
    }

    @Test
    void shouldMergeAcrossFigureInterruption() {
        ParagraphBlock a = paragraph("/page/3/Text/1",
                "We evaluated the model on six public datasets and observed", 3);
        FigureBlock fig = figure("/page/3/Figure/2", 3);
        ParagraphBlock b = paragraph("/page/3/Text/3",
                "consistent improvements across all benchmarks.", 3);

        AcademicDocument doc = document(a, fig, b);
        List<ReadingOrderRepairAudit> audits = repair.repair(doc);

        // 段落合并为一个，但中间的 Figure 必须保留（不得连带删除）
        assertThat(doc.getSections().get(0).getBlocks()).hasSize(2);
        assertThat(doc.getSections().get(0).getBlocks())
                .anyMatch(block -> block instanceof com.example.ragdemo.document.domain.block.FigureBlock);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).skippedBlockIds()).containsExactly("/page/3/Figure/2");
    }

    // ==================== 不应合并 ====================

    @Test
    void shouldNotMergeWhenLeftEndsWithSentencePunctuation() {
        ParagraphBlock a = paragraph("/page/3/Text/1",
                "This is a complete sentence.", 3);
        NoiseBlock noise = footer("/page/3/PageFooter/2", "Page 3", 3);
        ParagraphBlock b = paragraph("/page/4/Text/1",
                "another complete sentence.", 4);

        AcademicDocument doc = document(a, noise, b);
        List<ReadingOrderRepairAudit> audits = repair.repair(doc);

        assertThat(doc.getSections().get(0).getBlocks()).hasSize(3);
        assertThat(audits).isEmpty();
    }

    @Test
    void shouldNotMergeWhenRightStartsUppercase() {
        ParagraphBlock a = paragraph("/page/3/Text/1",
                "The patients were admitted to the hospital and monitored for", 3);
        NoiseBlock noise = footer("/page/3/PageFooter/2", "Page 3", 3);
        ParagraphBlock b = paragraph("/page/4/Text/1",
                "Twelve hours before the procedure.", 4);

        AcademicDocument doc = document(a, noise, b);
        List<ReadingOrderRepairAudit> audits = repair.repair(doc);

        assertThat(doc.getSections().get(0).getBlocks()).hasSize(3);
        assertThat(audits).isEmpty();
    }

    @Test
    void shouldNotMergeFigureAssociatedText() {
        ParagraphBlock a = paragraph("/page/3/Text/1",
                "The architecture consists of three components as illustrated", 3);
        FigureBlock fig = figure("/page/3/Figure/2", 3);
        ParagraphBlock b = paragraph("/page/3/Text/3",
                "from left to right, the encoder, projection head and classifier.", 3);

        AcademicDocument doc = document(a, fig, b);
        List<ReadingOrderRepairAudit> audits = repair.repair(doc);

        // figure-associated text 必须保留，不得并入正文
        assertThat(doc.getSections().get(0).getBlocks()).hasSize(3);
        assertThat(audits).isEmpty();
    }

    @Test
    void shouldNotMergeWhenTooManySkippedBlocks() {
        ParagraphBlock a = paragraph("/page/3/Text/1",
                "The patients were admitted to the hospital and monitored for", 3);
        List<DocumentBlock> blocks = new ArrayList<>();
        blocks.add(a);
        // 9 个透明块 > maxSkipBlocks(8)
        for (int i = 0; i < 9; i++) {
            blocks.add(footer("/page/3/PageFooter/" + i, "footer " + i, 3));
        }
        blocks.add(paragraph("/page/4/Text/1", "twelve hours before the procedure.", 4));

        Section section = Section.builder()
                .id("sec-1").title("Methods").level(1)
                .path(new ArrayList<>(List.of("Methods")))
                .blocks(blocks)
                .build();
        AcademicDocument doc = AcademicDocument.builder()
                .documentId("doc-1")
                .sections(new ArrayList<>(List.of(section)))
                .build();

        List<ReadingOrderRepairAudit> audits = repair.repair(doc);

        assertThat(audits).isEmpty();
        // 超过窗口的块保持原样
        assertThat(doc.getSections().get(0).getBlocks()).hasSize(blocks.size());
    }

    @Test
    void shouldNotMergeAcrossNonTransparentBlock() {
        ParagraphBlock a = paragraph("/page/3/Text/1",
                "The patients were admitted to the hospital and monitored for", 3);
        // 另一个段落（非透明）会中断扫描
        ParagraphBlock blocker = paragraph("/page/3/Text/2",
                "This is a separate conceptual paragraph.", 3);
        ParagraphBlock b = paragraph("/page/4/Text/1",
                "twelve hours before the procedure.", 4);

        AcademicDocument doc = document(a, blocker, b);
        List<ReadingOrderRepairAudit> audits = repair.repair(doc);

        assertThat(audits).isEmpty();
        assertThat(doc.getSections().get(0).getBlocks()).hasSize(3);
    }

    // ==================== 配置开关 ====================

    @Test
    void shouldRespectTransparentNoiseDisabled() {
        ReadingOrderRepairConfig config = new ReadingOrderRepairConfig();
        config.setMaxSkipBlocks(8);
        config.setTransparentNoiseEnabled(false); // 噪声不可跳过
        config.setFigureAssociationEnabled(true);
        ReadingOrderRepair strictRepair =
                new ReadingOrderRepair(config, new FigureAssociatedTextDetector());

        AcademicDocument doc = document(
                paragraph("/page/3/Text/1",
                        "The patients were admitted to the hospital and monitored for", 3),
                footer("/page/3/PageFooter/2", "Page 3", 3),
                paragraph("/page/4/Text/1", "twelve hours before the procedure.", 4));

        List<ReadingOrderRepairAudit> audits = strictRepair.repair(doc);

        assertThat(audits).isEmpty();
    }

    @Test
    void shouldHandleNullDocumentGracefully() {
        assertThat(repair.repair(null)).isEmpty();
    }
}
