package com.example.ragdemo.document.cleaner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PaperMetadata;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;

/**
 * 主章节锚点保守重挂单测（skill §4 Rule 4 收敛版）。
 * <p>
 * 核心契约：
 * <ul>
 *   <li>只有「空得只剩标题的锚点」被错嵌在「另一个锚点」之下、且有可收编同级时才重挂</li>
 *   <li>重挂 = 提升为 root + 收编其后连续同级（rawLevel 相同、且遇到下一个锚点标题即停）</li>
 *   <li>锚点子节带正文 / 父非锚点 / 无 rawLevel / 无可收编同级 → 一律不动</li>
 *   <li>重挂后必须重建被收编子树的 level / path</li>
 * </ul>
 */
class MajorAnchorReparentingTest {

    private final MajorAnchorReparenting reparenting = new MajorAnchorReparenting();

    // ==================== 应修复 ====================

    @Test
    @DisplayName("空锚点 Methods 被错嵌在 Discussion 下 → 提升为 root 并收编 Datasets / Pre-processing")
    void orphanAnchorUnderAnchor_isReparentedWithAdoption() {
        Section discussion = section("d", "Discussion", 1, body("pd"));
        Section ownSubsection = section("own", "Implicit biases and scaling laws", 2, body("po"));
        Section methods = section("m", "Methods", 2, null);
        Section datasets = section("ds", "Datasets", 2, body("p1"));
        Section preProcessing = section("pre", "Pre-processing", 2, body("p2"));
        discussion.setChildren(new ArrayList<>(
                List.of(ownSubsection, methods, datasets, preProcessing)));
        AcademicDocument document = document(discussion);

        MajorAnchorReparenting.Result result = reparenting.reparent(document);

        assertThat(result.reparentedCount()).isEqualTo(1);
        // Methods 成为 root，紧随 Discussion 之后
        assertThat(document.getSections()).extracting(Section::getId).containsExactly("d", "m");
        // Discussion 自己的子节保留
        assertThat(discussion.getChildren()).extracting(Section::getId).containsExactly("own");
        // Datasets / Pre-processing 收编到 Methods 之下
        assertThat(methods.getChildren()).extracting(Section::getId).containsExactly("ds", "pre");
        assertThat(result.audits().get(0).adoptedSectionTitles())
                .containsExactly("Datasets", "Pre-processing");
        assertThat(result.warnings()).anyMatch(w -> w.startsWith("MAJOR_ANCHOR_REPARENTED"));
    }

    @Test
    @DisplayName("重挂后重建被收编子树的 level / path")
    void levelAndPathAreRebuiltForAdoptedSubtree() {
        Section discussion = section("d", "Discussion", 1, body("pd"));
        Section methods = section("m", "Methods", 2, null);
        Section datasets = section("ds", "Datasets", 2, body("p1"));
        Section grandChild = section("gc", "PTB-XL", 3, body("p2"));
        datasets.setChildren(new ArrayList<>(List.of(grandChild)));
        discussion.setChildren(new ArrayList<>(List.of(methods, datasets)));
        AcademicDocument document = document(discussion);

        reparenting.reparent(document);

        assertThat(methods.getLevel()).isEqualTo(1);
        assertThat(methods.getPath()).containsExactly("Methods");
        assertThat(datasets.getLevel()).isEqualTo(2);
        assertThat(datasets.getPath()).containsExactly("Methods", "Datasets");
        assertThat(grandChild.getLevel()).isEqualTo(3);
        assertThat(grandChild.getPath()).containsExactly("Methods", "Datasets", "PTB-XL");
    }

    @Test
    @DisplayName("收编遇到下一个主章节锚点标题即停")
    void adoptionStopsAtNextMajorAnchor() {
        Section discussion = section("d", "Discussion", 1, body("pd"));
        Section methods = section("m", "Methods", 2, null);
        Section datasets = section("ds", "Datasets", 2, body("p1"));
        Section conclusions = section("c", "Conclusions", 2, body("p2"));
        Section appendix = section("ap", "Appendix A", 2, body("p3"));
        discussion.setChildren(new ArrayList<>(List.of(methods, datasets, conclusions, appendix)));
        AcademicDocument document = document(discussion);

        reparenting.reparent(document);

        assertThat(methods.getChildren()).extracting(Section::getId).containsExactly("ds");
        // Conclusions 是锚点 → 停止收编；Appendix A 仍留在 Discussion 下
        assertThat(discussion.getChildren()).extracting(Section::getId)
                .containsExactly("c", "ap");
    }

    @Test
    @DisplayName("收编在 rawLevel 不再相同时停止")
    void adoptionStopsWhenRawLevelDiffers() {
        Section discussion = section("d", "Discussion", 1, body("pd"));
        Section methods = section("m", "Methods", 2, null);
        Section datasets = section("ds", "Datasets", 2, body("p1"));
        Section deeper = section("deep", "Patch segmentation strategy", 3, body("p2"));
        discussion.setChildren(new ArrayList<>(List.of(methods, datasets, deeper)));
        AcademicDocument document = document(discussion);

        reparenting.reparent(document);

        assertThat(methods.getChildren()).extracting(Section::getId).containsExactly("ds");
        assertThat(discussion.getChildren()).extracting(Section::getId).containsExactly("deep");
    }

    // ==================== 不得误修 ====================

    @Test
    @DisplayName("锚点子节带正文（非空孤儿）→ 不动")
    void anchorChildWithContent_isNotTouched() {
        Section discussion = section("d", "Discussion", 1, body("pd"));
        Section methods = section("m", "Methods", 2, body("pm"));
        Section datasets = section("ds", "Datasets", 2, body("p1"));
        discussion.setChildren(new ArrayList<>(List.of(methods, datasets)));
        AcademicDocument document = document(discussion);

        MajorAnchorReparenting.Result result = reparenting.reparent(document);

        assertThat(result.reparentedCount()).isZero();
        assertThat(discussion.getChildren()).extracting(Section::getId)
                .containsExactly("m", "ds");
    }

    @Test
    @DisplayName("父节点不是主章节锚点 → 不动")
    void anchorUnderNonAnchorParent_isNotTouched() {
        Section parent = section("p", "Comparison of transformers", 1, body("pp"));
        Section methods = section("m", "Methods", 2, null);
        Section datasets = section("ds", "Datasets", 2, body("p1"));
        parent.setChildren(new ArrayList<>(List.of(methods, datasets)));
        AcademicDocument document = document(parent);

        MajorAnchorReparenting.Result result = reparenting.reparent(document);

        assertThat(result.reparentedCount()).isZero();
        assertThat(parent.getChildren()).extracting(Section::getId).containsExactly("m", "ds");
    }

    @Test
    @DisplayName("没有可收编同级 → 不动（不产出空 root）")
    void noAdoptableSiblings_isNotTouched() {
        Section discussion = section("d", "Discussion", 1, body("pd"));
        Section methods = section("m", "Methods", 2, null);
        Section ownSubsection = section("own", "Implicit biases", 2, body("po"));
        // Methods 是最后一个子节，之后没有同级
        discussion.setChildren(new ArrayList<>(List.of(ownSubsection, methods)));
        AcademicDocument document = document(discussion);

        MajorAnchorReparenting.Result result = reparenting.reparent(document);

        assertThat(result.reparentedCount()).isZero();
        assertThat(document.getSections()).extracting(Section::getId).containsExactly("d");
    }

    @Test
    @DisplayName("缺少 Marker rawLevel 佐证 → 不动")
    void missingRawLevel_isNotTouched() {
        Section discussion = section("d", "Discussion", null, body("pd"));
        Section methods = section("m", "Methods", null, null);
        Section datasets = section("ds", "Datasets", null, body("p1"));
        discussion.setChildren(new ArrayList<>(List.of(methods, datasets)));
        AcademicDocument document = document(discussion);

        MajorAnchorReparenting.Result result = reparenting.reparent(document);

        assertThat(result.reparentedCount()).isZero();
        assertThat(discussion.getChildren()).extracting(Section::getId).containsExactly("m", "ds");
    }

    @Test
    @DisplayName("已是 root 的锚点 → 不动")
    void anchorAlreadyRoot_isNotTouched() {
        Section methods = section("m", "Methods", 1, null);
        Section datasets = section("ds", "Datasets", 1, body("p1"));
        AcademicDocument document = document(methods, datasets);

        MajorAnchorReparenting.Result result = reparenting.reparent(document);

        assertThat(result.reparentedCount()).isZero();
    }

    // ==================== 构造工具 ====================

    private Section section(String id, String title, Integer rawLevel, ParagraphBlock body) {
        List<DocumentBlock> blocks = body == null
                ? new ArrayList<>()
                : new ArrayList<>(List.of(body));
        return Section.builder()
                .id(id)
                .sourceBlockId(id)
                .title(title)
                .level(1)
                .rawLevel(rawLevel)
                .path(new ArrayList<>(List.of(title)))
                .blocks(blocks)
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
