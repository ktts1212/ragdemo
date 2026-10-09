package com.example.ragdemo.document.chunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.ragdemo.document.domain.PageRange;
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
 * block provenance 归一化工具。
 * <p>
 * 领域模型中 provenance 字段并不统一：
 * {@link ParagraphBlock} / {@link FigureBlock} / {@link TableBlock} 提供复数 {@code sourceBlockIds}，
 * 而 {@link ListBlock} / {@link FormulaBlock} / {@link FootnoteBlock} / {@link CaptionBlock} / {@link NoiseBlock}
 * 只提供单数 {@code sourceBlockId}。
 * <p>
 * 本类把两者统一为"有序去重的 id 列表"，使 chunk 的 {@code sourceBlockIds = union} 得以实现，
 * 且<b>无需修改领域模型</b>（避免波及 20+ 既有调用点）。
 */
public final class BlockProvenance {

    private BlockProvenance() {
    }

    /**
     * 归一化单个 block 的来源 id 列表。
     * <p>
     * 优先级：复数 {@code sourceBlockIds} → 单数 {@code sourceBlockId} → {@code id}；
     * 结果有序去重（null / 空白值被丢弃）。
     */
    public static List<String> sourceBlockIds(DocumentBlock block) {
        if (block == null) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        if (block instanceof ParagraphBlock paragraph) {
            addAll(candidates, paragraph.getSourceBlockIds());
            add(candidates, paragraph.getSourceBlockId());
        } else if (block instanceof FigureBlock figure) {
            addAll(candidates, figure.getSourceBlockIds());
        } else if (block instanceof TableBlock table) {
            addAll(candidates, table.getSourceBlockIds());
        } else if (block instanceof ListBlock list) {
            add(candidates, list.getSourceBlockId());
        } else if (block instanceof FormulaBlock formula) {
            add(candidates, formula.getSourceBlockId());
        } else if (block instanceof FootnoteBlock footnote) {
            add(candidates, footnote.getSourceBlockId());
        } else if (block instanceof CaptionBlock caption) {
            add(candidates, caption.getSourceBlockId());
        } else if (block instanceof NoiseBlock noise) {
            add(candidates, noise.getSourceBlockId());
        }
        add(candidates, block.getId());
        return dedup(candidates);
    }

    /** 多个 block 的 provenance 并集（有序去重）。 */
    public static List<String> union(List<DocumentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<String> all = new ArrayList<>();
        for (DocumentBlock block : blocks) {
            all.addAll(sourceBlockIds(block));
        }
        return dedup(all);
    }

    /** 合并后 chunk 的起始页 = 所有非空 startPage 的最小值；全为空时返回 null。 */
    public static Integer startPage(List<DocumentBlock> blocks) {
        Integer min = null;
        for (PageRange range : pageRanges(blocks)) {
            if (range.getStartPage() != null && (min == null || range.getStartPage() < min)) {
                min = range.getStartPage();
            }
        }
        return min;
    }

    /** 合并后 chunk 的结束页 = 所有非空 endPage 的最大值；全为空时返回 null。 */
    public static Integer endPage(List<DocumentBlock> blocks) {
        Integer max = null;
        for (PageRange range : pageRanges(blocks)) {
            if (range.getEndPage() != null && (max == null || range.getEndPage() > max)) {
                max = range.getEndPage();
            }
        }
        return max;
    }

    private static List<PageRange> pageRanges(List<DocumentBlock> blocks) {
        List<PageRange> ranges = new ArrayList<>();
        if (blocks == null) {
            return ranges;
        }
        for (DocumentBlock block : blocks) {
            if (block != null && block.getPageRange() != null) {
                ranges.add(block.getPageRange());
            }
        }
        return ranges;
    }

    private static void add(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    private static void addAll(List<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            add(target, value);
        }
    }

    private static List<String> dedup(List<String> values) {
        Set<String> seen = new LinkedHashSet<>(values);
        return new ArrayList<>(seen);
    }
}
