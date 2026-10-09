package com.example.ragdemo.document.cleaner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 主章节锚点判定单测。
 * <p>
 * 重点：
 * <ul>
 *   <li>剥离<b>罗马前缀</b>（{@code II. METHODS} → {@code methods}）—— 否则 Rule A 会把真主章节误重挂</li>
 *   <li>剥离操作<b>不得</b>破坏以 i/v/x/l/c 开头的真实锚点（limitations / introduction / conclusions）</li>
 *   <li>编号前缀剥离后仍要求与锚点集<b>精确相等</b> —— 深编号子节（4.4.6. Ablations on ...）仍可被重挂</li>
 * </ul>
 */
class MajorSectionAnchorsTest {

    @Test
    @DisplayName("罗马前缀的主章节标题被识别为锚点")
    void romanPrefixedTitles_areAnchors() {
        assertThat(MajorSectionAnchors.isMajorAnchor("II. METHODS")).isTrue();
        assertThat(MajorSectionAnchors.isMajorAnchor("III. RESULTS")).isTrue();
        assertThat(MajorSectionAnchors.isMajorAnchor("IV. CONCLUSIONS")).isTrue();
        assertThat(MajorSectionAnchors.isMajorAnchor("I. Introduction")).isTrue();
        assertThat(MajorSectionAnchors.isMajorAnchor("V. Discussion")).isTrue();
        // 无分隔符的罗马形式（罗马 + 空白）
        assertThat(MajorSectionAnchors.isMajorAnchor("III RESULTS")).isTrue();
    }

    @Test
    @DisplayName("阿拉伯编号 / 大小写 / 尾部冒号等变体仍被识别")
    void arabicAndCaseVariants_areAnchors() {
        assertThat(MajorSectionAnchors.isMajorAnchor("methods")).isTrue();
        assertThat(MajorSectionAnchors.isMajorAnchor("METHODS:")).isTrue();
        assertThat(MajorSectionAnchors.isMajorAnchor("3. Methods")).isTrue();
        assertThat(MajorSectionAnchors.isMajorAnchor("2.1. Evaluation")).isTrue();
        assertThat(MajorSectionAnchors.isMajorAnchor("  Discussion  ")).isTrue();
    }

    @Test
    @DisplayName("罗马前缀剥离不破坏以 i/v/x/l/c 开头的真实锚点")
    void romanStripping_doesNotCorruptRealAnchors() {
        assertThat(MajorSectionAnchors.normalize("limitations")).isEqualTo("limitations");
        assertThat(MajorSectionAnchors.normalize("introduction")).isEqualTo("introduction");
        assertThat(MajorSectionAnchors.normalize("conclusions")).isEqualTo("conclusions");
        assertThat(MajorSectionAnchors.normalize("references")).isEqualTo("references");
        assertThat(MajorSectionAnchors.normalize("materials and methods"))
                .isEqualTo("materials and methods");
        assertThat(MajorSectionAnchors.normalize("experiments")).isEqualTo("experiments");
        assertThat(MajorSectionAnchors.normalize("methodology")).isEqualTo("methodology");
    }

    @Test
    @DisplayName("非锚点标题不误判（含已知边界：变体/复合标题尚未处理）")
    void nonAnchorTitles_areNotAnchors() {
        assertThat(MajorSectionAnchors.isMajorAnchor("Comparison of 1-dimensional transformers")).isFalse();
        assertThat(MajorSectionAnchors.isMajorAnchor("A Additional Experiments")).isFalse();
        // 已知边界（B 类：标题变体/复合标题，本轮未处理）
        assertThat(MajorSectionAnchors.isMajorAnchor("Experimental Settings")).isFalse();
        assertThat(MajorSectionAnchors.isMajorAnchor("Related works")).isFalse();
        // 深编号子节：剥离编号后仍需精确相等 → 不是锚点，因此仍可被 Rule A 重挂
        assertThat(MajorSectionAnchors.isMajorAnchor(
                "4.4.6. Ablations on the weight assignment method of WCE")).isFalse();
        assertThat(MajorSectionAnchors.isMajorAnchor(null)).isFalse();
        assertThat(MajorSectionAnchors.isMajorAnchor("   ")).isFalse();
    }
}
