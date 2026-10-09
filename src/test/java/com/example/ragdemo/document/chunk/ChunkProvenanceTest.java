package com.example.ragdemo.document.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;

/**
 * provenance 专项测试（skill §24）。
 * <p>
 * 重点：领域模型里 provenance 字段不统一（Paragraph/Figure/Table 有复数 sourceBlockIds，
 * List/Formula/Footnote/Caption/Noise 只有单数 sourceBlockId），
 * {@link BlockProvenance} 必须把它们归一为"有序去重的 id 列表"，
 * 且 chunk 的 sourceBlockIds = union、startPage = min、endPage = max。
 * <p>
 * 另一个关键不变量：跨页 repaired paragraph 必须保持为<b>一个</b>逻辑单元，禁止再按页拆开。
 */
class ChunkProvenanceTest {

    // ==================== sourceBlockIds 归一化 ====================

    @Test
    @DisplayName("Paragraph 复数 sourceBlockIds 优先")
    void sourceBlockIds_prefersPluralList() {
        var paragraph = ChunkTestSupport.paraMerged("/page/3/Text/11", "text", 4, 5,
                List.of("/page/3/Text/11", "/page/4/Text/1"));

        assertThat(BlockProvenance.sourceBlockIds(paragraph))
                .containsExactly("/page/3/Text/11", "/page/4/Text/1");
    }

    @Test
    @DisplayName("List / Formula / Footnote / Caption / Noise 回退到单数 sourceBlockId")
    void sourceBlockIds_fallsBackToSingularId() {
        assertThat(BlockProvenance.sourceBlockIds(ChunkTestSupport.list("l1", "item")))
                .containsExactly("l1");
        assertThat(BlockProvenance.sourceBlockIds(ChunkTestSupport.formula("f1", "(1)", "y=x")))
                .containsExactly("f1");
        assertThat(BlockProvenance.sourceBlockIds(ChunkTestSupport.footnote("fn1", "note")))
                .containsExactly("fn1");
        assertThat(BlockProvenance.sourceBlockIds(ChunkTestSupport.caption("c1", "cap")))
                .containsExactly("c1");
        assertThat(BlockProvenance.sourceBlockIds(ChunkTestSupport.noise("n1", "footer")))
                .containsExactly("n1");
    }

    @Test
    @DisplayName("无任何 source id 时回退到 block.id；null / 空白被丢弃")
    void sourceBlockIds_fallsBackToBlockId() {
        var paragraph = com.example.ragdemo.document.domain.block.ParagraphBlock.builder()
                .id("only-id")
                .text("text")
                .build();

        assertThat(BlockProvenance.sourceBlockIds(paragraph)).containsExactly("only-id");
        assertThat(BlockProvenance.sourceBlockIds(null)).isEmpty();
    }

    @Test
    @DisplayName("union 有序去重")
    void union_isOrderedAndDeduplicated() {
        List<DocumentBlock> blocks = List.of(
                ChunkTestSupport.para("p1", "a"),
                ChunkTestSupport.para("p2", "b"),
                ChunkTestSupport.para("p1", "c")); // 与第一块同 id

        assertThat(BlockProvenance.union(blocks)).containsExactly("p1", "p2");
        assertThat(BlockProvenance.union(List.of())).isEmpty();
    }

    // ==================== 页码聚合 ====================

    @Test
    @DisplayName("合并 chunk 的 startPage = min、endPage = max")
    void pageRange_isMinAndMaxAcrossBlocks() {
        List<DocumentBlock> blocks = List.of(
                ChunkTestSupport.para("p1", ChunkTestSupport.chars(800), 4, 4),
                ChunkTestSupport.para("p2", ChunkTestSupport.chars(800), 5, 6));
        Section section = ChunkTestSupport.section("s1", "Methods", List.of("Methods"), 1, blocks);

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).getStartPage()).isEqualTo(4);
        assertThat(result.chunks().get(0).getEndPage()).isEqualTo(6);
    }

    @Test
    @DisplayName("无页码信息时不伪造页码（null）")
    void pageRange_isNullWhenUnknown() {
        Section section = ChunkTestSupport.sectionOf("s1", "Methods",
                ChunkTestSupport.para("p1", ChunkTestSupport.chars(400)));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks().get(0).getStartPage()).isNull();
        assertThat(result.chunks().get(0).getEndPage()).isNull();
    }

    // ==================== 跨页 repaired paragraph ====================

    @Test
    @DisplayName("跨页 repaired paragraph 保持为一个 chunk，不按页拆开")
    void crossPageRepairedParagraph_isNotSplitByPage() {
        // 模拟 ReadingOrderRepair 产物：逻辑段落 id = 首页 block id，来源跨两页
        var merged = ChunkTestSupport.paraMerged("/page/3/Text/11", ChunkTestSupport.chars(1250), 4, 5,
                List.of("/page/3/Text/11", "/page/4/Text/1"));
        Section section = ChunkTestSupport.section("s1", "Methods", List.of("Methods"), 1,
                List.of(merged));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).hasSize(1);
        AcademicChunk chunk = result.chunks().get(0);
        assertThat(chunk.getSourceBlockIds())
                .containsExactly("/page/3/Text/11", "/page/4/Text/1");
        assertThat(chunk.getStartPage()).isEqualTo(4);
        assertThat(chunk.getEndPage()).isEqualTo(5);
        assertThat(result.metrics().splitParagraphCount()).isZero();
    }

    @Test
    @DisplayName("多个跨页段落累积时 provenance 为并集且顺序稳定")
    void provenanceUnion_isStableAcrossParagraphs() {
        var first = ChunkTestSupport.paraMerged("/page/3/Text/11", ChunkTestSupport.chars(400), 4, 5,
                List.of("/page/3/Text/11", "/page/4/Text/1"));
        var second = ChunkTestSupport.paraMerged("/page/4/Text/9", ChunkTestSupport.chars(400), 5, 6,
                List.of("/page/4/Text/9", "/page/5/Text/3"));
        Section section = ChunkTestSupport.section("s1", "Methods", List.of("Methods"), 1,
                List.of(first, second));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).getSourceBlockIds()).containsExactly(
                "/page/3/Text/11", "/page/4/Text/1", "/page/4/Text/9", "/page/5/Text/3");
        assertThat(result.chunks().get(0).getStartPage()).isEqualTo(4);
        assertThat(result.chunks().get(0).getEndPage()).isEqualTo(6);
    }

    // ==================== 重复 provenance ====================

    @Test
    @DisplayName("overlap 关闭时无重复 provenance")
    void duplicatedSourceBlock_isZeroWithoutOverlap() {
        Section section = ChunkTestSupport.section("s1", "Methods", List.of("Methods"), 1,
                List.of(ChunkTestSupport.para("p1", ChunkTestSupport.chars(4000)),
                        ChunkTestSupport.para("p2", ChunkTestSupport.chars(4000))));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).hasSize(2);
        assertThat(result.metrics().duplicatedSourceBlockCount()).isZero();
        assertThat(result.metrics().overlapChunkCount()).isZero();
    }

    @Test
    @DisplayName("overlap 仅作用于 TEXT chunk；结构块不参与 overlap")
    void overlap_neverAppliesToStructuredChunks() {
        RagProperties properties = ChunkTestSupport.properties(300, 1000, 50, true);
        List<DocumentBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            blocks.add(ChunkTestSupport.para("p" + i, String.format("%03d", i) + "a".repeat(197)));
        }
        blocks.add(ChunkTestSupport.list("l1", "item one"));
        blocks.add(ChunkTestSupport.figure("f1", "Figure 1", "Fig. 1. Caption.", null));
        Section section = ChunkTestSupport.section("s1", "Results", List.of("Results"), 1, blocks);

        ChunkingResult result = ChunkTestSupport.chunker(properties)
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        result.chunks().stream()
                .filter(chunk -> chunk.getChunkType() != ChunkType.TEXT)
                .forEach(chunk -> assertThat(chunk.getOverlapBlockIds()).isEmpty());
        // 只对 TEXT 生效
        assertThat(result.metrics().overlapChunkCount())
                .isEqualTo((int) result.chunksOf(ChunkType.TEXT).stream()
                        .filter(chunk -> !chunk.getOverlapBlockIds().isEmpty()).count());
    }

    // ==================== 完整性 ====================

    @Test
    @DisplayName("所有 chunk 都保留 sectionId / sectionPath / sourceBlockIds，可追溯")
    void everySectionChunk_isTraceable() {
        Section child = ChunkTestSupport.section("s-child", "Metrics",
                List.of("Results", "Metrics"), 2,
                List.of(ChunkTestSupport.para("p2", ChunkTestSupport.chars(400))));
        Section root = ChunkTestSupport.section("s-root", "Results", List.of("Results"), 1,
                List.of(ChunkTestSupport.para("p1", ChunkTestSupport.chars(400))));
        root.setChildren(new ArrayList<>(List.of(child)));
        AcademicDocument document = ChunkTestSupport.document("doc", "Abstract.",
                List.of(root), List.of(ChunkTestSupport.reference("r1", "[1] Ref.")));

        ChunkingResult result = ChunkTestSupport.chunker().chunk(document);

        result.chunksOf(ChunkType.TEXT).forEach(chunk -> {
            assertThat(chunk.getSectionId()).isNotBlank();
            assertThat(chunk.getSectionPath()).isNotEmpty();
            assertThat(chunk.getSourceBlockIds()).isNotEmpty();
            assertThat(chunk.getChunkId()).startsWith("doc:");
        });
        assertThat(result.chunksOf(ChunkType.TEXT).stream()
                .map(AcademicChunk::getSectionPath)
                .map(path -> String.join(" > ", path))
                .toList()).containsExactly("Results", "Results > Metrics");
    }
}
