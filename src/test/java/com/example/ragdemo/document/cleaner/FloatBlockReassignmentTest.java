package com.example.ragdemo.document.cleaner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PageRange;
import com.example.ragdemo.document.domain.PaperMetadata;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;

/**
 * Figure/Table 浮动归属修复单测（skill §6 / §12）。
 * <p>
 * 核心契约：只依据<b>正文显式引用</b>（类型+编号完全匹配）移动；
 * 无引用 / 距离超限 / 不确定 → 不移动；移动不得改变 caption/content/sourceBlockIds/pageRange。
 */
class FloatBlockReassignmentTest {

    private final FloatBlockSectionReassigner reassigner = new FloatBlockSectionReassigner();

    // ==================== skill §12 基础 Case ====================

    @Test
    @DisplayName("Table：A 引用 Table 3，表格物理落在 B → 移回 A")
    void table_isReassignedToReferencingSection() {
        Section a = section("s-a", "Unified multi-label classification");
        a.setBlocks(blocks(paragraph("p1", "From the results in Table 3 the difference is 4%.", 1, 10)));
        Section b = section("s-b", "STEMI binary classification");
        b.setBlocks(blocks(table("t3", "Table 3", "Table 3. Performance results.", 1, 11)));

        FloatBlockSectionReassigner.Result result = reassigner.reassign(document(List.of(a, b)));

        assertThat(result.movedCount()).isEqualTo(1);
        assertThat(a.getBlocks()).extracting(DocumentBlock::getId).containsExactly("p1", "t3");
        assertThat(b.getBlocks()).isEmpty();
        FloatBlockSectionReassigner.Audit audit = result.audits().get(0);
        assertThat(audit.identifier()).isEqualTo("TABLE:3");
        assertThat(audit.oldSectionId()).isEqualTo("s-b");
        assertThat(audit.newSectionId()).isEqualTo("s-a");
        assertThat(audit.referenceBlockId()).isEqualTo("p1");
    }

    @Test
    @DisplayName("Figure：A 写 \"illustrated in Fig. 4\"，Figure 4 落在 B → 移回 A")
    void figure_isReassignedToReferencingSection() {
        Section a = section("s-a", "Baseline models");
        a.setBlocks(blocks(paragraph("p1", "The architecture is illustrated in Fig. 4.", 2, 10)));
        Section b = section("s-b", "PatchECG");
        b.setBlocks(blocks(figure("f4", "Figure 4", "Fig. 4. Overview of PatchECG.", 2, 12)));

        FloatBlockSectionReassigner.Result result = reassigner.reassign(document(List.of(a, b)));

        assertThat(result.movedCount()).isEqualTo(1);
        assertThat(result.audits().get(0).identifier()).isEqualTo("FIGURE:4");
        assertThat(a.getBlocks()).extracting(DocumentBlock::getId).containsExactly("p1", "f4");
    }

    // ==================== 不移动的场景 ====================

    @Test
    @DisplayName("无正文引用 → 不得自动移动")
    void figureWithoutReference_isNotMoved() {
        Section a = section("s-a", "Section A");
        a.setBlocks(blocks(paragraph("p1", "This section has no float reference at all.", 1, 10)));
        Section b = section("s-b", "Section B");
        b.setBlocks(blocks(figure("f5", "Figure 5", "Fig. 5. Some overview.", 1, 12)));

        FloatBlockSectionReassigner.Result result = reassigner.reassign(document(List.of(a, b)));

        assertThat(result.movedCount()).isZero();
        assertThat(b.getBlocks()).extracting(DocumentBlock::getId).containsExactly("f5");
    }

    @Test
    @DisplayName("引用与浮动块距离超限 → 只告警不移动")
    void referenceTooFarAway_isNotMoved() {
        Section a = section("s-a", "Introduction");
        a.setBlocks(blocks(paragraph("p1", "As shown in Table 3 earlier work reported gains.", 1, 10)));
        Section b = section("s-b", "Results");
        b.setBlocks(blocks(table("t3", "Table 3", "Table 3. Performance results.", 9, 90)));

        FloatBlockSectionReassigner.Result result = reassigner.reassign(document(List.of(a, b)));

        assertThat(result.movedCount()).isZero();
        assertThat(b.getBlocks()).extracting(DocumentBlock::getId).containsExactly("t3");
        assertThat(result.warnings()).anyMatch(w -> w.startsWith("FLOAT_REASSIGN_SKIPPED_BY_DISTANCE"));
    }

    @Test
    @DisplayName("引用与浮动块已在同一 Section → 不移动、无审计")
    void alreadyConsistent_isNotMoved() {
        Section a = section("s-a", "Section A");
        a.setBlocks(blocks(
                paragraph("p1", "Results are shown in Table 3.", 1, 10),
                table("t3", "Table 3", "Table 3. Performance results.", 1, 11)));

        FloatBlockSectionReassigner.Result result = reassigner.reassign(document(List.of(a)));

        assertThat(result.movedCount()).isZero();
        assertThat(result.warnings()).isEmpty();
    }

    // ==================== 多引用 ====================

    @Test
    @DisplayName("多引用：优先首次定义性引用；跨 Section 的另一处普通引用不抢优先级")
    void multipleReferences_prefersDefinitionalReference() {
        Section a = section("s-a", "Section A");
        a.setBlocks(blocks(paragraph("p1", "The pipeline is shown in Fig. 4 for clarity.", 3, 10)));
        Section b = section("s-b", "Section B");
        b.setBlocks(blocks(paragraph("p2", "We follow the setup of Fig. 4 in prior work.", 3, 20)));
        Section c = section("s-c", "Section C");
        c.setBlocks(blocks(figure("f4", "Figure 4", "Fig. 4. Overview of PatchECG.", 3, 30)));

        FloatBlockSectionReassigner.Result result = reassigner.reassign(document(List.of(a, b, c)));

        assertThat(result.movedCount()).isEqualTo(1);
        assertThat(result.audits().get(0).newSectionId()).isEqualTo("s-a");
        assertThat(result.audits().get(0).reason()).isEqualTo("definitional-reference");
    }

    // ==================== 编号严格性 ====================

    @Test
    @DisplayName("类型+编号必须完全匹配：Table S3 的引用不会移动 Table 3")
    void supplementaryNumber_doesNotMatchMainNumber() {
        Section a = section("s-a", "Section A");
        a.setBlocks(blocks(paragraph("p1", "Supplementary results are in Table S3.", 1, 10)));
        Section b = section("s-b", "Section B");
        b.setBlocks(blocks(table("t3", "Table 3", "Table 3. Main results.", 1, 12)));

        FloatBlockSectionReassigner.Result result = reassigner.reassign(document(List.of(a, b)));

        assertThat(result.movedCount()).isZero();
        assertThat(b.getBlocks()).extracting(DocumentBlock::getId).containsExactly("t3");
    }

    // ==================== provenance 不变 ====================

    @Test
    @DisplayName("移动只改归属 Section：caption/content/sourceBlockIds/pageRange/order 一律不变")
    void reassignment_preservesProvenance() {
        Section a = section("s-a", "Section A");
        a.setBlocks(blocks(paragraph("p1", "See Table 3 for details.", 5, 10)));
        TableBlock table = table("t3", "Table 3", "Table 3. Performance results.", 5, 11);
        Section b = section("s-b", "Section B");
        b.setBlocks(blocks(table));

        reassigner.reassign(document(List.of(a, b)));

        TableBlock moved = (TableBlock) a.getBlocks().stream()
                .filter(block -> block.getId().equals("t3")).findFirst().orElseThrow();
        assertThat(moved.getCaption()).isEqualTo("Table 3. Performance results.");
        assertThat(moved.getContent()).isEqualTo("h1\th2\nv1\tv2");
        assertThat(moved.getHtml()).contains("<table>");
        assertThat(moved.getSourceBlockIds()).containsExactly("t3");
        assertThat(moved.getPageRange().getStartPage()).isEqualTo(5);
        assertThat(moved.getPageRange().getEndPage()).isEqualTo(5);
        assertThat(moved.getOrder()).isEqualTo(11);
    }

    @Test
    @DisplayName("移动后按原全局 order 稳定排序，文档顺序不乱")
    void blocks_areResortedByOriginalOrder() {
        Section a = section("s-a", "Section A");
        a.setBlocks(blocks(
                paragraph("p-first", "Intro text referencing Table 3.", 1, 10),
                paragraph("p-last", "Later paragraph.", 1, 30)));
        Section b = section("s-b", "Section B");
        b.setBlocks(blocks(
                table("t3", "Table 3", "Table 3. Results.", 1, 20),
                paragraph("p-b2", "Another paragraph in B.", 1, 40)));

        reassigner.reassign(document(List.of(a, b)));

        assertThat(a.getBlocks()).extracting(DocumentBlock::getId)
                .containsExactly("p-first", "t3", "p-last");
        assertThat(b.getBlocks()).extracting(DocumentBlock::getId).containsExactly("p-b2");
    }

    // ==================== 构造工具 ====================

    private Section section(String id, String title) {
        return Section.builder()
                .id(id)
                .sourceBlockId(id)
                .title(title)
                .level(1)
                .path(new ArrayList<>(List.of(title)))
                .blocks(new ArrayList<>())
                .build();
    }

    private ArrayList<DocumentBlock> blocks(DocumentBlock... blockArray) {
        return new ArrayList<>(List.of(blockArray));
    }

    private ParagraphBlock paragraph(String id, String text, int startPage, int order) {
        return ParagraphBlock.builder()
                .id(id)
                .sourceBlockId(id)
                .sourceBlockIds(new ArrayList<>(List.of(id)))
                .text(text)
                .order(order)
                .pageRange(new PageRange(startPage, startPage))
                .build();
    }

    private FigureBlock figure(String id, String label, String caption, int startPage, int order) {
        return FigureBlock.builder()
                .id(id)
                .sourceBlockIds(new ArrayList<>(List.of(id)))
                .label(label)
                .caption(caption)
                .order(order)
                .pageRange(new PageRange(startPage, startPage))
                .build();
    }

    private TableBlock table(String id, String label, String caption, int startPage, int order) {
        return TableBlock.builder()
                .id(id)
                .sourceBlockIds(new ArrayList<>(List.of(id)))
                .label(label)
                .caption(caption)
                .html("<table><tr><td>v1</td><td>v2</td></tr></table>")
                .content("h1\th2\nv1\tv2")
                .order(order)
                .pageRange(new PageRange(startPage, startPage))
                .build();
    }

    private AcademicDocument document(List<Section> sections) {
        return AcademicDocument.builder()
                .documentId("doc")
                .metadata(PaperMetadata.builder().title("Test Paper").build())
                .sections(new ArrayList<>(sections))
                .references(new ArrayList<>())
                .build();
    }
}
