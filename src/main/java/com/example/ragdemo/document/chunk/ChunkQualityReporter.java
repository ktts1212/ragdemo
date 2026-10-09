package com.example.ragdemo.document.chunk;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;

import lombok.extern.slf4j.Slf4j;

/**
 * Chunk QA 度量与告警（skill §25/§26）。
 * <p>
 * 本类是唯一计算 {@link ChunkQualityMetrics} 的地方；{@link SectionAwareChunker} 只负责累加执行期计数。
 * <p>
 * 关键不变量（理想值）：
 * {@code emptyChunkCount == 0}、{@code noiseChunkCount == 0}、{@code crossSectionChunkCount == 0}。
 */
@Slf4j
@Component
public class ChunkQualityReporter {

    /** 参考文献 chunk 占比告警阈值（仅告警，不改变行为） */
    private static final double REFERENCE_RATIO_WARNING_THRESHOLD = 0.5;

    public ChunkQualityMetrics report(AcademicDocument document, List<AcademicChunk> chunks,
            List<String> warnings, ChunkBuildStats stats) {
        List<AcademicChunk> safeChunks = chunks == null ? List.of() : chunks;
        int chunkCount = safeChunks.size();

        // ===== token 分布 =====
        List<Integer> tokenList = new ArrayList<>();
        for (AcademicChunk chunk : safeChunks) {
            tokenList.add(chunk.getTokenCount());
        }
        tokenList.sort(Integer::compareTo);
        double avgTokens = tokenList.isEmpty() ? 0.0
                : tokenList.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        int p50 = percentile(tokenList, 50);
        int p90 = percentile(tokenList, 90);
        int maxTokens = tokenList.isEmpty() ? 0 : tokenList.get(tokenList.size() - 1);
        int minTokens = tokenList.isEmpty() ? 0 : tokenList.get(0);

        // ===== 分类 / 异常计数 =====
        Map<ChunkType, Integer> typeCounts = new EnumMap<>(ChunkType.class);
        int oversized = 0;
        int undersized = 0;
        int empty = 0;
        int noise = 0;
        int overlapChunks = 0;
        for (AcademicChunk chunk : safeChunks) {
            typeCounts.merge(chunk.getChunkType(), 1, Integer::sum);
            if (chunk.isOversized()) {
                oversized++;
            }
            if (chunk.isUndersized()) {
                undersized++;
            }
            if (chunk.getContent() == null || chunk.getContent().isBlank()) {
                empty++;
            }
            if (chunk.getBlockTypes() != null && chunk.getBlockTypes().contains(BlockKind.NOISE)) {
                noise++;
            }
            if (chunk.getOverlapBlockIds() != null && !chunk.getOverlapBlockIds().isEmpty()) {
                overlapChunks++;
            }
        }

        // ===== blockId → sectionId 映射（用于跨 Section 检测）=====
        List<Section> allSections = new ArrayList<>();
        Map<String, String> blockToSection = new HashMap<>();
        collectSections(document == null ? null : document.getSections(), allSections, blockToSection);

        int crossSection = 0;
        for (AcademicChunk chunk : safeChunks) {
            if (chunk.getSectionId() == null) {
                continue;
            }
            if (hasForeignBlock(chunk, blockToSection)) {
                crossSection++;
            }
        }

        // ===== 重复 provenance（overlap 或错误合并都会体现）=====
        Map<String, Integer> idOccurrences = new HashMap<>();
        for (AcademicChunk chunk : safeChunks) {
            for (String id : chunkBlockIds(chunk)) {
                idOccurrences.merge(id, 1, Integer::sum);
            }
        }
        int duplicated = (int) idOccurrences.values().stream().filter(v -> v > 1).count();

        // ===== section 覆盖率：分母 = 有直属 block 的 section；分子 = 产出了 chunk 的 section =====
        Set<String> sectionsWithBlocks = new LinkedHashSet<>();
        for (Section section : allSections) {
            if (section.getId() != null && !section.getId().isBlank()
                    && section.getBlocks() != null && !section.getBlocks().isEmpty()) {
                sectionsWithBlocks.add(section.getId());
            }
        }
        Set<String> sectionsWithChunks = new LinkedHashSet<>();
        for (AcademicChunk chunk : safeChunks) {
            if (chunk.getSectionId() != null) {
                sectionsWithChunks.add(chunk.getSectionId());
            }
        }
        long covered = sectionsWithChunks.stream().filter(sectionsWithBlocks::contains).count();
        double sectionCoverage = sectionsWithBlocks.isEmpty()
                ? 0.0
                : (double) covered / sectionsWithBlocks.size();

        int referenceCount = typeCounts.getOrDefault(ChunkType.REFERENCE, 0);
        double referenceRatio = chunkCount == 0 ? 0.0 : (double) referenceCount / chunkCount;

        // ===== QA warnings（只告警，不改变行为）=====
        if (stats.noiseBlockCount() > 0) {
            warnings.add("NOISE_BLOCK_REACHED_CHUNKER: " + stats.noiseBlockCount());
        }
        if (empty > 0) {
            warnings.add("emptyChunkCount > 0: " + empty);
        }
        if (noise > 0) {
            warnings.add("noiseChunkCount > 0: " + noise);
        }
        if (crossSection > 0) {
            warnings.add("crossSectionChunkCount > 0: " + crossSection);
        }
        if (referenceRatio > REFERENCE_RATIO_WARNING_THRESHOLD) {
            warnings.add(String.format(
                    "referenceChunkRatio 偏高（参考文献 chunk 占比 %.2f，检索时建议过滤/降权）", referenceRatio));
        }
        if (stats.skippedFigureCount() > 0) {
            warnings.add("存在无 caption / 无 descriptionText 的 Figure，已跳过文本 chunk: "
                    + stats.skippedFigureCount());
        }

        ChunkQualityMetrics metrics = new ChunkQualityMetrics(
                chunkCount,
                avgTokens,
                p50,
                p90,
                maxTokens,
                minTokens,
                oversized,
                undersized,
                empty,
                noise,
                crossSection,
                stats.splitParagraphCount(),
                typeCounts.getOrDefault(ChunkType.TEXT, 0),
                typeCounts.getOrDefault(ChunkType.LIST, 0),
                typeCounts.getOrDefault(ChunkType.FIGURE, 0),
                typeCounts.getOrDefault(ChunkType.TABLE, 0),
                typeCounts.getOrDefault(ChunkType.FORMULA, 0),
                typeCounts.getOrDefault(ChunkType.FOOTNOTE, 0),
                typeCounts.getOrDefault(ChunkType.CAPTION, 0),
                typeCounts.getOrDefault(ChunkType.MIXED, 0),
                typeCounts.getOrDefault(ChunkType.ABSTRACT, 0),
                referenceCount,
                stats.skippedFigureCount(),
                stats.skippedTableCount(),
                stats.skippedReferenceCount(),
                overlapChunks,
                duplicated,
                referenceRatio,
                sectionCoverage);

        log.info("Chunk QA: chunkCount={}, avgTokens={}, p50={}, p90={}, max={}, min={} | "
                        + "oversized={}, undersized={}, empty={}, noise={}, crossSection={}, splitParagraph={} | "
                        + "text={}, list={}, figure={}, table={}, formula={}, footnote={}, caption={}, mixed={}, "
                        + "abstract={}, reference={} | skippedFigure={}, skippedTable={}, skippedReference={} | "
                        + "overlapChunks={}, duplicatedBlocks={}, referenceRatio={}, sectionCoverage={}",
                chunkCount, String.format("%.1f", avgTokens), p50, p90, maxTokens, minTokens,
                oversized, undersized, empty, noise, crossSection, stats.splitParagraphCount(),
                typeCounts.getOrDefault(ChunkType.TEXT, 0),
                typeCounts.getOrDefault(ChunkType.LIST, 0),
                typeCounts.getOrDefault(ChunkType.FIGURE, 0),
                typeCounts.getOrDefault(ChunkType.TABLE, 0),
                typeCounts.getOrDefault(ChunkType.FORMULA, 0),
                typeCounts.getOrDefault(ChunkType.FOOTNOTE, 0),
                typeCounts.getOrDefault(ChunkType.CAPTION, 0),
                typeCounts.getOrDefault(ChunkType.MIXED, 0),
                typeCounts.getOrDefault(ChunkType.ABSTRACT, 0),
                referenceCount,
                stats.skippedFigureCount(), stats.skippedTableCount(), stats.skippedReferenceCount(),
                overlapChunks, duplicated,
                String.format("%.2f", referenceRatio), String.format("%.2f", sectionCoverage));

        return metrics;
    }

    // ==================== 内部工具 ====================

    /** nearest-rank 百分位（不插值，保证结果稳定）。 */
    private int percentile(List<Integer> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int size = sorted.size();
        int index = (int) Math.ceil(percentile / 100.0 * size) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= size) {
            index = size - 1;
        }
        return sorted.get(index);
    }

    private void collectSections(List<Section> sections, List<Section> accumulator,
            Map<String, String> blockToSection) {
        if (sections == null) {
            return;
        }
        for (Section section : sections) {
            if (section == null) {
                continue;
            }
            accumulator.add(section);
            if (section.getBlocks() != null) {
                for (DocumentBlock block : section.getBlocks()) {
                    if (block == null) {
                        continue;
                    }
                    if (block.getId() != null && !block.getId().isBlank()) {
                        blockToSection.putIfAbsent(block.getId(), section.getId());
                    }
                    // 跨页合并段的历史 block id 也要登记，避免被误判为跨 Section
                    for (String id : BlockProvenance.sourceBlockIds(block)) {
                        blockToSection.putIfAbsent(id, section.getId());
                    }
                }
            }
            collectSections(section.getChildren(), accumulator, blockToSection);
        }
    }

    private boolean hasForeignBlock(AcademicChunk chunk, Map<String, String> blockToSection) {
        for (String id : chunkBlockIds(chunk)) {
            String owner = blockToSection.get(id);
            if (owner != null && !owner.equals(chunk.getSectionId())) {
                return true;
            }
        }
        return false;
    }

    private Set<String> chunkBlockIds(AcademicChunk chunk) {
        Set<String> ids = new LinkedHashSet<>();
        if (chunk.getSourceBlockIds() != null) {
            for (String id : chunk.getSourceBlockIds()) {
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        if (chunk.getOverlapBlockIds() != null) {
            for (String id : chunk.getOverlapBlockIds()) {
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }
}
