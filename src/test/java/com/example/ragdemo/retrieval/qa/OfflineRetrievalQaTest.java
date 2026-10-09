package com.example.ragdemo.retrieval.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ragdemo.document.chunk.AcademicChunk;
import com.example.ragdemo.document.chunk.BlockKind;
import com.example.ragdemo.document.chunk.ChunkType;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PageRange;
import com.example.ragdemo.document.domain.PaperMetadata;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;
import com.example.ragdemo.embedding.EmbeddingGateway;
import com.example.ragdemo.retrieval.qa.OfflineRetrievalQa.Experiment;
import com.example.ragdemo.retrieval.qa.OfflineRetrievalQa.TextMode;
import com.example.ragdemo.retrieval.qa.RetrievalGoldCase.QuestionType;

/**
 * 离线 Retrieval QA 冒烟测试（skill §17 / §21 / §22）。
 * <p>
 * <b>完全离线</b>：用确定性的词袋哈希 embedding 替代真实模型，
 * 因此可在无 Ollama / 无 DB 的环境下验证整条 QA 机制（语料构建 → 精确余弦 → 指标）。
 * <p>
 * 真实 embedding 下的对照实验（A-legacy vs B1/B2）待 Ollama 可达后用同一 API 运行。
 */
class OfflineRetrievalQaTest {

    /** 确定性词袋哈希 embedding：无语义，但可验证端到端机制与排序稳定性。 */
    private static final class LexicalGateway implements EmbeddingGateway {

        private static final int DIM = 256;

        @Override
        public float[] embedQuery(String text) {
            return vector(text);
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            return texts.stream().map(LexicalGateway::vector).toList();
        }

        private static float[] vector(String text) {
            float[] values = new float[DIM];
            if (text == null || text.isBlank()) {
                return values;
            }
            for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (!token.isBlank()) {
                    values[Math.floorMod(token.hashCode(), DIM)] += 1f;
                }
            }
            double norm = 0;
            for (float value : values) {
                norm += (double) value * value;
            }
            if (norm > 0) {
                float length = (float) Math.sqrt(norm);
                for (int i = 0; i < DIM; i++) {
                    values[i] /= length;
                }
            }
            return values;
        }
    }

    @Test
    @DisplayName("A/B 对照 + content/embeddingText 消融：三组均能产出指标（离线冒烟）")
    void abAndAblationSmokeTest() {
        AcademicDocument document = sampleDocument();
        List<RetrievalGoldCase> gold = sampleGold();
        EmbeddingGateway gateway = new LexicalGateway();

        List<RetrievalUnit> legacyCorpus = OfflineRetrievalQa.buildLegacyCorpus(document, 800, 120);
        List<RetrievalUnit> contentCorpus =
                OfflineRetrievalQa.buildSectionAwareCorpus(sampleChunks(), TextMode.CONTENT);
        List<RetrievalUnit> embeddingTextCorpus =
                OfflineRetrievalQa.buildSectionAwareCorpus(sampleChunks(), TextMode.EMBEDDING_TEXT);

        List<Experiment> experiments = List.of(
                OfflineRetrievalQa.run("A-legacy", legacyCorpus, gold, gateway, 10),
                OfflineRetrievalQa.run("B1-content", contentCorpus, gold, gateway, 10),
                OfflineRetrievalQa.run("B2-embeddingText", embeddingTextCorpus, gold, gateway, 10));

        experiments.forEach(experiment -> System.out.println("  " + experiment.name()
                + " units=" + experiment.unitCount() + "  " + experiment.metrics().summary()));

        // 语料构建
        assertThat(legacyCorpus).isNotEmpty();
        assertThat(contentCorpus).hasSize(3);
        assertThat(embeddingTextCorpus).hasSize(3);

        // 旧链路也需要页码/blockId 证据（span 映射生效），否则 A/B 口径不对等
        assertThat(legacyCorpus).allSatisfy(unit -> assertThat(unit.sourceBlockIds()).isNotEmpty());
        assertThat(legacyCorpus).allSatisfy(unit -> assertThat(unit.pages()).isNotEmpty());

        // 指标机制
        experiments.forEach(experiment ->
                assertThat(experiment.metrics().caseCount()).isEqualTo(gold.size()));

        // B2 用带 Section 前缀的文本 → 应有命中（词袋下也能稳定命中，因为含稀有 token）
        assertThat(experiments.get(2).metrics().recallAt5()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("精确余弦：查询与某单元完全一致时排第一；空向量不抛异常")
    void exactCosineRanking() {
        List<RetrievalUnit> units = List.of(
                RetrievalUnit.of("u1", "alpha", "TEXT", List.of(), List.of(), List.of()),
                RetrievalUnit.of("u2", "beta", "TEXT", List.of(), List.of(), List.of()));
        List<float[]> vectors = List.of(new float[] {1f, 0f}, new float[] {0f, 1f});

        assertThat(ExactCosineRetriever.topK(units, vectors, new float[] {1f, 0f}, 1))
                .extracting(RetrievalUnit::id).containsExactly("u1");
        assertThat(ExactCosineRetriever.topK(units, vectors, new float[] {0f, 1f}, 2))
                .extracting(RetrievalUnit::id).containsExactly("u2", "u1");
        assertThat(ExactCosineRetriever.topK(units, vectors, new float[] {0f, 0f}, 2)).isNotEmpty();
        assertThat(ExactCosineRetriever.topK(List.of(), List.of(), new float[] {1f}, 3)).isEmpty();
    }

    // ==================== 样例数据 ====================

    /** 用稀有 token 保证词袋 embedding 下的排序是确定性的。 */
    private AcademicDocument sampleDocument() {
        Section methods = Section.builder()
                .id("s-methods").sourceBlockId("s-methods").title("4. Methods").level(1)
                .path(new ArrayList<>(List.of("4. Methods")))
                .blocks(new ArrayList<>(List.of(
                        paragraph("/page/1/Text/1", "Alpha kryptoniteprotocol describes the pipeline.", 1))))
                .children(new ArrayList<>())
                .build();
        Section results = Section.builder()
                .id("s-results").sourceBlockId("s-results").title("5. Results").level(1)
                .path(new ArrayList<>(List.of("5. Results")))
                .blocks(new ArrayList<>(List.of(
                        paragraph("/page/2/Text/1", "Beta unobtainiummetric reached a high value.", 2),
                        table("/page/3/Table/1", "Table 1. Results.", "metric\tvalue\nzed\t0.95", 3))))
                .children(new ArrayList<>())
                .build();
        return AcademicDocument.builder()
                .documentId("paper-domain-id")
                .metadata(PaperMetadata.builder().title("Sample Paper").build())
                .sections(new ArrayList<>(List.of(methods, results)))
                .references(new ArrayList<>())
                .build();
    }

    /** 模拟 SectionAwareChunker 的输出（避免在该测试里重复装配分块器）。 */
    private List<AcademicChunk> sampleChunks() {
        return List.of(
                AcademicChunk.builder().chunkId("paper-domain-id:s-methods:0").documentId("paper-domain-id")
                        .chunkType(ChunkType.TEXT).sectionId("s-methods").sectionTitle("4. Methods")
                        .sectionPath(new ArrayList<>(List.of("4. Methods"))).sectionLevel(1)
                        .content("Alpha kryptoniteprotocol describes the pipeline.")
                        .embeddingText("Document: Paper Title\nSection: 4. Methods\n\nContent:\n"
                                + "Alpha kryptoniteprotocol describes the pipeline.")
                        .blockTypes(new ArrayList<>(List.of(BlockKind.PARAGRAPH)))
                        .sourceBlockIds(new ArrayList<>(List.of("/page/1/Text/1")))
                        .startPage(1).endPage(1).order(0).build(),
                AcademicChunk.builder().chunkId("paper-domain-id:s-results:0").documentId("paper-domain-id")
                        .chunkType(ChunkType.TEXT).sectionId("s-results").sectionTitle("5. Results")
                        .sectionPath(new ArrayList<>(List.of("5. Results"))).sectionLevel(1)
                        .content("Beta unobtainiummetric reached a high value.")
                        .embeddingText("Document: Paper Title\nSection: 5. Results\n\nContent:\n"
                                + "Beta unobtainiummetric reached a high value.")
                        .blockTypes(new ArrayList<>(List.of(BlockKind.PARAGRAPH)))
                        .sourceBlockIds(new ArrayList<>(List.of("/page/2/Text/1")))
                        .startPage(2).endPage(2).order(1).build(),
                AcademicChunk.builder().chunkId("paper-domain-id:s-results:1").documentId("paper-domain-id")
                        .chunkType(ChunkType.TABLE).sectionId("s-results").sectionTitle("5. Results")
                        .sectionPath(new ArrayList<>(List.of("5. Results"))).sectionLevel(1)
                        .content("Table 1. Results.\nmetric\tvalue\nzed\t0.95")
                        .embeddingText("Document: Paper Title\nSection: 5. Results\n\nContent:\n"
                                + "Table 1. Results.\nmetric\tvalue\nzed\t0.95")
                        .blockTypes(new ArrayList<>(List.of(BlockKind.TABLE)))
                        .sourceBlockIds(new ArrayList<>(List.of("/page/3/Table/1")))
                        .startPage(3).endPage(3).order(2).build());
    }

    private List<RetrievalGoldCase> sampleGold() {
        return List.of(
                RetrievalGoldCase.of("q1", "paper-domain-id",
                        "What does kryptoniteprotocol describe?", QuestionType.METHOD, null, "/page/1/Text/1"),
                RetrievalGoldCase.of("q2", "paper-domain-id",
                        "What value did unobtainiummetric reach?", QuestionType.NUMERIC, null, "/page/2/Text/1"),
                RetrievalGoldCase.of("q3", "paper-domain-id",
                        "What does the zed row of the results table show?", QuestionType.TABLE, null,
                        "/page/3/Table/1"));
    }

    private ParagraphBlock paragraph(String id, String text, int page) {
        return ParagraphBlock.builder()
                .id(id).sourceBlockId(id)
                .sourceBlockIds(new ArrayList<>(List.of(id)))
                .text(text).order(page)
                .pageRange(PageRange.builder().startPage(page).endPage(page).build())
                .build();
    }

    private TableBlock table(String id, String caption, String content, int page) {
        return TableBlock.builder()
                .id(id)
                .sourceBlockIds(new ArrayList<>(List.of(id)))
                .caption(caption).content(content)
                .order(page)
                .pageRange(PageRange.builder().startPage(page).endPage(page).build())
                .build();
    }

    private List<DocumentBlock> asBlocks(DocumentBlock... blocks) {
        return new ArrayList<>(List.of(blocks));
    }
}
