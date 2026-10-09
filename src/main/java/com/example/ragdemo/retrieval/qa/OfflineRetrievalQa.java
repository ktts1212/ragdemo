package com.example.ragdemo.retrieval.qa;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.example.ragdemo.document.chunk.AcademicChunk;
import com.example.ragdemo.document.chunk.BlockProvenance;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PageRange;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.ListBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;
import com.example.ragdemo.embedding.EmbeddingGateway;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;

/**
 * 离线 Retrieval QA（skill §17 / §21 / §22）。
 * <p>
 * <b>不依赖 pgvector / LLM / ChatService</b>：把语料 embedding 一次后，用
 * {@link ExactCosineRetriever} 在内存里做精确余弦检索。
 * <p>
 * 用于隔离比较：
 * <pre>
 * A（旧）：TextSplitterService 800/120 字符递归
 * B（新）：SectionAwareChunker
 * 消融 B1 = content  vs  B2 = embeddingText   ← 回答「Section 前缀到底有没有检索收益」
 * </pre>
 * 关键：A 组也要产出页码 / sourceBlockIds 证据（通过对文本建立 span→block 映射），
 * 否则 A/B 口径不对等（skill §18 注）。
 */
public final class OfflineRetrievalQa {

    private OfflineRetrievalQa() {
    }

    /** 新链路送入 embedding 的文本模式（skill §22 消融开关）。 */
    public enum TextMode {
        /** B1：只用纯净 content */
        CONTENT,
        /** B2：用带 Document/Section 前缀的 embeddingText */
        EMBEDDING_TEXT
    }

    /**
     * 一次实验的结果。
     *
     * @param name      实验名（如 {@code A-legacy} / {@code B1-content} / {@code B2-embeddingText}）
     * @param unitCount 语料单元数
     * @param metrics   检索指标
     */
    public record Experiment(String name, int unitCount, RetrievalMetrics metrics) {
    }

    // ==================== 语料构建 ====================

    /**
     * B 组语料：{@code SectionAwareChunker} 的输出 → {@link RetrievalUnit}。
     */
    public static List<RetrievalUnit> buildSectionAwareCorpus(List<AcademicChunk> chunks, TextMode mode) {
        List<RetrievalUnit> units = new ArrayList<>();
        if (chunks == null) {
            return units;
        }
        for (AcademicChunk chunk : chunks) {
            String text = mode == TextMode.EMBEDDING_TEXT ? chunk.getEmbeddingText() : chunk.getContent();
            units.add(RetrievalUnit.of(
                    chunk.getChunkId(),
                    text,
                    chunk.getChunkType() == null ? null : chunk.getChunkType().name(),
                    chunk.getSourceBlockIds(),
                    chunk.getSectionPath(),
                    pageList(chunk.getStartPage(), chunk.getEndPage())));
        }
        return units;
    }

    /**
     * A 组语料：拼接全文 → LangChain4j 递归字符切分（800/120）。
     * <p>
     * 为与 B 组同口径，构建时记录每个 block 的字符区间，切分后把每个 segment 映射回
     * 其覆盖的 sourceBlockIds / 页码（旧链路本身没有这些信息）。
     *
     * @param maxChars 段最大字符数（旧链路配置默认 800）
     * @param overlap  重叠字符数（默认 120）
     */
    public static List<RetrievalUnit> buildLegacyCorpus(AcademicDocument document, int maxChars, int overlap) {
        List<RetrievalUnit> units = new ArrayList<>();
        if (document == null) {
            return units;
        }
        StringBuilder fullText = new StringBuilder();
        List<Span> spans = new ArrayList<>();
        for (Section section : flatten(document.getSections())) {
            for (DocumentBlock block : section.getBlocks()) {
                String blockText = blockText(block);
                if (blockText == null || blockText.isBlank()) {
                    continue;
                }
                int start = fullText.length();
                fullText.append(blockText).append("\n\n");
                spans.add(new Span(start, fullText.length(),
                        BlockProvenance.sourceBlockIds(block), blockPages(block)));
            }
        }
        if (fullText.isEmpty()) {
            return units;
        }

        List<TextSegment> segments = DocumentSplitters.recursive(maxChars, overlap)
                .split(Document.from(fullText.toString()));

        String text = fullText.toString();
        int cursor = 0;
        for (int i = 0; i < segments.size(); i++) {
            String segmentText = segments.get(i).text();
            int start = text.indexOf(segmentText, cursor);
            if (start < 0) {
                start = cursor;
            }
            int end = start + segmentText.length();
            cursor = start;

            LinkedHashSet<String> ids = new LinkedHashSet<>();
            LinkedHashSet<Integer> pages = new LinkedHashSet<>();
            for (Span span : spans) {
                if (span.start() < end && span.end() > start) {
                    ids.addAll(span.sourceBlockIds());
                    pages.addAll(span.pages());
                }
            }
            units.add(RetrievalUnit.of("legacy:" + i, segmentText, "TEXT",
                    new ArrayList<>(ids), List.of(), new ArrayList<>(pages)));
        }
        return units;
    }

    // ==================== 运行 ====================

    /**
     * 对所有 gold 用例求 topK 排名并计算指标（skill §19）。
     */
    public static Experiment run(String name, List<RetrievalUnit> corpus,
            List<RetrievalGoldCase> gold, EmbeddingGateway gateway, int topK) {
        List<String> texts = corpus.stream().map(RetrievalUnit::text).toList();
        List<float[]> vectors = gateway.embedDocuments(texts);

        List<List<RetrievalUnit>> rankedPerCase = new ArrayList<>(gold.size());
        for (RetrievalGoldCase goldCase : gold) {
            float[] queryVector = gateway.embedQuery(goldCase.question());
            rankedPerCase.add(ExactCosineRetriever.topK(corpus, vectors, queryVector, topK));
        }
        return new Experiment(name, corpus.size(), RetrievalMetrics.compute(gold, rankedPerCase));
    }

    // ==================== 内部工具 ====================

    private record Span(int start, int end, List<String> sourceBlockIds, List<Integer> pages) {
    }

    private static List<Section> flatten(List<Section> sections) {
        List<Section> all = new ArrayList<>();
        collect(sections, all);
        return all;
    }

    private static void collect(List<Section> sections, List<Section> accumulator) {
        if (sections == null) {
            return;
        }
        for (Section section : sections) {
            if (section == null) {
                continue;
            }
            accumulator.add(section);
            collect(section.getChildren(), accumulator);
        }
    }

    private static String blockText(DocumentBlock block) {
        if (block instanceof ParagraphBlock paragraph) {
            return paragraph.getText();
        }
        if (block instanceof ListBlock list) {
            return list.getItems() == null ? null : String.join("\n", list.getItems());
        }
        if (block instanceof TableBlock table) {
            return table.getContent();
        }
        if (block instanceof FigureBlock figure) {
            return figure.getCaption();
        }
        return null;
    }

    private static List<Integer> blockPages(DocumentBlock block) {
        PageRange range = block.getPageRange();
        return range == null ? List.of() : pageList(range.getStartPage(), range.getEndPage());
    }

    private static List<Integer> pageList(Integer startPage, Integer endPage) {
        if (startPage == null) {
            return List.of();
        }
        int start = startPage;
        int end = endPage == null ? startPage : Math.max(startPage, endPage);
        if (end - start > 50) {
            end = start;
        }
        List<Integer> pages = new ArrayList<>();
        for (int page = start; page <= end; page++) {
            pages.add(page);
        }
        return pages;
    }
}
