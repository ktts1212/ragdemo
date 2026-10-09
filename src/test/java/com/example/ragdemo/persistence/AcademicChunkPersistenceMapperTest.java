package com.example.ragdemo.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ragdemo.document.chunk.AcademicChunk;
import com.example.ragdemo.document.chunk.BlockKind;
import com.example.ragdemo.document.chunk.ChunkType;
import com.example.ragdemo.embedding.EmbeddingGateway;
import com.example.ragdemo.model.param.DocumentChunkInsertParam;
import com.example.ragdemo.util.VectorUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link AcademicChunkPersistenceMapper} 单测（skill §5 / §7 / §12）。
 * <p>
 * 两条核心契约：
 * <ol>
 *   <li>{@code document_chunks.document_id} 写入的是传入的 {@code dbDocumentId}，
 *       <b>不是</b> {@code AcademicChunk.documentId}（domain 与 DB 解耦）</li>
 *   <li>embedding 必须由 {@code embeddingText} 计算，而不是 {@code content}</li>
 * </ol>
 */
class AcademicChunkPersistenceMapperTest {

    private final AcademicChunkPersistenceMapper mapper =
            new AcademicChunkPersistenceMapper(new ObjectMapper());

    private AcademicChunk sampleChunk() {
        return AcademicChunk.builder()
                .chunkId("paper-domain-id:/page/3/SectionHeader/8:1")
                .documentId("paper-domain-id")
                .chunkType(ChunkType.TABLE)
                .sectionId("/page/3/SectionHeader/8")
                .sectionTitle("4.1 Datasets")
                .sectionPath(new ArrayList<>(List.of("4. Methods", "4.1 Datasets")))
                .sectionLevel(2)
                .content("Table 3. Performance results.")
                .embeddingText("Document: A Paper\nSection: 4. Methods > 4.1 Datasets\n\nContent:\n"
                        + "Table 3. Performance results.")
                .blockTypes(new ArrayList<>(List.of(BlockKind.TABLE)))
                .sourceBlockIds(new ArrayList<>(List.of("/page/4/Table/12")))
                .startPage(4)
                .endPage(5)
                .tokenCount(42)
                .charCount(31)
                .order(7)
                .contentHash("abcdef0123456789")
                .titleContext("A Paper")
                .build();
    }

    @Test
    @DisplayName("domain documentId 与 dbDocumentId 解耦；专列与 JSONB 长尾分别落位")
    void mapsCoreFieldsAndDecouplesDocumentId() {
        DocumentChunkInsertParam param = mapper.toInsertParam(sampleChunk(), 42L, 1L,
                new float[] {0.5f, 0.25f});

        // persistence key：用的是 dbDocumentId，而不是领域 id
        assertThat(param.getDocumentId()).isEqualTo(42L);
        assertThat(param.getKnowledgeBaseId()).isEqualTo(1L);
        assertThat(param.getChunkIndex()).isEqualTo(7);
        assertThat(param.getChunkKey()).isEqualTo("paper-domain-id:/page/3/SectionHeader/8:1");

        // content 与 embeddingText 分离
        assertThat(param.getContent()).isEqualTo("Table 3. Performance results.");
        assertThat(param.getEmbeddingText()).startsWith("Document: A Paper")
                .contains("Section: 4. Methods > 4.1 Datasets");
        assertThat(param.getEmbedding()).isEqualTo("[0.5,0.25]");

        // 专列
        assertThat(param.getChunkType()).isEqualTo("TABLE");
        assertThat(param.getSectionId()).isEqualTo("/page/3/SectionHeader/8");
        assertThat(param.getSectionTitle()).isEqualTo("4.1 Datasets");
        assertThat(param.getSectionLevel()).isEqualTo(2);
        assertThat(param.getSectionPath()).isEqualTo("{\"4. Methods\",\"4.1 Datasets\"}");
        assertThat(param.getContentHash()).isEqualTo("abcdef0123456789");
        assertThat(param.getPageNumber()).isEqualTo(4);
        assertThat(param.getEndPage()).isEqualTo(5);
        assertThat(param.getTokenCount()).isEqualTo(42);

        // 新链路无字符偏移 → 不伪造
        assertThat(param.getStartOffset()).isNull();
        assertThat(param.getEndOffset()).isNull();

        // 长尾 JSONB
        assertThat(param.getMetadata())
                .contains("\"sourceBlockIds\":[\"/page/4/Table/12\"]")
                .contains("\"charCount\":31")
                .contains("\"oversized\":false")
                .contains("\"undersized\":false");
    }

    @Test
    @DisplayName("embedding 由 embeddingText 计算，而不是 content（契约级保证）")
    void embeddingComesFromEmbeddingTextNotContent() {
        AtomicReference<String> embeddedText = new AtomicReference<>();
        EmbeddingGateway gateway = new EmbeddingGateway() {
            @Override
            public float[] embedQuery(String text) {
                return new float[] {1f, 0f};
            }

            @Override
            public List<float[]> embedDocuments(List<String> texts) {
                embeddedText.set(texts.get(0));
                return List.of(new float[] {1f, 0f});
            }
        };

        mapper.toInsertParam(sampleChunk(), 1L, 1L, gateway);

        assertThat(embeddedText.get()).isEqualTo(sampleChunk().getEmbeddingText());
        assertThat(embeddedText.get()).isNotEqualTo(sampleChunk().getContent());
        assertThat(embeddedText.get()).contains("Section:");
    }

    @Test
    @DisplayName("embeddingText 为空 / embedding 为空 → 明确报错，不静默写入")
    void rejectsMissingEmbeddingInput() {
        AcademicChunk blankText = AcademicChunk.builder()
                .chunkId("c1").documentId("d").chunkType(ChunkType.TEXT)
                .content("body").embeddingText("  ").build();
        // 契约检查发生在「由 embeddingText 现算」的重载里（生产入口）
        EmbeddingGateway stubGateway = new EmbeddingGateway() {
            @Override
            public float[] embedQuery(String text) {
                return new float[] {1f};
            }

            @Override
            public List<float[]> embedDocuments(List<String> texts) {
                return List.of(new float[] {1f});
            }
        };
        assertThatThrownBy(() -> mapper.toInsertParam(blankText, 1L, 1L, stubGateway))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embeddingText 为空");

        assertThatThrownBy(() -> mapper.toInsertParam(sampleChunk(), 1L, 1L, new float[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding 不能为空");
    }

    @Test
    @DisplayName("text[] 字面量：转义引号/反斜杠，空列表为 {}")
    void pgTextArrayLiteralEscaping() {
        assertThat(VectorUtils.toPgTextArray(List.of("A \"q\"", "B\\C")))
                .isEqualTo("{\"A \\\"q\\\"\",\"B\\\\C\"}");
        assertThat(VectorUtils.toPgTextArray(List.of())).isEqualTo("{}");
        assertThat(VectorUtils.toPgTextArray(null)).isEqualTo("{}");
    }
}
