package com.example.ragdemo.document.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;

/**
 * SectionAwareChunker 主测试（skill §28）。
 * <p>
 * 覆盖：Section 边界、段落累积、超长段落降级、非文本 block 策略、
 * chunkId/order 稳定性、provenance、QA 不变量、overlap 开关。
 * <p>
 * token 由 字符 / 4 精确控制（见 {@link ChunkTestSupport}）。
 */
class SectionAwareChunkerTest {

    // ==================== Section 边界 ====================

    @Test
    @DisplayName("两个短 Section 不跨边界合并，各自成 chunk")
    void sectionBoundary_isNotCrossed() {
        Section a = ChunkTestSupport.sectionOf("s-a", "Section A", ChunkTestSupport.para("p-a", ChunkTestSupport.chars(200)));
        Section b = ChunkTestSupport.sectionOf("s-b", "Section B", ChunkTestSupport.para("p-b", ChunkTestSupport.chars(200)));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(a, b), List.of()));

        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks().stream().map(AcademicChunk::getSectionId).toList())
                .containsExactly("s-a", "s-b");
        // 每个 chunk 只含自己 section 的 block
        assertThat(result.chunks().get(0).getSourceBlockIds()).containsExactly("p-a");
        assertThat(result.chunks().get(1).getSourceBlockIds()).containsExactly("p-b");
        assertThat(result.metrics().crossSectionChunkCount()).isZero();
    }

    @Test
    @DisplayName("纯容器 Section（无直属 block）不产出 chunk，但其子 Section 正常产出")
    void emptyContainerSection_emitsNoChunk() {
        Section container = ChunkTestSupport.section("c1", "Results", List.of("Results"), 2,
                List.of());
        Section child = ChunkTestSupport.sectionOf("c2", "Metrics", ChunkTestSupport.para("p1", ChunkTestSupport.chars(400)));
        container.setChildren(new ArrayList<>(List.of(child)));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(container), List.of()));

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).getSectionId()).isEqualTo("c2");
        assertThat(result.metrics().emptyChunkCount()).isZero();
    }

    @Test
    @DisplayName("sectionPath 中的空白段被过滤，不会出现 \"Discussion >  > X\"")
    void blankPathSegments_areFiltered() {
        Section section = ChunkTestSupport.section("s1", "Limitations",
                List.of("Discussion", "", "   ", "Limitations"), 3,
                List.of(ChunkTestSupport.para("p1", ChunkTestSupport.chars(400))));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        AcademicChunk chunk = result.chunks().get(0);
        assertThat(chunk.getSectionPath()).containsExactly("Discussion", "Limitations");
        assertThat(chunk.getEmbeddingText()).contains("Section: Discussion > Limitations");
        assertThat(chunk.getEmbeddingText()).doesNotContain(">  >");
    }

    @Test
    @DisplayName("极短 Section 保留为独立 chunk（v1 不跨 Section 合并）并标记 undersized")
    void undersizedSection_isKeptAsIsolatedChunk() {
        Section tiny = ChunkTestSupport.sectionOf("s-tiny", "Competing interests",
                ChunkTestSupport.para("p1", ChunkTestSupport.chars(41)));
        Section big = ChunkTestSupport.sectionOf("s-big", "Methods",
                ChunkTestSupport.para("p2", ChunkTestSupport.chars(4000)));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(tiny, big), List.of()));

        assertThat(result.chunks()).hasSize(2);
        AcademicChunk tinyChunk = result.chunks().get(0);
        assertThat(tinyChunk.getSectionId()).isEqualTo("s-tiny");
        assertThat(tinyChunk.getContent()).isEqualTo(ChunkTestSupport.chars(41));
        assertThat(tinyChunk.isUndersized()).isTrue();
        assertThat(result.metrics().undersizedChunkCount()).isEqualTo(1);
        assertThat(result.chunks().get(1).isUndersized()).isFalse();
    }

    // ==================== PARAGRAPH ====================

    @Test
    @DisplayName("同 Section 内连续 Paragraph 按 targetTokens 累积")
    void paragraphs_accumulateUpToTarget() {
        List<DocumentBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            blocks.add(ChunkTestSupport.para("p" + i, ChunkTestSupport.chars(800))); // 200 token each
        }
        Section section = ChunkTestSupport.section("s1", "Results", List.of("Results"), 1, blocks);

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        // 200*3=600 <= 700，再加第 4 段将达 800 > 700 → 前 3 段一块，后 2 段一块
        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks().get(0).getSourceBlockIds()).containsExactly("p0", "p1", "p2");
        assertThat(result.chunks().get(1).getSourceBlockIds()).containsExactly("p3", "p4");
        assertThat(result.chunks().get(0).getBlockTypes()).containsExactly(BlockKind.PARAGRAPH);
    }

    @Test
    @DisplayName("单个 Paragraph 不超过 maxTokens 必须完整保留，不拆分")
    void singleParagraph_withinMax_isKeptIntact() {
        String text = ChunkTestSupport.chars(3500); // 875 token
        Section section = ChunkTestSupport.sectionOf("s1", "Methods", ChunkTestSupport.para("p1", text));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).getContent()).isEqualTo(text);
        assertThat(result.chunks().get(0).isOversized()).isFalse();
        assertThat(result.metrics().splitParagraphCount()).isZero();
    }

    @Test
    @DisplayName("超长 Paragraph 按句子边界降级切分，且内容不丢")
    void oversizedParagraph_isSplitBySentenceBoundary() {
        String text = ChunkTestSupport.sentences(6); // 6 x 1000 chars ≈ 1500 token > max 1000
        Section section = ChunkTestSupport.sectionOf("s1", "Methods", ChunkTestSupport.para("p1", text));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks().size()).isGreaterThan(1);
        assertThat(result.metrics().splitParagraphCount()).isEqualTo(1);
        result.chunks().forEach(chunk -> assertThat(chunk.getTokenCount()).isLessThanOrEqualTo(1000));

        String rejoined = result.chunks().stream()
                .map(AcademicChunk::getContent)
                .collect(Collectors.joining(" "));
        assertThat(rejoined.replaceAll("\\s+", " ")).isEqualTo(text.replaceAll("\\s+", " "));
    }

    // ==================== LIST / MIXED ====================

    @Test
    @DisplayName("短 LIST 整体成一个 LIST chunk，item 带 \"- \" 前缀")
    void shortList_becomesSingleChunk() {
        Section section = ChunkTestSupport.sectionOf("s1", "Contributions",
                ChunkTestSupport.list("l1", "first contribution", "second contribution"));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).hasSize(1);
        AcademicChunk chunk = result.chunks().get(0);
        assertThat(chunk.getChunkType()).isEqualTo(ChunkType.LIST);
        assertThat(chunk.getContent()).contains("- first contribution").contains("- second contribution");
        assertThat(chunk.getBlockTypes()).containsExactly(BlockKind.LIST);
    }

    @Test
    @DisplayName("短前置说明段落 + LIST → MIXED（至多 2 种 blockType）")
    void shortLeadInWithList_becomesMixed() {
        List<DocumentBlock> blocks = List.of(
                ChunkTestSupport.para("p1", "The main contributions are:"),
                ChunkTestSupport.list("l1", "contribution one", "contribution two"));
        Section section = ChunkTestSupport.section("s1", "Contributions", List.of("Contributions"), 1, blocks);

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).hasSize(1);
        AcademicChunk chunk = result.chunks().get(0);
        assertThat(chunk.getChunkType()).isEqualTo(ChunkType.MIXED);
        assertThat(chunk.getBlockTypes()).containsExactly(BlockKind.PARAGRAPH, BlockKind.LIST);
        assertThat(chunk.getContent()).contains("The main contributions are:");
        assertThat(result.metrics().mixedChunkCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("长前置段落 + LIST 不合并（拆成 TEXT + LIST）")
    void longLeadInWithList_isNotMixed() {
        List<DocumentBlock> blocks = List.of(
                ChunkTestSupport.para("p1", ChunkTestSupport.chars(800)), // 200 token > 40
                ChunkTestSupport.list("l1", "contribution one"));
        Section section = ChunkTestSupport.section("s1", "Contributions", List.of("Contributions"), 1, blocks);

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks().stream().map(AcademicChunk::getChunkType).toList())
                .containsExactly(ChunkType.TEXT, ChunkType.LIST);
        assertThat(result.metrics().mixedChunkCount()).isZero();
    }

    // ==================== FIGURE / CAPTION / FORMULA / FOOTNOTE ====================

    @Test
    @DisplayName("无 caption 且无 descriptionText 的 Figure 不产出 chunk，只计数")
    void figureWithoutAnyText_isSkipped() {
        List<DocumentBlock> blocks = List.of(ChunkTestSupport.figure("f1", null, null, null));
        Section section = ChunkTestSupport.section("s1", "Results", List.of("Results"), 1, blocks);

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).isEmpty();
        assertThat(result.metrics().skippedFigureCount()).isEqualTo(1);
        assertThat(result.metrics().emptyChunkCount()).isZero();
    }

    @Test
    @DisplayName("只有 label 没有 caption/descriptionText 的 Figure 同样跳过（label 无检索价值）")
    void figureWithLabelOnly_isSkipped() {
        List<DocumentBlock> blocks = List.of(ChunkTestSupport.figure("f1", "Figure 3", null, null));
        Section section = ChunkTestSupport.section("s1", "Results", List.of("Results"), 1, blocks);

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).isEmpty();
        assertThat(result.metrics().skippedFigureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Figure caption 必须进入 FIGURE chunk，且不含 imageKey")
    void figureWithCaption_keepsCaption() {
        List<DocumentBlock> blocks = List.of(ChunkTestSupport.figure("f1", "Figure 1",
                "Fig. 1. Overview of the PatchECG pipeline.", null));
        Section section = ChunkTestSupport.section("s1", "Results", List.of("Results"), 1, blocks);

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).hasSize(1);
        AcademicChunk chunk = result.chunks().get(0);
        assertThat(chunk.getChunkType()).isEqualTo(ChunkType.FIGURE);
        assertThat(chunk.getContent()).contains("Figure 1").contains("Fig. 1. Overview of the PatchECG pipeline.");
        assertThat(chunk.getEmbeddingText()).contains("Fig. 1. Overview of the PatchECG pipeline.");
        assertThat(chunk.getContent()).doesNotContain("figure-images");
        assertThat(result.metrics().skippedFigureCount()).isZero();
    }

    @Test
    @DisplayName("orphan CAPTION 不丢弃，产出 CAPTION chunk")
    void orphanCaption_isKept() {
        Section section = ChunkTestSupport.sectionOf("s1", "Results",
                ChunkTestSupport.caption("c1", "Fig. 3. AUROC score plotted against average label confidence."));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).getChunkType()).isEqualTo(ChunkType.CAPTION);
        assertThat(result.chunks().get(0).getContent()).contains("Fig. 3. AUROC");
        assertThat(result.metrics().captionChunkCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("FORMULA 独立成 chunk；空公式被跳过")
    void formula_becomesOwnChunk() {
        List<DocumentBlock> blocks = List.of(
                ChunkTestSupport.formula("f1", "(1)", "y = Wx + b"),
                ChunkTestSupport.formula("f2", null, "   "));
        Section section = ChunkTestSupport.section("s1", "Methods", List.of("Methods"), 1, blocks);

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).getChunkType()).isEqualTo(ChunkType.FORMULA);
        assertThat(result.chunks().get(0).getContent()).contains("y = Wx + b");
        assertThat(result.metrics().formulaChunkCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("短 FOOTNOTE 并入同 Section 的 TEXT chunk，不产生极短独立 chunk")
    void shortFootnote_isMergedIntoTextChunk() {
        List<DocumentBlock> blocks = List.of(
                ChunkTestSupport.para("p1", ChunkTestSupport.chars(800)),
                ChunkTestSupport.footnote("fn1", "See supplementary material."));
        Section section = ChunkTestSupport.section("s1", "Methods", List.of("Methods"), 1, blocks);

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).hasSize(1);
        AcademicChunk chunk = result.chunks().get(0);
        assertThat(chunk.getBlockTypes()).containsExactly(BlockKind.PARAGRAPH, BlockKind.FOOTNOTE);
        assertThat(chunk.getContent()).contains("See supplementary material.");
        assertThat(result.metrics().footnoteChunkCount()).isZero();
    }

    // ==================== NOISE ====================

    @Test
    @DisplayName("NOISE block 不进入 chunk，只计数并告警")
    void noiseBlock_producesNoChunk() {
        List<DocumentBlock> blocks = List.of(
                ChunkTestSupport.noise("n1", "Page 1 footer"),
                ChunkTestSupport.para("p1", ChunkTestSupport.chars(400)));
        Section section = ChunkTestSupport.section("s1", "Methods", List.of("Methods"), 1, blocks);

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).getBlockTypes()).containsExactly(BlockKind.PARAGRAPH);
        assertThat(result.metrics().noiseChunkCount()).isZero();
        assertThat(result.warnings()).anyMatch(w -> w.startsWith("NOISE_BLOCK_REACHED_CHUNKER"));
    }

    // ==================== ABSTRACT / REFERENCE ====================

    @Test
    @DisplayName("ABSTRACT 单独成 chunk 且排在最前；abstractText 为空时不产出")
    void abstract_becomesFirstChunk() {
        Section section = ChunkTestSupport.sectionOf("s1", "Intro",
                ChunkTestSupport.para("p1", ChunkTestSupport.chars(400)));

        ChunkingResult withAbstract = ChunkTestSupport.chunker().chunk(
                ChunkTestSupport.document("doc", "Cardiovascular diseases are the leading cause of death.",
                        List.of(section), List.of()));
        assertThat(withAbstract.chunks().get(0).getChunkType()).isEqualTo(ChunkType.ABSTRACT);
        assertThat(withAbstract.chunks().get(0).getOrder()).isZero();
        assertThat(withAbstract.chunks().get(0).getSectionId()).isNull();
        assertThat(withAbstract.chunks().get(0).getSectionPath()).isEmpty();
        assertThat(withAbstract.chunks().get(0).getChunkId()).isEqualTo("doc:__abstract__:0");
        assertThat(withAbstract.chunks().get(0).getEmbeddingText()).contains("Document: " + ChunkTestSupport.TEST_TITLE);

        ChunkingResult withoutAbstract = ChunkTestSupport.chunker().chunk(
                ChunkTestSupport.document("doc", "  ", List.of(section), List.of()));
        assertThat(withoutAbstract.chunks()).hasSize(1);
        assertThat(withoutAbstract.metrics().abstractChunkCount()).isZero();
    }

    @Test
    @DisplayName("1 Reference → 1 REFERENCE chunk，排最后；rawText 为空则跳过")
    void references_oneChunkPerReference() {
        Section section = ChunkTestSupport.sectionOf("s1", "Intro",
                ChunkTestSupport.para("p1", ChunkTestSupport.chars(400)));
        AcademicDocument document = ChunkTestSupport.document("doc", "Abstract.",
                List.of(section),
                List.of(ChunkTestSupport.reference("r1", "[1] Kaptoge S, et al. World health organization."),
                        ChunkTestSupport.reference("r2", "   "),
                        ChunkTestSupport.reference("r3", "[3] Wagner P, et al. PTB-XL.")));

        ChunkingResult result = ChunkTestSupport.chunker().chunk(document);

        assertThat(result.metrics().abstractChunkCount()).isEqualTo(1);
        assertThat(result.metrics().referenceChunkCount()).isEqualTo(2);
        assertThat(result.metrics().skippedReferenceCount()).isEqualTo(1);
        List<AcademicChunk> references = result.chunksOf(ChunkType.REFERENCE);
        assertThat(references).hasSize(2);
        assertThat(references.get(0).getReferenceIds()).containsExactly("r1");
        assertThat(references.get(0).getSourceBlockIds()).isEmpty();
        assertThat(references.get(0).getSectionId()).isNull();
        // 参考文献排在正文之后
        assertThat(result.chunks().get(result.chunkCount() - 1).getChunkType())
                .isEqualTo(ChunkType.REFERENCE);
        assertThat(result.chunks().get(result.chunkCount() - 1).getOrder())
                .isEqualTo(result.chunkCount() - 1);
    }

    @Test
    @DisplayName("REFERENCE chunk 不混入正文 chunk（正文 chunk 不含 reference id）")
    void references_areNotMixedIntoBodyChunks() {
        Section section = ChunkTestSupport.sectionOf("s1", "Intro",
                ChunkTestSupport.para("p1", "Body text without any bibliography entry."));
        AcademicDocument document = ChunkTestSupport.document("doc", "Abstract.",
                List.of(section), List.of(ChunkTestSupport.reference("r1", "[1] Somebody A. A paper.")));

        ChunkingResult result = ChunkTestSupport.chunker().chunk(document);

        result.chunksOf(ChunkType.TEXT).forEach(chunk ->
                assertThat(chunk.getContent()).doesNotContain("[1] Somebody A. A paper."));
        result.chunksOf(ChunkType.TEXT).forEach(chunk ->
                assertThat(chunk.getReferenceIds()).isEmpty());
    }

    // ==================== chunkId / order 稳定性 ====================

    @Test
    @DisplayName("chunkId 与 order 稳定可复现（同输入两次结果一致）")
    void chunkId_isStableAndDeterministic() {
        Section section = ChunkTestSupport.sectionOf("s1", "Methods",
                ChunkTestSupport.para("p1", ChunkTestSupport.chars(4000)),
                ChunkTestSupport.para("p2", ChunkTestSupport.chars(4000)));
        AcademicDocument document = ChunkTestSupport.document("doc", "Abstract.",
                List.of(section), List.of(ChunkTestSupport.reference("r1", "[1] Ref.")));

        List<String> first = ChunkTestSupport.chunker().chunk(document).chunks().stream()
                .map(AcademicChunk::getChunkId).toList();
        List<String> second = ChunkTestSupport.chunker().chunk(document).chunks().stream()
                .map(AcademicChunk::getChunkId).toList();

        assertThat(second).isEqualTo(first);
        // 2 段 x 1000 token，均超过 target 700 → 各自成块
        assertThat(first).containsExactly("doc:__abstract__:0", "doc:s1:0", "doc:s1:1",
                "doc:__references__:0");
    }

    @Test
    @DisplayName("重复 sectionId 时 chunkId 不冲突（确定性加后缀并告警）")
    void duplicateSectionIds_doNotCollide() {
        Section a = ChunkTestSupport.sectionOf("dup", "Section A",
                ChunkTestSupport.para("p1", ChunkTestSupport.chars(400)));
        Section b = ChunkTestSupport.sectionOf("dup", "Section B",
                ChunkTestSupport.para("p2", ChunkTestSupport.chars(400)));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(a, b), List.of()));

        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks().stream().map(AcademicChunk::getChunkId).toList())
                .doesNotHaveDuplicates()
                .containsExactly("doc:dup:0", "doc:dup#2:0");
        assertThat(result.warnings()).anyMatch(w -> w.contains("duplicate sectionId"));
    }

    @Test
    @DisplayName("order 从 0 连续递增，ABSTRACT → sections → references")
    void order_isSequential() {
        Section section = ChunkTestSupport.sectionOf("s1", "Intro",
                ChunkTestSupport.para("p1", ChunkTestSupport.chars(400)));
        AcademicDocument document = ChunkTestSupport.document("doc", "Abstract.",
                List.of(section), List.of(ChunkTestSupport.reference("r1", "[1] Ref.")));

        ChunkingResult result = ChunkTestSupport.chunker().chunk(document);

        assertThat(result.chunks().stream().map(AcademicChunk::getOrder).toList())
                .containsExactly(0, 1, 2);
        assertThat(result.chunks().stream().map(AcademicChunk::getChunkType).toList())
                .containsExactly(ChunkType.ABSTRACT, ChunkType.TEXT, ChunkType.REFERENCE);
    }

    @Test
    @DisplayName("embeddingText 含 Document + Section 上下文；contentHash 为 16 位且稳定")
    void embeddingText_hasContextPrefix() {
        Section section = ChunkTestSupport.sectionOf("s1", "Methods",
                ChunkTestSupport.para("p1", ChunkTestSupport.chars(400)));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", null, List.of(section), List.of()));

        AcademicChunk chunk = result.chunks().get(0);
        assertThat(chunk.getEmbeddingText())
                .startsWith("Document: " + ChunkTestSupport.TEST_TITLE)
                .contains("Section: Methods")
                .contains("Content:");
        assertThat(chunk.getContentHash()).hasSize(16);
        assertThat(chunk.getCharCount()).isEqualTo(chunk.getContent().length());
    }

    // ==================== QA 不变量 ====================

    @Test
    @DisplayName("QA 不变量：emptyChunkCount / noiseChunkCount / crossSectionChunkCount 均为 0")
    void qaInvariants_hold() {
        Section a = ChunkTestSupport.sectionOf("s-a", "Section A",
                ChunkTestSupport.noise("n1", "footer"),
                ChunkTestSupport.para("p-a", ChunkTestSupport.chars(800)));
        Section b = ChunkTestSupport.sectionOf("s-b", "Section B",
                ChunkTestSupport.para("p-b", ChunkTestSupport.chars(400)));

        ChunkingResult result = ChunkTestSupport.chunker()
                .chunk(ChunkTestSupport.document("doc", "Abstract.", List.of(a, b),
                        List.of(ChunkTestSupport.reference("r1", "[1] Ref."))));

        ChunkQualityMetrics metrics = result.metrics();
        assertThat(metrics.emptyChunkCount()).isZero();
        assertThat(metrics.noiseChunkCount()).isZero();
        assertThat(metrics.crossSectionChunkCount()).isZero();
        assertThat(metrics.chunkCount()).isEqualTo(result.chunkCount());
        assertThat(metrics.sectionCoverageRatio()).isEqualTo(1.0);
    }

    // ==================== Overlap ====================

    @Test
    @DisplayName("overlap 默认关闭（v1）")
    void overlap_isDisabledByDefault() {
        assertThat(ChunkTestSupport.defaultProperties().getChunker().isOverlapEnabled()).isFalse();

        ChunkingResult result = ChunkTestSupport.chunker().chunk(overlapDocument());

        assertThat(result.metrics().overlapChunkCount()).isZero();
        assertThat(result.chunks()).allSatisfy(chunk -> assertThat(chunk.getOverlapBlockIds()).isEmpty());
    }

    @Test
    @DisplayName("overlap 开启时：仅 TEXT chunk 重复上一块的最后一段，并记录 overlapBlockIds")
    void overlap_whenEnabled_duplicatesLastParagraph() {
        RagProperties properties = ChunkTestSupport.properties(700, 1000, 180, true);
        ChunkingResult result = ChunkTestSupport.chunker(properties).chunk(overlapDocument());

        assertThat(result.chunks()).hasSize(2);
        AcademicChunk first = result.chunks().get(0);
        AcademicChunk second = result.chunks().get(1);

        // 上一块的最后一段 = 第 14 段（索引 13）
        String lastParagraph = first.getContent()
                .substring(first.getContent().lastIndexOf("\n\n") + 2);
        assertThat(second.getContent()).startsWith(lastParagraph);
        assertThat(second.getOverlapBlockIds()).containsExactly("p13");
        assertThat(result.metrics().overlapChunkCount()).isEqualTo(1);
        assertThat(result.metrics().duplicatedSourceBlockCount()).isEqualTo(1);
        assertThat(second.getTokenCount()).isLessThanOrEqualTo(1000);
    }

    /** 20 段 x 200 字符（50 token）→ 2 个 TEXT chunk（14 + 6 段）。 */
    private AcademicDocument overlapDocument() {
        List<DocumentBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            blocks.add(ChunkTestSupport.para("p" + i, paragraphText(i)));
        }
        Section section = ChunkTestSupport.section("s1", "Results", List.of("Results"), 1, blocks);
        return ChunkTestSupport.document("doc", null, List.of(section), List.of());
    }

    /** 200 字符且带可区分前缀（用于精确定位"最后一段"）。 */
    private String paragraphText(int index) {
        return String.format("%03d", index) + "a".repeat(197);
    }
}
