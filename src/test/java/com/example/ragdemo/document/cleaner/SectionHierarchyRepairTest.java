package com.example.ragdemo.document.cleaner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PaperMetadata;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.ParagraphBlock;

/**
 * Section 层级纠偏单测（skill §5.2 / §11）。
 * <p>
 * 核心契约：
 * <ul>
 *   <li>只有<b>编号深度相同</b>的错误父子才提级；无编号、编号深度不同、单字母罗马歧义一律不动</li>
 *   <li>{@code rawLevel} 相同<b>不是</b>提级依据（实测多数为合法父子）</li>
 *   <li>提级后必须重建该子树（含后代）的 level / path</li>
 * </ul>
 */
class SectionHierarchyRepairTest {

    private final SectionHierarchyRepair repair = new SectionHierarchyRepair();

    // ==================== 应修复 ====================

    @Test
    @DisplayName("编号深度相同（2.1 / 2.2）的错误父子 → 提为同级")
    void numberedSameDepth_isPromotedToSiblings() {
        Section parent = section("s-a", "2.1 Pre-processing", null, body("p1"));
        Section child = section("s-b", "2.2 Baseline models", null, body("p2"));
        parent.setChildren(new ArrayList<>(List.of(child)));
        AcademicDocument document = document(parent);

        SectionHierarchyRepair.Result result = repair.repair(document);

        assertThat(result.promotedCount()).isEqualTo(1);
        assertThat(document.getSections()).extracting(Section::getId)
                .containsExactly("s-a", "s-b");
        assertThat(parent.getChildren()).isEmpty();
        assertThat(result.audits().get(0).title()).isEqualTo("2.2 Baseline models");
        assertThat(result.audits().get(0).oldParentId()).isEqualTo("s-a");
        assertThat(result.warnings()).anyMatch(w -> w.startsWith("SIBLING_REPAIRED_BY_NUMBERING"));
    }

    @Test
    @DisplayName("附录编号 A.1 / A.2 同深度 → 提为同级")
    void appendixNumbering_isPromoted() {
        Section parent = section("s-a1", "A.1 Pretraining on a Larger ECG Dataset", null, body("p1"));
        Section child = section("s-a2", "A.2 Effect of Learning Rates", null, body("p2"));
        parent.setChildren(new ArrayList<>(List.of(child)));
        AcademicDocument document = document(section("root", "Paper Title", null, body("p0")));
        document.getSections().get(0).setChildren(new ArrayList<>(List.of(parent)));
        parent.setChildren(new ArrayList<>(List.of(child)));

        repair.repair(document);

        Section root = document.getSections().get(0);
        assertThat(root.getChildren()).extracting(Section::getId).containsExactly("s-a1", "s-a2");
        assertThat(parent.getChildren()).isEmpty();
    }

    @Test
    @DisplayName("root 下的同编号深度父子 → 子提为 root，并重建 level/path")
    void promotionFromRootLevel_movesChildToDocumentRoot() {
        // 典型错挂：两个一级章节被标成父子
        Section parent = section("s-root1", "2 Results", null, body("p1"));
        Section child = section("s-root2", "3 Discussion", null, body("p2"));
        parent.setChildren(new ArrayList<>(List.of(child)));
        AcademicDocument document = document(parent);

        repair.repair(document);

        assertThat(document.getSections()).extracting(Section::getId)
                .containsExactly("s-root1", "s-root2");
        assertThat(child.getLevel()).isEqualTo(1);
        assertThat(child.getPath()).containsExactly("3 Discussion");
    }

    @Test
    @DisplayName("提级后重建整棵子树的 level / path")
    void levelAndPathAreRebuiltForSubtree() {
        Section parent = section("s-p", "2.1 Pre-processing", null, body("p1"));
        Section promoted = section("s-c", "2.2 Baseline models", null, body("p2"));
        Section grandChild = section("s-gc", "PatchECG", null, body("p3"));
        promoted.setChildren(new ArrayList<>(List.of(grandChild)));
        parent.setChildren(new ArrayList<>(List.of(promoted)));
        AcademicDocument document = document(section("root", "Paper Title", null, body("p0")));
        document.getSections().get(0).setChildren(new ArrayList<>(List.of(parent)));

        repair.repair(document);

        assertThat(promoted.getLevel()).isEqualTo(2); // root(1) + 1
        assertThat(promoted.getPath()).containsExactly("Paper Title", "2.2 Baseline models");
        assertThat(grandChild.getLevel()).isEqualTo(3);
        assertThat(grandChild.getPath())
                .containsExactly("Paper Title", "2.2 Baseline models", "PatchECG");
    }

    // ==================== 不得误修 ====================

    @Test
    @DisplayName("无编号的真实父子（Methods > Pre-processing）不得被提级")
    void unnumberedRealParentChild_isNotBroken() {
        Section parent = section("m", "Methods", null, body("p1"));
        Section child = section("pp", "Pre-processing", null, body("p2"));
        parent.setChildren(new ArrayList<>(List.of(child)));
        AcademicDocument document = document(parent);

        SectionHierarchyRepair.Result result = repair.repair(document);

        assertThat(result.promotedCount()).isZero();
        assertThat(parent.getChildren()).extracting(Section::getId).containsExactly("pp");
    }

    @Test
    @DisplayName("编号深度不同的真实父子（3 Method > 3.1 Event Reconstruction）不得被提级")
    void differentNumberedDepth_isNotBroken() {
        Section parent = section("m", "3 Method", null, body("p1"));
        Section child = section("c", "3.1 Event Reconstruction", null, body("p2"));
        parent.setChildren(new ArrayList<>(List.of(child)));
        AcademicDocument document = document(parent);

        SectionHierarchyRepair.Result result = repair.repair(document);

        assertThat(result.promotedCount()).isZero();
        assertThat(parent.getChildren()).extracting(Section::getId).containsExactly("c");
    }

    @Test
    @DisplayName("单字母罗马歧义（II. METHODS > C. Dataset）不得被提级")
    void singleLetterRomanAmbiguity_isNotBroken() {
        Section parent = section("m", "II. METHODS", null, body("p1"));
        Section child = section("c", "C. Dataset", null, body("p2"));
        parent.setChildren(new ArrayList<>(List.of(child)));
        AcademicDocument document = document(parent);

        SectionHierarchyRepair.Result result = repair.repair(document);

        // "C." 在标题非全大写时判为字母子节（深度 2）> 罗马 "II."（深度 1）→ 合法父子
        assertThat(result.promotedCount()).isZero();
        assertThat(parent.getChildren()).extracting(Section::getId).containsExactly("c");
    }

    @Test
    @DisplayName("rawLevel 相同但无编号 → 只告警，不提级（实测多数为合法父子）")
    void sameRawLevelButUnnumbered_isOnlyWarned() {
        Section parent = section("m", "Experiments", 2, body("p1"));
        Section child = section("c", "Pretraining Dataset", 2, body("p2"));
        parent.setChildren(new ArrayList<>(List.of(child)));
        AcademicDocument document = document(parent);

        SectionHierarchyRepair.Result result = repair.repair(document);

        assertThat(result.promotedCount()).isZero();
        assertThat(parent.getChildren()).extracting(Section::getId).containsExactly("c");
        assertThat(result.warnings())
                .anyMatch(w -> w.startsWith("SAME_RAW_LEVEL_BUT_NESTED"));
    }

    @Test
    @DisplayName("rawLevel 与编号冲突时仍按编号提级，并在审计中标记 rawLevelConflict")
    void rawLevelConflict_isRecordedButNumberingWins() {
        Section parent = section("s-a1", "A.1 Pretraining", 2, body("p1"));
        Section child = section("s-a2", "A.2 Effect of Learning Rates", 3, body("p2"));
        parent.setChildren(new ArrayList<>(List.of(child)));
        AcademicDocument document = document(parent);

        SectionHierarchyRepair.Result result = repair.repair(document);

        assertThat(result.promotedCount()).isEqualTo(1);
        assertThat(result.audits().get(0).rawLevelConflict()).isTrue();
        assertThat(result.audits().get(0).parentRawLevel()).isEqualTo(2);
        assertThat(result.audits().get(0).childRawLevel()).isEqualTo(3);
    }

    // ==================== Rule 2（A2）：子编号比父编号更浅 ====================

    @Test
    @DisplayName("A2：子编号比父更浅（4.4.6. > 4.6. / 4.7.）→ 挂回编号前缀对应的父节")
    void shallowerChild_isReparentedToNumberingPrefix() {
        Section results = section("r", "4. Experiments and results", null, body("p0"));
        Section ablation = section("ab", "4.4. Ablation studies", null, body("p1"));
        results.setChildren(new ArrayList<>(List.of(ablation)));
        results.setOrder(10);
        ablation.setOrder(11);

        Section deepParent = section("dp", "4.4.6. Ablations on the weight assignment method of WCE",
                null, body("p2"));
        Section c1 = section("c1", "4.6. Model robustness under noise introduction", null, body("p3"));
        Section c2 = section("c2", "4.7. Visualization", null, body("p4"));
        deepParent.setChildren(new ArrayList<>(List.of(c1, c2)));
        deepParent.setOrder(12);
        c1.setOrder(13);
        c2.setOrder(20);

        AcademicDocument document = document(results, deepParent);

        SectionHierarchyRepair.Result result = repair.repair(document);

        assertThat(result.shallowerReparentedCount()).isEqualTo(2);
        // 按文档 order 插入：ab(11) → c1(13) → c2(20)
        assertThat(results.getChildren()).extracting(Section::getId)
                .containsExactly("ab", "c1", "c2");
        assertThat(deepParent.getChildren()).isEmpty();
        // level / path 重建
        assertThat(c1.getLevel()).isEqualTo(2);
        assertThat(c1.getPath()).containsExactly("4. Experiments and results",
                "4.6. Model robustness under noise introduction");
        assertThat(result.warnings()).anyMatch(w -> w.startsWith("SHALLOWER_CHILD_REPARENTED"));
    }

    @Test
    @DisplayName("A2：找不到编号前缀对应的父节 → 不动 + SHALLOWER_CHILD_UNRESOLVED 告警")
    void shallowerChild_withoutNumberingParent_isNotMoved() {
        Section deepParent = section("dp", "4.4.6. Ablations on the weight assignment method of WCE",
                null, body("p2"));
        Section c1 = section("c1", "4.6. Model robustness under noise introduction", null, body("p3"));
        deepParent.setChildren(new ArrayList<>(List.of(c1)));

        AcademicDocument document = document(deepParent);

        SectionHierarchyRepair.Result result = repair.repair(document);

        assertThat(result.shallowerReparentedCount()).isZero();
        assertThat(deepParent.getChildren()).extracting(Section::getId).containsExactly("c1");
        assertThat(result.warnings()).anyMatch(w -> w.startsWith("SHALLOWER_CHILD_UNRESOLVED"));
    }

    @Test
    @DisplayName("A2：目标父节位于自身子树内 → 不动（防环）")
    void shallowerChild_targetInsideOwnSubtree_isNotMoved() {
        Section deepParent = section("dp", "4.4.6. Ablations on the weight assignment method of WCE",
                null, body("p2"));
        Section c1 = section("c1", "4.6. Model robustness under noise introduction", null, body("p3"));
        Section nested = section("n", "4. Nested", null, body("p4"));
        c1.setChildren(new ArrayList<>(List.of(nested)));
        deepParent.setChildren(new ArrayList<>(List.of(c1)));

        AcademicDocument document = document(deepParent);

        SectionHierarchyRepair.Result result = repair.repair(document);

        assertThat(result.shallowerReparentedCount()).isZero();
        assertThat(deepParent.getChildren()).extracting(Section::getId).containsExactly("c1");
    }

    @Test
    @DisplayName("A2：罗马 / 字母编号不参与前缀运算 → 不动")
    void nonNumericNumbering_isNotMoved() {
        Section letterParent = section("lp", "A. Appendix", null, body("p1"));
        Section romanChild = section("rc", "I. INTRODUCTION", null, body("p2"));
        letterParent.setChildren(new ArrayList<>(List.of(romanChild)));

        AcademicDocument document = document(letterParent);

        SectionHierarchyRepair.Result result = repair.repair(document);

        assertThat(result.shallowerReparentedCount()).isZero();
        assertThat(letterParent.getChildren()).extracting(Section::getId).containsExactly("rc");
    }

    @Test
    @DisplayName("A2：子比父更深（正常嵌套）→ 不动")
    void deeperChild_isNotTouched() {
        Section parent = section("p", "4. Experiments and results", null, body("p1"));
        Section child = section("c", "4.1. Datasets", null, body("p2"));
        parent.setChildren(new ArrayList<>(List.of(child)));

        AcademicDocument document = document(parent);

        SectionHierarchyRepair.Result result = repair.repair(document);

        assertThat(result.promotedCount()).isZero();
        assertThat(parent.getChildren()).extracting(Section::getId).containsExactly("c");
    }

    // ==================== 构造工具 ====================

    private Section section(String id, String title, Integer rawLevel, ParagraphBlock body) {
        return Section.builder()
                .id(id)
                .sourceBlockId(id)
                .title(title)
                .level(1)
                .rawLevel(rawLevel)
                .path(new ArrayList<>(List.of(title)))
                .blocks(new ArrayList<>(List.of(body)))
                .children(new ArrayList<>())
                .build();
    }

    private ParagraphBlock body(String id) {
        return ParagraphBlock.builder()
                .id(id)
                .sourceBlockId(id)
                .sourceBlockIds(new ArrayList<>(List.of(id)))
                .text("Body text of " + id + ".")
                .build();
    }

    private AcademicDocument document(Section... sections) {
        return AcademicDocument.builder()
                .documentId("doc")
                .metadata(PaperMetadata.builder().title("Paper Title").build())
                .sections(new ArrayList<>(List.of(sections)))
                .references(new ArrayList<>())
                .build();
    }
}
