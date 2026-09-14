package com.example.ragdemo.document.parser.grobid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Author;
import com.example.ragdemo.document.domain.CitationMarker;
import com.example.ragdemo.document.domain.PaperMetadata;
import com.example.ragdemo.document.domain.Reference;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.FormulaBlock;
import com.example.ragdemo.document.domain.block.ListBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;
import com.example.ragdemo.document.parser.grobid.exception.GrobidDocumentParseException;

/**
 * GrobidDocumentParser 单元测试：不依赖 GROBID HTTP 服务 / Docker。
 * <p>
 * 覆盖 skill §31 建议的全部测试点 + §32 section 层级专项 + §33 Parser/Cleaner 职责边界。
 * 测试数据基于 src/test/resources/grobid/ 下的 TEI fixture。
 */
class GrobidDocumentParserTest {

    private final GrobidDocumentParser parser = new GrobidDocumentParser();

    private String readFixture(String name) throws Exception {
        return new String(new ClassPathResource("grobid/" + name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    // ==================== metadata ====================

    @Test
    void shouldParseMetadata() throws Exception {
        AcademicDocument doc = parser.parse("doc-42", readFixture("full.tei.xml"));
        PaperMetadata metadata = doc.getMetadata();

        assertThat(metadata.getTitle()).isEqualTo("A self-supervised electrocardiogram foundation model");
        assertThat(metadata.getDoi()).isEqualTo("10.1038/s41467-026-72436-2");
        assertThat(metadata.getJournal()).isEqualTo("Nature Communications");
        assertThat(metadata.getPublisher()).isEqualTo("Nature Publishing Group");
        assertThat(metadata.getKeywords()).containsExactly("ECG", "foundation model", "self-supervised learning");
        assertThat(metadata.getPublicationDate().getYear()).isEqualTo(2026);
        assertThat(metadata.getPublicationDate().getMonth()).isEqualTo(8);
        assertThat(metadata.getPublicationDate().getDay()).isEqualTo(26);
    }

    // ==================== authors & affiliations ====================

    @Test
    void shouldParseAuthorsAndAffiliations() throws Exception {
        AcademicDocument doc = parser.parse("doc-42", readFixture("full.tei.xml"));
        PaperMetadata metadata = doc.getMetadata();

        // 第一个作者：结构化姓名分离 + ORCID
        Author first = metadata.getAuthors().get(0);
        assertThat(first.getFirstName()).isEqualTo("Siying");
        assertThat(first.getLastName()).isEqualTo("Lin");
        assertThat(first.getOrcid()).isEqualTo("0000-0002-5677-2128");
        assertThat(first.getEmail()).isEqualTo("siying.lin@example.org");
        // fullName 不混入 affiliation 文本
        assertThat(first.getFullName()).isEqualTo("Siying Lin");
        assertThat(first.getFullName()).doesNotContain("University");

        // 中间名：Ana Maria Garcia → firstName=Ana, middleName=Maria
        Author third = metadata.getAuthors().get(2);
        assertThat(third.getFirstName()).isEqualTo("Ana");
        assertThat(third.getMiddleName()).isEqualTo("Maria");
        assertThat(third.getLastName()).isEqualTo("Garcia");

        // affiliation 去重：aff0 被两位作者引用，只出现一次
        assertThat(metadata.getAffiliations()).hasSize(2);
        assertThat(metadata.getAffiliations().get(0).getId()).isEqualTo("aff0");
        assertThat(metadata.getAffiliations().get(0).getInstitution()).isEqualTo("University of XXX");
        assertThat(metadata.getAffiliations().get(0).getDepartment()).isEqualTo("Department of Computer Science");
        assertThat(metadata.getAffiliations().get(0).getCountry()).isEqualTo("Japan");
        assertThat(metadata.getAffiliations().get(1).getInstitution()).isEqualTo("Hospital YYY");

        // 作者 affiliationIds 关联
        assertThat(first.getAffiliationIds()).containsExactly("aff0");
        assertThat(metadata.getAuthors().get(1).getAffiliationIds()).containsExactly("aff0");
        assertThat(third.getAffiliationIds()).containsExactly("aff1");
    }

    // ==================== abstract ====================

    @Test
    void shouldParseAbstract() throws Exception {
        AcademicDocument doc = parser.parse("doc-42", readFixture("full.tei.xml"));

        // 两段 abstract 用 \n\n 合并，忠实映射不修复边界
        assertThat(doc.getAbstractText()).startsWith("Electrocardiogram (ECG) analysis is crucial");
        assertThat(doc.getAbstractText()).contains("\n\n");
        assertThat(doc.getAbstractText()).endsWith("rhythm and morphology classification.");
    }

    // ==================== body / section ====================

    @Test
    void shouldPreserveUnlabeledBodyContent() throws Exception {
        AcademicDocument doc = parser.parse("doc-42", readFixture("full.tei.xml"));

        // body 开头无 <head> 的 div 必须保留正文，title=null 且不自动命名 Introduction
        Section first = doc.getSections().get(0);
        assertThat(first.getTitle()).isNull();
        assertThat(first.getBlocks()).hasSize(1);
        assertThat(((ParagraphBlock) first.getBlocks().get(0)).getText())
                .isEqualTo("Unlabeled preamble paragraph that must be preserved.");
    }

    @Test
    void shouldPreserveSectionOrder() throws Exception {
        AcademicDocument doc = parser.parse("doc-42", readFixture("full.tei.xml"));

        // 平铺 sibling 保持 TEI 原始顺序，不强猜 Methods 是 Data sources 的父节点
        List<String> titles = doc.getSections().stream()
                .map(Section::getTitle)
                .toList();
        assertThat(titles).containsExactly(
                null, "Introduction", "Results", "Discussion", "Methods", "Ethics", "Data sources");
        // Methods 没有子 section（TEI 未提供嵌套）
        Section methods = doc.getSections().get(4);
        assertThat(methods.getChildren()).isEmpty();
    }

    @Test
    void shouldParseParagraphCitationMarkers() throws Exception {
        AcademicDocument doc = parser.parse("doc-42", readFixture("full.tei.xml"));

        Section intro = doc.getSections().get(1);
        ParagraphBlock firstPara = (ParagraphBlock) intro.getBlocks().get(0);
        // 可见文本保留 ref 文本（TEI ref 可见内容通常不含方括号，方括号由 GROBID 决定）
        assertThat(firstPara.getText()).contains("Previous studies 8 and 12 showed");
        assertThat(firstPara.getText()).contains("contrastive learning works well");
        // citation markers 生成：b8 / b12
        List<CitationMarker> citations = firstPara.getCitations();
        assertThat(citations).hasSize(2);
        assertThat(citations.get(0).getTargetReferenceIds()).containsExactly("b8");
        assertThat(citations.get(1).getTargetReferenceIds()).containsExactly("b12");
    }

    // ==================== figure / table / formula / list ====================

    @Test
    void shouldParseFigure() throws Exception {
        AcademicDocument doc = parser.parse("doc-42", readFixture("full.tei.xml"));

        Section results = doc.getSections().get(2);
        DocumentBlock block = results.getBlocks().get(1);
        assertThat(block).isInstanceOf(FigureBlock.class);
        FigureBlock figure = (FigureBlock) block;
        assertThat(figure.getId()).isEqualTo("fig_0"); // 保留 xml:id，不生成 UUID
        assertThat(figure.getLabel()).isEqualTo("2");
        assertThat(figure.getCaption()).isEqualTo("Overall architecture of the proposed framework.");
        // coords="4 ...; 5 ..." → startPage=4, endPage=5
        assertThat(figure.getPageRange().getStartPage()).isEqualTo(4);
        assertThat(figure.getPageRange().getEndPage()).isEqualTo(5);
    }

    @Test
    void shouldParseTable() throws Exception {
        AcademicDocument doc = parser.parse("doc-42", readFixture("full.tei.xml"));

        Section results = doc.getSections().get(2);
        DocumentBlock block = results.getBlocks().get(2);
        // figure type="table" 必须映射为 TableBlock，不是 FigureBlock
        assertThat(block).isInstanceOf(TableBlock.class);
        TableBlock table = (TableBlock) block;
        assertThat(table.getId()).isEqualTo("tab_0");
        assertThat(table.getLabel()).isEqualTo("1");
        assertThat(table.getCaption()).isEqualTo("Benchmark results on three public datasets.");
        // TSV 内容：row \n、cell \t
        assertThat(table.getContent()).isEqualTo(
                "Model\tPTB-XL\tChapman\nECG-LFM\t0.951\t0.993");
        // 无 coords → pageRange 为 null，不伪造页码
        assertThat(table.getPageRange()).isNull();
    }

    @Test
    void shouldParseFormula() throws Exception {
        AcademicDocument doc = parser.parse("doc-42", readFixture("full.tei.xml"));

        Section results = doc.getSections().get(2);
        DocumentBlock block = results.getBlocks().get(3);
        assertThat(block).isInstanceOf(FormulaBlock.class);
        FormulaBlock formula = (FormulaBlock) block;
        assertThat(formula.getId()).isEqualTo("formula_0");
        assertThat(formula.getLabel()).isEqualTo("(1)");
        // 公式正文不含 label 编号
        assertThat(formula.getFormula()).doesNotContain("(1)");
        assertThat(formula.getFormula()).contains("L");
    }

    @Test
    void shouldParseList() throws Exception {
        AcademicDocument doc = parser.parse("doc-42", readFixture("full.tei.xml"));

        Section results = doc.getSections().get(2);
        DocumentBlock block = results.getBlocks().get(4);
        assertThat(block).isInstanceOf(ListBlock.class);
        assertThat(((ListBlock) block).getItems()).containsExactly("Encoder", "Projection head", "Classifier");
    }

    // ==================== references ====================

    @Test
    void shouldParseReferences() throws Exception {
        AcademicDocument doc = parser.parse("doc-42", readFixture("full.tei.xml"));

        List<Reference> references = doc.getReferences();
        assertThat(references).hasSize(3);

        Reference b1 = references.get(0);
        assertThat(b1.getId()).isEqualTo("b1");
        assertThat(b1.getTitle()).isEqualTo("Automatic diagnosis of the 12-lead ECG using a deep neural network");
        assertThat(b1.getAuthors()).containsExactly("A Ribeiro");
        assertThat(b1.getJournal()).isEqualTo("Nature Communications");
        assertThat(b1.getVolume()).isEqualTo("11");
        assertThat(b1.getIssue()).isEqualTo("1");
        assertThat(b1.getPages()).isEqualTo("1-9");
        assertThat(b1.getDoi()).isEqualTo("10.1038/s41467-020-15432-4");
        assertThat(b1.getPublicationDate().getYear()).isEqualTo(2020);
        assertThat(b1.getRawText()).isNotBlank();

        Reference b8 = references.get(1);
        assertThat(b8.getArxivId()).isEqualTo("arXiv:2002.05709");
        assertThat(b8.getJournal()).isEqualTo("ICML");

        Reference b12 = references.get(2);
        assertThat(b12.getUrl()).isEqualTo("https://arxiv.org/abs/1911.05722");
    }

    @Test
    void citationTarget_mapsToReferenceId() throws Exception {
        AcademicDocument doc = parser.parse("doc-42", readFixture("full.tei.xml"));

        Section intro = doc.getSections().get(1);
        ParagraphBlock para = (ParagraphBlock) intro.getBlocks().get(0);
        // citation target b8 能对应 Reference.id = b8，关联闭环
        for (CitationMarker marker : para.getCitations()) {
            for (String targetId : marker.getTargetReferenceIds()) {
                assertThat(doc.getReferences())
                        .extracting(Reference::getId)
                        .contains(targetId);
            }
        }
    }

    // ==================== parserInfo ====================

    @Test
    void shouldParseParserInfo() throws Exception {
        AcademicDocument doc = parser.parse("doc-42", readFixture("full.tei.xml"));

        assertThat(doc.getParserInfo().getParserType()).isEqualTo("GROBID");
        assertThat(doc.getParserInfo().getParserVersion()).isEqualTo("0.9.0");
        assertThat(doc.getParserInfo().getStructureVersion()).isEqualTo("academic-document-v1");
        assertThat(doc.getParserInfo().getParsedAt()).isNotNull();
    }

    // ==================== 容错 ====================

    @Test
    void shouldAllowMissingOptionalFields() throws Exception {
        AcademicDocument doc = parser.parse("doc-7", readFixture("minimal.tei.xml"));

        // title 从 titleStmt fallback 成功
        assertThat(doc.getMetadata().getTitle()).isEqualTo("Minimal Paper");
        assertThat(doc.getMetadata().getDoi()).isNull();
        assertThat(doc.getMetadata().getAuthors()).isEmpty();
        assertThat(doc.getAbstractText()).isNull();
        assertThat(doc.getSections()).isEmpty();
        assertThat(doc.getReferences()).isEmpty();
        assertThat(doc.getParserInfo()).isNull();
    }

    @Test
    void shouldRejectMalformedXml() throws Exception {
        assertThatThrownBy(() -> parser.parse("doc-1", readFixture("invalid.tei.xml")))
                .isInstanceOf(GrobidDocumentParseException.class);
    }

    @Test
    void shouldRejectBlankTei() {
        assertThatThrownBy(() -> parser.parse("doc-1", "   "))
                .isInstanceOf(GrobidDocumentParseException.class);
    }

    @Test
    void shouldRejectNonTeiRoot() {
        assertThatThrownBy(() -> parser.parse("doc-1", "<html><body/></html>"))
                .isInstanceOf(GrobidDocumentParseException.class);
    }

    // ==================== section 层级专项（§32） ====================

    @Test
    void nestedSections_buildChildrenLevelAndPath() throws Exception {
        AcademicDocument doc = parser.parse("doc-9", readFixture("nested-sections.tei.xml"));

        assertThat(doc.getSections()).hasSize(2);
        Section methods = doc.getSections().get(0);
        assertThat(methods.getTitle()).isEqualTo("Methods");
        assertThat(methods.getLevel()).isEqualTo(1);
        assertThat(methods.getPath()).containsExactly("Methods");
        assertThat(methods.getChildren()).hasSize(1);

        Section dataset = methods.getChildren().get(0);
        assertThat(dataset.getTitle()).isEqualTo("Dataset");
        assertThat(dataset.getLevel()).isEqualTo(2);
        assertThat(dataset.getPath()).containsExactly("Methods", "Dataset");
        assertThat(dataset.getChildren()).hasSize(1);

        Section preprocessing = dataset.getChildren().get(0);
        assertThat(preprocessing.getTitle()).isEqualTo("Preprocessing");
        assertThat(preprocessing.getLevel()).isEqualTo(3);
        assertThat(preprocessing.getPath()).containsExactly("Methods", "Dataset", "Preprocessing");

        // 三级 section 的正文 C 保留
        assertThat(((ParagraphBlock) preprocessing.getBlocks().get(0)).getText()).isEqualTo("C");

        // 平铺的 Conclusion 不是 Methods 的子节点
        assertThat(doc.getSections().get(1).getTitle()).isEqualTo("Conclusion");
        assertThat(methods.getChildren()).extracting(Section::getTitle).doesNotContain("Conclusion");
    }

    // ==================== Parser/Cleaner 职责边界（§33） ====================

    @Test
    void parser_isFaithfulMapping_notAutoFix() throws Exception {
        // 摘要区两个 <p> 即使语义上可能属于不同部分，Parser 也必须忠实合并，不自动修复
        AcademicDocument doc = parser.parse("doc-42", readFixture("full.tei.xml"));
        assertThat(doc.getAbstractText()).contains("We present a self-supervised foundation model");
    }
}
