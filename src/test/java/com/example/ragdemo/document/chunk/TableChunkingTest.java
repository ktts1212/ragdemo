package com.example.ragdemo.document.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;

/**
 * TABLE 专项测试（skill §12）。
 * <p>
 * 表格是高价值检索对象，必须保证：
 * <ul>
 *   <li>caption 与表格内容一起进入同一个 chunk，不被切成无上下文的碎片</li>
 *   <li>超长表格按行分批，且<b>每一批都重复 caption + 表头行</b></li>
 *   <li>{@code html} 永不进入 content / embeddingText</li>
 * </ul>
 */
class TableChunkingTest {

    private static final String CAPTION = "Table 2. Comparison of 1-dimensional transformers.";
    private static final String HEADER_ROW = "Model\tAUROC\tF1";

    @Test
    @DisplayName("普通表格：caption + 内容在同一 chunk，html 不外泄")
    void normalTable_keepsCaptionAndContentInOneChunk() {
        Section section = ChunkTestSupport.sectionOf("s1", "Results",
                ChunkTestSupport.table("t1", "Table 1", CAPTION, HEADER_ROW + "\nPatchECG\t0.95\t0.91"));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).hasSize(1);
        AcademicChunk chunk = result.chunks().get(0);
        assertThat(chunk.getChunkType()).isEqualTo(ChunkType.TABLE);
        assertThat(chunk.getContent()).contains(CAPTION).contains("PatchECG\t0.95\t0.91");
        assertThat(chunk.getEmbeddingText()).contains(CAPTION);
        assertThat(chunk.getBlockTypes()).containsExactly(BlockKind.TABLE);
        assertThat(chunk.getContent()).doesNotContain("html-only");
        assertThat(chunk.getEmbeddingText()).doesNotContain("html-only");
        assertThat(result.metrics().tableChunkCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("caption 为空时回退用 label（Table 2）作为表头")
    void table_fallsBackToLabelWhenCaptionBlank() {
        Section section = ChunkTestSupport.sectionOf("s1", "Results",
                ChunkTestSupport.table("t1", "Table 2", null, HEADER_ROW));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).getContent()).startsWith("Table 2");
    }

    @Test
    @DisplayName("caption 与内容均为空 → 跳过并计数，不产出空 chunk")
    void emptyTable_isSkipped() {
        List<DocumentBlock> blocks = List.of(ChunkTestSupport.table("t1", null, null, "   "));
        Section section = ChunkTestSupport.section("s1", "Results", List.of("Results"), 1, blocks);

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).isEmpty();
        assertThat(result.metrics().skippedTableCount()).isEqualTo(1);
        assertThat(result.metrics().emptyChunkCount()).isZero();
    }

    @Test
    @DisplayName("超长表格按行分批，每批都重复 caption + 表头行，且行不丢不重")
    void oversizedTable_splitsByRows_repeatingCaptionAndHeader() {
        List<String> expectedRows = IntStream.range(0, 20).mapToObj(TableChunkingTest::rowText).toList();
        String content = HEADER_ROW + "\n" + String.join("\n", expectedRows);
        Section section = ChunkTestSupport.sectionOf("s1", "Results",
                ChunkTestSupport.table("t1", "Table 2", CAPTION, content));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks().size()).isGreaterThan(1);
        List<String> actualRows = new ArrayList<>();
        for (AcademicChunk chunk : result.chunks()) {
            assertThat(chunk.getChunkType()).isEqualTo(ChunkType.TABLE);
            // 每批都必须带完整上下文
            assertThat(chunk.getContent()).contains(CAPTION);
            assertThat(chunk.getContent()).contains(HEADER_ROW);
            String[] lines = chunk.getContent().split("\n");
            // lines[0] = caption，lines[1] = 表头行，其后为数据行
            assertThat(lines[0]).isEqualTo(CAPTION);
            assertThat(lines[1]).isEqualTo(HEADER_ROW);
            actualRows.addAll(List.of(lines).subList(2, lines.length));
        }
        assertThat(actualRows).containsExactlyElementsOf(expectedRows);
    }

    @Test
    @DisplayName("超长表格的每一批都控制在 target 预算附近（不超 max）")
    void oversizedTable_batchesStayWithinBudget() {
        List<String> rows = IntStream.range(0, 30).mapToObj(TableChunkingTest::rowText).toList();
        Section section = ChunkTestSupport.sectionOf("s1", "Results",
                ChunkTestSupport.table("t1", "Table 2", CAPTION, HEADER_ROW + "\n" + String.join("\n", rows)));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks().size()).isGreaterThan(1);
        result.chunks().forEach(chunk -> {
            assertThat(chunk.getTokenCount()).isLessThanOrEqualTo(1000);
            assertThat(chunk.isOversized()).isFalse();
        });
    }

    @Test
    @DisplayName("表格 caption 不会丢失（并入 FIGURE/TABLE 前后不得被吞）")
    void tableCaption_isNeverLost() {
        // 表格前有短说明段落 → 可能触发 MIXED，caption 仍必须在最终 chunk 中
        List<DocumentBlock> blocks = List.of(
                ChunkTestSupport.para("p1", "Results are summarized below:"),
                ChunkTestSupport.table("t1", "Table 3", CAPTION, HEADER_ROW + "\nPatchECG\t0.95\t0.91"));
        Section section = ChunkTestSupport.section("s1", "Results", List.of("Results"), 1, blocks);

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        String allContent = result.chunks().stream()
                .map(AcademicChunk::getContent)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(allContent).contains(CAPTION).contains("PatchECG\t0.95\t0.91");
        assertThat(result.chunks().stream()
                .filter(chunk -> chunk.getBlockTypes().contains(BlockKind.TABLE))
                .count()).isEqualTo(1);
    }

    /** 数据行：可区分前缀 + 填充，总长约 300 字符。 */
    private static String rowText(int index) {
        return String.format("r%02d", index) + "x".repeat(296);
    }
}
