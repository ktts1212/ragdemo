package com.example.ragdemo.document.chunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;

import lombok.extern.slf4j.Slf4j;

/**
 * Section 结构感知分块器（skill §3/§7/§22/§23/§24）。
 * <p>
 * 核心原则：
 * <pre>
 * Section 决定语义边界 → BlockType 决定块内策略 → Paragraph 决定自然文本边界 → Token 预算决定何时切分
 * </pre>
 * 不变量：
 * <ul>
 *   <li>不跨 Section 合并（{@code chunk.sectionPath} 永远等于内容实际所属 Section 的 path）</li>
 *   <li>不产空 chunk（content 为空白一律不产出）</li>
 *   <li>NOISE block 不进入 chunk，只计数 + 告警</li>
 *   <li>chunkId 稳定可复现：{@code documentId:scopeId:localIndex}</li>
 * </ul>
 * 输出顺序：ABSTRACT → sections（DFS preorder）→ references。
 */
@Slf4j
@Service
public class SectionAwareChunker {

    private static final String ABSTRACT_SCOPE = "__abstract__";
    private static final String REFERENCE_SCOPE = "__references__";

    private final ChunkTokenEstimator estimator;
    private final ChunkTextSplitter textSplitter;
    private final ChunkTextRenderer textRenderer;
    private final SectionBlockChunker sectionBlockChunker;
    private final DocumentLevelChunker documentLevelChunker;
    private final ChunkQualityReporter qualityReporter;
    private final RagProperties properties;

    public SectionAwareChunker(ChunkTokenEstimator estimator, ChunkTextSplitter textSplitter,
            ChunkTextRenderer textRenderer, SectionBlockChunker sectionBlockChunker,
            DocumentLevelChunker documentLevelChunker, ChunkQualityReporter qualityReporter,
            RagProperties properties) {
        this.estimator = estimator;
        this.textSplitter = textSplitter;
        this.textRenderer = textRenderer;
        this.sectionBlockChunker = sectionBlockChunker;
        this.documentLevelChunker = documentLevelChunker;
        this.qualityReporter = qualityReporter;
        this.properties = properties;
    }

    /**
     * 把 Canonical {@link AcademicDocument} 切成 section-aware chunk。
     *
     * @param document 已清洗的文档
     * @return chunk 列表 + warnings + QA 度量
     */
    public ChunkingResult chunk(AcademicDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document 不能为 null");
        }
        long start = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();
        String documentId = document.getDocumentId();
        if (documentId == null || documentId.isBlank()) {
            warnings.add("documentId 为空");
        }
        String title = document.getMetadata() == null ? null : document.getMetadata().getTitle();

        List<AcademicChunk> chunks = new ArrayList<>();
        ChunkStatsAccumulator stats = new ChunkStatsAccumulator();
        int[] order = {0};
        int[] unnamedSectionCounter = {0};
        // 已使用的 chunkId scope：chunkId 是下游入库的 upsert 主键，绝不允许冲突
        Set<String> usedScopeIds = new LinkedHashSet<>();

        // 1) ABSTRACT（高价值检索入口，单独成 chunk）
        ChunkScope abstractScope = new ChunkScope(
                uniqueScopeId(ABSTRACT_SCOPE, usedScopeIds, warnings), null, null, List.of(), null);
        for (ChunkDraft draft : documentLevelChunker.chunkAbstract(document)) {
            chunks.add(finalize(draft, documentId, title, abstractScope, order[0]++));
        }

        // 2) 正文 sections：按文档自然顺序 DFS（preorder）
        traverseSections(document.getSections(), documentId, title, chunks, stats, order,
                unnamedSectionCounter, usedScopeIds, warnings);

        // 3) references：独立于正文 Section
        ChunkScope referenceScope = new ChunkScope(
                uniqueScopeId(REFERENCE_SCOPE, usedScopeIds, warnings), null, null, List.of(), null);
        for (ChunkDraft draft : documentLevelChunker.chunkReferences(document, stats)) {
            chunks.add(finalize(draft, documentId, title, referenceScope, order[0]++));
        }

        // 4) QA 度量 + 告警（先 QA，后 embedding —— skill §25）
        ChunkQualityMetrics metrics = qualityReporter.report(document, chunks, warnings, stats.toStats());
        log.info("SectionAwareChunker 完成: documentId={}, chunkCount={}, warningCount={}, durationMs={}ms",
                documentId, chunks.size(), warnings.size(), System.currentTimeMillis() - start);
        return new ChunkingResult(documentId, chunks, warnings, metrics);
    }

    // ==================== Section 遍历 ====================

    private void traverseSections(List<Section> sections, String documentId, String title,
            List<AcademicChunk> chunks, ChunkStatsAccumulator stats, int[] order,
            int[] unnamedSectionCounter, Set<String> usedScopeIds, List<String> warnings) {
        if (sections == null) {
            return;
        }
        for (Section section : sections) {
            if (section == null) {
                continue;
            }
            List<DocumentBlock> blocks = section.getBlocks() == null ? List.of() : section.getBlocks();
            if (!blocks.isEmpty()) {
                String candidate = ChunkTextUtils.firstNonBlank(section.getId(), section.getSourceBlockId());
                if (candidate == null) {
                    // 无 id 的 section：用遍历序保证确定性（不引入随机量）
                    candidate = "section-" + unnamedSectionCounter[0]++;
                }
                ChunkScope scope = new ChunkScope(
                        uniqueScopeId(candidate, usedScopeIds, warnings), section.getId(),
                        section.getTitle(), resolveSectionPath(section), section.getLevel());
                List<AcademicChunk> sectionChunks = new ArrayList<>();
                for (ChunkDraft draft : sectionBlockChunker.chunkSection(blocks, stats)) {
                    sectionChunks.add(finalize(draft, documentId, title, scope, order[0]++));
                }
                applyOverlap(sectionChunks);
                chunks.addAll(sectionChunks);
            }
            // 纯容器 section（无直属 block）本身不产 chunk，但其子节点仍要被处理
            traverseSections(section.getChildren(), documentId, title, chunks, stats, order,
                    unnamedSectionCounter, usedScopeIds, warnings);
        }
    }

    /**
     * 计算全局唯一的 chunkId scope。
     * <p>
     * 领域模型未强制 Section.id 唯一（也未强制非空），而 {@code chunkId} 是下游入库的 upsert 主键：
     * 一旦两个 scope 相同，后写入的 chunk 会静默覆盖前一个。这里用"已用集合 + 确定性后缀"兜底并告警。
     */
    private String uniqueScopeId(String candidate, Set<String> usedScopeIds, List<String> warnings) {
        if (usedScopeIds.add(candidate)) {
            return candidate;
        }
        int suffix = 2;
        String unique = candidate + "#" + suffix;
        while (!usedScopeIds.add(unique)) {
            suffix++;
            unique = candidate + "#" + suffix;
        }
        warnings.add("duplicate sectionId，chunkId 已加后缀避免冲突: " + candidate + " -> " + unique);
        return unique;
    }

    /** sectionPath：过滤空白段；为空时退化为自身标题。 */
    private List<String> resolveSectionPath(Section section) {
        List<String> path = new ArrayList<>();
        if (section.getPath() != null) {
            for (String segment : section.getPath()) {
                if (segment != null && !segment.isBlank()) {
                    path.add(segment.trim());
                }
            }
        }
        if (path.isEmpty() && section.getTitle() != null && !section.getTitle().isBlank()) {
            path.add(section.getTitle().trim());
        }
        return path;
    }

    // ==================== 定稿 ====================

    private AcademicChunk finalize(ChunkDraft draft, String documentId, String title,
            ChunkScope scope, int order) {
        String content = draft.content() == null ? "" : draft.content().trim();
        String embeddingText = textRenderer.renderEmbeddingText(title, scope.sectionPath(), content);
        int tokenCount = estimator.estimate(content);
        int maxTokens = properties.getChunker().getMaxTokens();
        int minTokens = properties.getChunker().getMinTokens();

        return AcademicChunk.builder()
                .chunkId(documentId + ":" + scope.scopeId() + ":" + scope.nextIndex())
                .documentId(documentId)
                .chunkType(draft.chunkType())
                .sectionId(scope.sectionId())
                .sectionTitle(scope.sectionTitle())
                .sectionPath(new ArrayList<>(scope.sectionPath()))
                .sectionLevel(scope.sectionLevel())
                .content(content)
                .embeddingText(embeddingText)
                .blockTypes(new ArrayList<>(draft.blockTypes()))
                .sourceBlockIds(new ArrayList<>(BlockProvenance.union(draft.sourceBlocks())))
                .overlapBlockIds(new ArrayList<>())
                .startPage(BlockProvenance.startPage(draft.sourceBlocks()))
                .endPage(BlockProvenance.endPage(draft.sourceBlocks()))
                .tokenCount(tokenCount)
                .charCount(content.length())
                .order(order)
                .oversized(tokenCount > maxTokens)
                .undersized(tokenCount < minTokens)
                .contentHash(hash(embeddingText))
                .titleContext(title)
                .referenceIds(new ArrayList<>(draft.referenceIds()))
                .build();
    }

    // ==================== Overlap（v1 默认关闭，仅 TEXT）====================

    /**
     * 同一 Section 内相邻 TEXT chunk 的 block 级 overlap：把上一个 chunk 的
     * <b>最后一个完整段落</b>复制到下一个 chunk 头部；段落过长时退化为句级尾部。
     * 仅当 {@code rag.chunker.overlap-enabled=true} 时生效。
     */
    private void applyOverlap(List<AcademicChunk> sectionChunks) {
        if (!properties.getChunker().isOverlapEnabled() || sectionChunks.size() < 2) {
            return;
        }
        int overlapTokens = properties.getChunker().getOverlapTokens();
        int maxTokens = properties.getChunker().getMaxTokens();
        int minTokens = properties.getChunker().getMinTokens();

        for (int i = 1; i < sectionChunks.size(); i++) {
            AcademicChunk previous = sectionChunks.get(i - 1);
            AcademicChunk current = sectionChunks.get(i);
            if (previous.getChunkType() != ChunkType.TEXT || current.getChunkType() != ChunkType.TEXT) {
                continue;
            }
            String lastParagraph = lastParagraph(previous.getContent());
            if (lastParagraph == null || lastParagraph.isBlank()) {
                continue;
            }
            String overlap = lastParagraph;
            if (estimator.estimate(overlap) > overlapTokens) {
                overlap = trailingWithinBudget(lastParagraph, overlapTokens);
            }
            if (overlap == null || overlap.isBlank()) {
                continue;
            }
            String merged = overlap + SectionBlockChunker.PARAGRAPH_SEPARATOR + current.getContent();
            if (estimator.estimate(merged) > maxTokens) {
                // 加了 overlap 会超预算 → 取消本次 overlap（宁可无 overlap，也不超 max）
                continue;
            }
            current.setContent(merged);
            current.setEmbeddingText(textRenderer.renderEmbeddingText(
                    current.getTitleContext(), current.getSectionPath(), merged));
            current.setCharCount(merged.length());
            current.setTokenCount(estimator.estimate(merged));
            current.setContentHash(hash(current.getEmbeddingText()));
            current.setOversized(current.getTokenCount() > maxTokens);
            current.setUndersized(current.getTokenCount() < minTokens);
            current.getOverlapBlockIds().addAll(tailBlockIds(previous));
        }
    }

    private String lastParagraph(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        int index = content.lastIndexOf(SectionBlockChunker.PARAGRAPH_SEPARATOR);
        if (index < 0) {
            return content;
        }
        return content.substring(index + SectionBlockChunker.PARAGRAPH_SEPARATOR.length());
    }

    private String trailingWithinBudget(String text, int budgetTokens) {
        List<String> sentences = textSplitter.splitSentences(text);
        List<String> picked = new ArrayList<>();
        int tokens = 0;
        for (int i = sentences.size() - 1; i >= 0; i--) {
            int sentenceTokens = estimator.estimate(sentences.get(i));
            if (tokens + sentenceTokens > budgetTokens) {
                break;
            }
            picked.add(0, sentences.get(i));
            tokens += sentenceTokens;
        }
        return picked.isEmpty() ? null : String.join(" ", picked);
    }

    /**
     * 被 overlap 重复的块 id = 上一个 chunk 尾部对应"最后一段"的 id。
     * 近似规则：每个段落至少贡献 1 个 id，因此尾部 id 数 = 总 id 数 - (段落数 - 1)。
     * 文档中跨页合并段约占 6/89，属少数情况，该近似用于 QA 统计已足够。
     */
    private List<String> tailBlockIds(AcademicChunk previous) {
        List<String> ids = previous.getSourceBlockIds();
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        int paragraphCount = countParagraphs(previous.getContent());
        int tail = Math.max(1, ids.size() - (paragraphCount - 1));
        return new ArrayList<>(ids.subList(Math.max(0, ids.size() - tail), ids.size()));
    }

    private int countParagraphs(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        int count = 1;
        int index = content.indexOf(SectionBlockChunker.PARAGRAPH_SEPARATOR);
        while (index >= 0) {
            count++;
            index = content.indexOf(SectionBlockChunker.PARAGRAPH_SEPARATOR,
                    index + SectionBlockChunker.PARAGRAPH_SEPARATOR.length());
        }
        return count;
    }

    // ==================== 工具 ====================

    /** embeddingText 的 SHA-256 前 16 位（变更检测用，不参与 chunkId）。 */
    private String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** chunk 的 scope（决定 chunkId 前缀与 section 上下文）。 */
    private static final class ChunkScope {

        private final String scopeId;
        private final String sectionId;
        private final String sectionTitle;
        private final List<String> sectionPath;
        private final Integer sectionLevel;
        private int index;

        ChunkScope(String scopeId, String sectionId, String sectionTitle,
                List<String> sectionPath, Integer sectionLevel) {
            this.scopeId = scopeId;
            this.sectionId = sectionId;
            this.sectionTitle = sectionTitle;
            this.sectionPath = sectionPath == null ? List.of() : sectionPath;
            this.sectionLevel = sectionLevel;
        }

        String scopeId() {
            return scopeId;
        }

        String sectionId() {
            return sectionId;
        }

        String sectionTitle() {
            return sectionTitle;
        }

        List<String> sectionPath() {
            return sectionPath;
        }

        Integer sectionLevel() {
            return sectionLevel;
        }

        int nextIndex() {
            return index++;
        }
    }
}
