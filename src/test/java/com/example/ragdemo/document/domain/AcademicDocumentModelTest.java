package com.example.ragdemo.document.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.FormulaBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 学术文档领域模型测试：
 * 1. 构建测试（Section 树 / path / block 类型 / Reference / CitationMarker）
 * 2. Jackson 序列化往返（验证 DocumentBlock 具体子类型可恢复）
 * 3. Citation → Reference 关联表达
 */
class AcademicDocumentModelTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /** 构造 skill §13 验收标准中的论文结构：Paper → Methods → (Dataset: paragraphs+table / Model: paragraphs+figure+formula) */
    private AcademicDocument buildSampleDocument() {
        // ---- 元数据 ----
        PaperMetadata metadata = PaperMetadata.builder()
                .title("A self-supervised electrocardiogram foundation model")
                .authors(List.of(
                        Author.builder()
                                .fullName("Kashif Shaheed")
                                .lastName("Shaheed")
                                .affiliationIds(List.of("aff-1"))
                                .build()))
                .affiliations(List.of(
                        Affiliation.builder()
                                .id("aff-1")
                                .institution("University of XXX")
                                .department("Department of Computer Science")
                                .country("Japan")
                                .build()))
                .keywords(List.of("ECG", "self-supervised learning"))
                .doi("10.1234/ecg-fm")
                .publicationDate(PartialDate.builder().year(2024).month(3).rawText("March 2024").build())
                .build();

        // ---- 引用 ----
        Reference ref12 = Reference.builder()
                .id("ref-12")
                .rawText("[12] Chen et al. A framework for contrastive learning. 2020.")
                .title("A framework for contrastive learning")
                .authors(List.of("Chen et al."))
                .journal("ICML")
                .publicationDate(PartialDate.builder().year(2020).build())
                .build();
        Reference ref15 = Reference.builder()
                .id("ref-15")
                .rawText("[15] He et al. Momentum contrast. 2020.")
                .title("Momentum contrast")
                .authors(List.of("He et al."))
                .build();

        // ---- Methods / Dataset ----
        Section dataset = Section.builder()
                .id("sec-dataset")
                .title("Dataset")
                .level(2)
                .path(List.of("Methods", "Dataset"))
                .order(0)
                .build();
        dataset.getBlocks().add(ParagraphBlock.builder()
                .id("blk-ds-p1")
                .text("We use the PhysioNet 2021 dataset containing 12-lead ECGs.")
                .order(0)
                .pageRange(PageRange.builder().startPage(3).endPage(3).build())
                .build());
        dataset.getBlocks().add(TableBlock.builder()
                .id("blk-ds-t1")
                .label("Table 1")
                .caption("Dataset statistics")
                .content("train=68000, val=10000, test=20000")
                .order(1)
                .pageRange(PageRange.builder().startPage(3).build())
                .build());

        // ---- Methods / Model ----
        Section model = Section.builder()
                .id("sec-model")
                .title("Model")
                .level(2)
                .path(List.of("Methods", "Model"))
                .order(1)
                .build();
        ParagraphBlock modelP1 = ParagraphBlock.builder()
                .id("blk-model-p1")
                .text("Previous studies [12, 15] showed that contrastive learning works well.")
                .order(0)
                .pageRange(PageRange.builder().startPage(4).endPage(5).build())
                .build();
        modelP1.getCitations().add(CitationMarker.builder()
                .text("[12, 15]")
                .targetReferenceIds(List.of("ref-12", "ref-15"))
                .build());
        model.getBlocks().add(modelP1);
        model.getBlocks().add(FigureBlock.builder()
                .id("blk-model-f1")
                .label("Figure 2")
                .caption("Overall architecture of the proposed framework.")
                .order(1)
                .pageRange(PageRange.builder().startPage(4).build())
                .build());
        model.getBlocks().add(FormulaBlock.builder()
                .id("blk-model-fm1")
                .label("(1)")
                .formula("L = -log exp(sim(z_i, z_j)/tau)")
                .order(2)
                .pageRange(PageRange.builder().startPage(4).build())
                .build());

        Section methods = Section.builder()
                .id("sec-methods")
                .title("Methods")
                .level(1)
                .path(List.of("Methods"))
                .order(0)
                .build();
        methods.getChildren().add(dataset);
        methods.getChildren().add(model);

        return AcademicDocument.builder()
                .documentId("doc-42")
                .metadata(metadata)
                .abstractText("We present a self-supervised foundation model for ECG.")
                .sections(List.of(methods))
                .references(List.of(ref12, ref15))
                .parserInfo(ParserInfo.builder()
                        .parserType("GROBID")
                        .parserVersion("0.8.0")
                        .structureVersion("academic-document-v1")
                        .parsedAt(Instant.parse("2026-08-26T10:00:00Z"))
                        .build())
                .build();
    }

    // ==================== 11.1 构建测试 ====================

    @Test
    void build_sectionTree_andBlocks_areExpressible() {
        AcademicDocument doc = buildSampleDocument();

        // 元数据
        assertThat(doc.getMetadata().getTitle()).contains("electrocardiogram");
        assertThat(doc.getMetadata().getAuthors()).hasSize(1);
        assertThat(doc.getMetadata().getAuthors().get(0).getAffiliationIds()).containsExactly("aff-1");
        assertThat(doc.getMetadata().getAffiliations()).hasSize(1);
        assertThat(doc.getMetadata().getPublicationDate().getYear()).isEqualTo(2024);
        assertThat(doc.getMetadata().getPublicationDate().getRawText()).isEqualTo("March 2024");
        assertThat(doc.getAbstractText()).startsWith("We present");

        // Section 树
        Section methods = doc.getSections().get(0);
        assertThat(methods.getTitle()).isEqualTo("Methods");
        assertThat(methods.getPath()).containsExactly("Methods");
        assertThat(methods.getChildren()).hasSize(2);

        Section dataset = methods.getChildren().get(0);
        assertThat(dataset.getPath()).containsExactly("Methods", "Dataset");
        assertThat(dataset.getLevel()).isEqualTo(2);

        // Block 类型
        assertThat(dataset.getBlocks()).hasSize(2);
        assertThat(dataset.getBlocks().get(0)).isInstanceOf(ParagraphBlock.class);
        assertThat(dataset.getBlocks().get(1)).isInstanceOf(TableBlock.class);

        Section model = methods.getChildren().get(1);
        assertThat(model.getBlocks()).hasSize(3);
        assertThat(model.getBlocks().get(1)).isInstanceOf(FigureBlock.class);
        assertThat(model.getBlocks().get(2)).isInstanceOf(FormulaBlock.class);

        // 页码
        assertThat(model.getBlocks().get(0).getPageRange().getStartPage()).isEqualTo(4);
        assertThat(dataset.getBlocks().get(0).getPageRange().getEndPage()).isEqualTo(3);

        // 引用与参考文献
        assertThat(doc.getReferences()).hasSize(2);
        assertThat(doc.getParserInfo().getParserType()).isEqualTo("GROBID");
    }

    // ==================== 11.2 Jackson 往返测试 ====================

    @Test
    void jackson_roundTrip_preservesBlockTypes() throws Exception {
        AcademicDocument original = buildSampleDocument();

        String json = objectMapper.writeValueAsString(original);
        // JSON 中必须带 blockType 以恢复具体类型
        assertThat(json).contains("\"blockType\":\"PARAGRAPH\"");
        assertThat(json).contains("\"blockType\":\"TABLE\"");

        AcademicDocument restored = objectMapper.readValue(json, AcademicDocument.class);

        // 顶层字段
        assertThat(restored.getDocumentId()).isEqualTo("doc-42");
        assertThat(restored.getMetadata().getTitle()).isEqualTo(original.getMetadata().getTitle());
        assertThat(restored.getAbstractText()).isEqualTo(original.getAbstractText());
        assertThat(restored.getReferences()).hasSize(2);
        assertThat(restored.getReferences().get(0).getTitle()).isEqualTo("A framework for contrastive learning");

        // DocumentBlock 具体子类型恢复
        Section methods = restored.getSections().get(0);
        Section dataset = methods.getChildren().get(0);
        Section model = methods.getChildren().get(1);

        assertThat(dataset.getBlocks().get(0)).isInstanceOf(ParagraphBlock.class);
        assertThat(dataset.getBlocks().get(1)).isInstanceOf(TableBlock.class);
        assertThat(model.getBlocks().get(0)).isInstanceOf(ParagraphBlock.class);
        assertThat(model.getBlocks().get(1)).isInstanceOf(FigureBlock.class);
        assertThat(model.getBlocks().get(2)).isInstanceOf(FormulaBlock.class);

        // 字段内容不丢失
        FigureBlock figure = (FigureBlock) model.getBlocks().get(1);
        assertThat(figure.getLabel()).isEqualTo("Figure 2");
        assertThat(figure.getCaption()).isEqualTo("Overall architecture of the proposed framework.");
        assertThat(figure.getPageRange().getStartPage()).isEqualTo(4);

        ParagraphBlock paragraph = (ParagraphBlock) model.getBlocks().get(0);
        assertThat(paragraph.getText()).contains("contrastive learning");

        // 日期与 ParserInfo
        assertThat(restored.getMetadata().getPublicationDate().getRawText()).isEqualTo("March 2024");
        assertThat(restored.getParserInfo().getParserVersion()).isEqualTo("0.8.0");
    }

    // ==================== 11.3 Citation 关联测试 ====================

    @Test
    void citation_canReferenceReferenceIds() {
        AcademicDocument doc = buildSampleDocument();

        // 正文里的 citation marker 指向 ref-12 / ref-15
        Section model = doc.getSections().get(0).getChildren().get(1);
        ParagraphBlock paragraph = (ParagraphBlock) model.getBlocks().get(0);
        List<CitationMarker> citations = paragraph.getCitations();

        assertThat(citations).hasSize(1);
        CitationMarker marker = citations.get(0);
        assertThat(marker.getText()).isEqualTo("[12, 15]");
        assertThat(marker.getTargetReferenceIds()).containsExactly("ref-12", "ref-15");

        // Reference 侧存在对应的 id，可完成关联闭环
        for (String targetId : marker.getTargetReferenceIds()) {
            assertThat(doc.getReferences())
                    .extracting(Reference::getId)
                    .contains(targetId);
        }
    }
}
