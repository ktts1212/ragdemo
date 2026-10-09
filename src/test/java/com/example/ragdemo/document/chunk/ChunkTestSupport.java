package com.example.ragdemo.document.chunk;

import java.util.ArrayList;
import java.util.List;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PageRange;
import com.example.ragdemo.document.domain.PaperMetadata;
import com.example.ragdemo.document.domain.Reference;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.CaptionBlock;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.FootnoteBlock;
import com.example.ragdemo.document.domain.block.FormulaBlock;
import com.example.ragdemo.document.domain.block.ListBlock;
import com.example.ragdemo.document.domain.block.NoiseBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;

/**
 * chunk 包测试的公共装配与构造工具。
 * <p>
 * 所有构造都是确定性的：token 由 {@link CharBasedTokenEstimator}（字符 / 4）估算，
 * 因此测试里用"字符数"精确控制 token 预算。
 */
final class ChunkTestSupport {

    static final String TEST_TITLE = "Test Paper Title";

    private ChunkTestSupport() {
    }

    // ==================== 装配 ====================

    /** 默认参数：target=700 / max=1000 / min=180 / overlap 关闭 */
    static RagProperties defaultProperties() {
        RagProperties properties = new RagProperties();
        properties.setChunker(new RagProperties.Chunker());
        return properties;
    }

    static RagProperties properties(int target, int max, int min, boolean overlapEnabled) {
        RagProperties properties = new RagProperties();
        RagProperties.Chunker chunker = new RagProperties.Chunker();
        chunker.setTargetTokens(target);
        chunker.setMaxTokens(max);
        chunker.setMinTokens(min);
        chunker.setOverlapEnabled(overlapEnabled);
        properties.setChunker(chunker);
        return properties;
    }

    static SectionAwareChunker chunker() {
        return chunker(defaultProperties());
    }

    static SectionAwareChunker chunker(RagProperties properties) {
        ChunkTokenEstimator estimator = new CharBasedTokenEstimator();
        ChunkTextSplitter textSplitter = new ChunkTextSplitter(estimator);
        ChunkTextRenderer renderer = new ChunkTextRenderer(properties);
        ListChunker listChunker = new ListChunker(estimator, textSplitter, properties);
        FigureTableChunker figureTableChunker = new FigureTableChunker(estimator, properties);
        SectionBlockChunker sectionBlockChunker = new SectionBlockChunker(
                estimator, textSplitter, listChunker, figureTableChunker, properties);
        DocumentLevelChunker documentLevelChunker = new DocumentLevelChunker(
                estimator, textSplitter, properties);
        ChunkQualityReporter reporter = new ChunkQualityReporter();
        return new SectionAwareChunker(estimator, textSplitter, renderer, sectionBlockChunker,
                documentLevelChunker, reporter, properties);
    }

    // ==================== 文本工具 ====================

    /** 精确字符数文本（token = ceil(chars / 4)）。 */
    static String chars(int charCount) {
        return "a".repeat(charCount);
    }

    /** 由 n 个句子组成的长文本，每句 999 字符 + 句号（便于测试句子级切分）。 */
    static String sentences(int sentenceCount) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < sentenceCount; i++) {
            builder.append("a".repeat(999)).append(". ");
        }
        return builder.toString().trim();
    }

    // ==================== block 构造 ====================

    static ParagraphBlock para(String id, String text) {
        return ParagraphBlock.builder()
                .id(id)
                .sourceBlockId(id)
                .sourceBlockIds(new ArrayList<>(List.of(id)))
                .text(text)
                .build();
    }

    static ParagraphBlock para(String id, String text, int startPage, int endPage) {
        return ParagraphBlock.builder()
                .id(id)
                .sourceBlockId(id)
                .sourceBlockIds(new ArrayList<>(List.of(id)))
                .text(text)
                .pageRange(new PageRange(startPage, endPage))
                .build();
    }

    /** 跨页合并段落：一个逻辑段落由多个 raw block 组成，页码跨页。 */
    static ParagraphBlock paraMerged(String id, String text, int startPage, int endPage,
            List<String> sourceBlockIds) {
        return ParagraphBlock.builder()
                .id(id)
                .sourceBlockId(sourceBlockIds.isEmpty() ? id : sourceBlockIds.get(0))
                .sourceBlockIds(new ArrayList<>(sourceBlockIds))
                .text(text)
                .pageRange(new PageRange(startPage, endPage))
                .build();
    }

    static ListBlock list(String id, String... items) {
        return ListBlock.builder()
                .id(id)
                .sourceBlockId(id)
                .items(new ArrayList<>(List.of(items)))
                .build();
    }

    static FigureBlock figure(String id, String label, String caption, String descriptionText) {
        return FigureBlock.builder()
                .id(id)
                .sourceBlockIds(new ArrayList<>(List.of(id)))
                .label(label)
                .caption(caption)
                .descriptionText(descriptionText)
                .imageKey("figure-images/" + id + ".png")
                .build();
    }

    static TableBlock table(String id, String label, String caption, String content) {
        return TableBlock.builder()
                .id(id)
                .sourceBlockIds(new ArrayList<>(List.of(id)))
                .label(label)
                .caption(caption)
                .html("<table><tr><td>html-only</td></tr></table>")
                .content(content)
                .build();
    }

    static FormulaBlock formula(String id, String label, String formula) {
        return FormulaBlock.builder()
                .id(id)
                .sourceBlockId(id)
                .label(label)
                .formula(formula)
                .build();
    }

    static FootnoteBlock footnote(String id, String text) {
        return FootnoteBlock.builder()
                .id(id)
                .sourceBlockId(id)
                .text(text)
                .build();
    }

    static CaptionBlock caption(String id, String text) {
        return CaptionBlock.builder()
                .id(id)
                .sourceBlockId(id)
                .text(text)
                .build();
    }

    static NoiseBlock noise(String id, String text) {
        return NoiseBlock.builder()
                .id(id)
                .sourceBlockId(id)
                .text(text)
                .noiseType("PAGE_FOOTER")
                .build();
    }

    // ==================== Section / 文档构造 ====================

    static Section section(String id, String title, List<String> path, int level,
            List<DocumentBlock> blocks) {
        return Section.builder()
                .id(id)
                .sourceBlockId(id)
                .title(title)
                .level(level)
                .path(new ArrayList<>(path))
                .blocks(new ArrayList<>(blocks))
                .build();
    }

    static Section sectionOf(String id, String title, DocumentBlock... blocks) {
        return section(id, title, List.of(title), 1, new ArrayList<>(List.of(blocks)));
    }

    static AcademicDocument document(String documentId, String abstractText, List<Section> sections,
            List<Reference> references) {
        return AcademicDocument.builder()
                .documentId(documentId)
                .metadata(PaperMetadata.builder().title(TEST_TITLE).build())
                .abstractText(abstractText)
                .sections(new ArrayList<>(sections))
                .references(new ArrayList<>(references))
                .build();
    }

    static Reference reference(String id, String rawText) {
        return Reference.builder().id(id).rawText(rawText).build();
    }
}
