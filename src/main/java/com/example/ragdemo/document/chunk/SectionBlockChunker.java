package com.example.ragdemo.document.chunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ragdemo.config.RagProperties;
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
 * 单个 Section 的直属 block 列表 → chunk drafts（skill §9~§15）。
 * <p>
 * 本类是该阶段唯一持有"段落缓冲"状态的地方，因此也负责 MIXED 判定与 flush 时机：
 * <ul>
 *   <li>连续 PARAGRAPH 按 targetTokens 累积为 TEXT chunk（不拆自然段）</li>
 *   <li>任何结构块（LIST/FIGURE/TABLE/FORMULA/CAPTION）出现前必须先 flush 段落缓冲，
 *       保证 TEXT chunk 不会横跨图表</li>
 *   <li>MIXED 判定（v1 严格）：待 flush 缓冲中<b>恰好一个</b> paragraph，
 *       且其 token ≤ 40、与紧随结构块合计 ≤ maxTokens 时，合并为 MIXED（至多 2 种 blockType）</li>
 *   <li>NOISE 不产出 chunk，仅计数（由 Reporter 告警）</li>
 * </ul>
 */
@Component
public class SectionBlockChunker {

    /** MIXED 判定中"短前置说明段落"的最大 token 数 */
    private static final int MIXED_LEAD_IN_MAX_TOKENS = 40;

    /** 段落拼接分隔符（overlap 依赖它定位"最后一段"） */
    static final String PARAGRAPH_SEPARATOR = "\n\n";

    private final ChunkTokenEstimator estimator;
    private final ChunkTextSplitter textSplitter;
    private final ListChunker listChunker;
    private final FigureTableChunker figureTableChunker;
    private final RagProperties properties;

    public SectionBlockChunker(ChunkTokenEstimator estimator, ChunkTextSplitter textSplitter,
            ListChunker listChunker, FigureTableChunker figureTableChunker, RagProperties properties) {
        this.estimator = estimator;
        this.textSplitter = textSplitter;
        this.listChunker = listChunker;
        this.figureTableChunker = figureTableChunker;
        this.properties = properties;
    }

    /**
     * 处理一个 Section 的直属 block（不含子 Section，子 Section 由遍历器递归处理）。
     */
    public List<ChunkDraft> chunkSection(List<DocumentBlock> blocks, ChunkStatsAccumulator stats) {
        List<ChunkDraft> chunks = new ArrayList<>();
        List<DocumentBlock> buffer = new ArrayList<>();
        int bufferTokens = 0;
        int target = properties.getChunker().getTargetTokens();
        int max = properties.getChunker().getMaxTokens();

        for (DocumentBlock block : blocks) {
            if (block == null) {
                continue;
            }

            if (block instanceof NoiseBlock) {
                // Cleaner 之后理论不应出现：不产出 chunk，只计数（Reporter 会告警）
                stats.addNoiseBlock();
                continue;
            }

            if (block instanceof ParagraphBlock paragraph) {
                if (paragraph.getText() == null || paragraph.getText().isBlank()) {
                    continue;
                }
                int tokens = estimator.estimate(paragraph.getText());
                if (tokens > max) {
                    // 超长段落：先 flush，再句子级降级切分
                    flush(chunks, buffer);
                    buffer = new ArrayList<>();
                    bufferTokens = 0;
                    stats.addSplitParagraph();
                    for (String part : textSplitter.splitAndPack(paragraph.getText(), target, max)) {
                        chunks.add(ChunkDraft.of(ChunkType.TEXT, part, List.of(BlockKind.PARAGRAPH),
                                List.of((DocumentBlock) paragraph)));
                    }
                    continue;
                }
                if (buffer.isEmpty()) {
                    buffer.add(paragraph);
                    bufferTokens = tokens;
                } else if (bufferTokens + tokens <= target) {
                    buffer.add(paragraph);
                    bufferTokens += tokens;
                } else {
                    flush(chunks, buffer);
                    buffer = new ArrayList<>();
                    buffer.add(paragraph);
                    bufferTokens = tokens;
                }
                continue;
            }

            if (block instanceof FormulaBlock formula) {
                flush(chunks, buffer);
                buffer = new ArrayList<>();
                bufferTokens = 0;
                if (formula.getFormula() != null && !formula.getFormula().isBlank()) {
                    String content = ChunkTextUtils.joinNonBlank(formula.getLabel(), formula.getFormula());
                    chunks.add(ChunkDraft.of(ChunkType.FORMULA, content, List.of(BlockKind.FORMULA),
                            List.of((DocumentBlock) formula)));
                }
                continue;
            }

            if (block instanceof CaptionBlock caption) {
                // orphan caption：不丢弃、不在 Chunker 内做图文绑定（属 Cleaner 职责）
                flush(chunks, buffer);
                buffer = new ArrayList<>();
                bufferTokens = 0;
                if (caption.getText() != null && !caption.getText().isBlank()) {
                    chunks.add(ChunkDraft.of(ChunkType.CAPTION, caption.getText().trim(),
                            List.of(BlockKind.CAPTION), List.of((DocumentBlock) caption)));
                }
                continue;
            }

            if (block instanceof FootnoteBlock footnote) {
                if (footnote.getText() == null || footnote.getText().isBlank()) {
                    continue;
                }
                int footnoteTokens = estimator.estimate(footnote.getText());
                // ① 优先并入当前段落缓冲
                if (!buffer.isEmpty()) {
                    String merged = joinBuffer(buffer) + PARAGRAPH_SEPARATOR + footnote.getText().trim();
                    if (estimator.estimate(merged) <= max) {
                        buffer.add(footnote);
                        bufferTokens += footnoteTokens;
                        continue;
                    }
                }
                // ② 其次并入本 Section 上一个 TEXT chunk
                int lastText = lastTextChunkIndex(chunks);
                if (lastText >= 0) {
                    ChunkDraft last = chunks.get(lastText);
                    String merged = last.content() + PARAGRAPH_SEPARATOR + footnote.getText().trim();
                    if (estimator.estimate(merged) <= max) {
                        chunks.set(lastText, new ChunkDraft(ChunkType.TEXT, merged,
                                appendKind(last.blockTypes(), BlockKind.FOOTNOTE),
                                appendBlock(last.sourceBlocks(), footnote),
                                last.referenceIds()));
                        continue;
                    }
                }
                // ③ 兜底：独立 FOOTNOTE chunk（保留类型便于后续降权）
                chunks.add(ChunkDraft.of(ChunkType.FOOTNOTE, footnote.getText().trim(),
                        List.of(BlockKind.FOOTNOTE), List.of((DocumentBlock) footnote)));
                continue;
            }

            if (block instanceof ListBlock || block instanceof FigureBlock || block instanceof TableBlock) {
                List<ChunkDraft> structured = structuredDrafts(block, stats);
                if (structured.isEmpty()) {
                    // 例如空 caption 的 Figure：不产出 chunk，仅计数
                    flush(chunks, buffer);
                    buffer = new ArrayList<>();
                    bufferTokens = 0;
                    continue;
                }
                if (buffer.size() == 1 && bufferTokens <= MIXED_LEAD_IN_MAX_TOKENS
                        && bufferTokens + estimator.estimate(structured.get(0).content()) <= max) {
                    chunks.add(mergeLeadIn(buffer.get(0), structured.get(0)));
                    chunks.addAll(structured.subList(1, structured.size()));
                } else {
                    flush(chunks, buffer);
                    chunks.addAll(structured);
                }
                buffer = new ArrayList<>();
                bufferTokens = 0;
                continue;
            }

            // sealed 集之外的未知类型：保守 flush，不猜测语义
            flush(chunks, buffer);
            buffer = new ArrayList<>();
            bufferTokens = 0;
        }

        flush(chunks, buffer);
        return chunks;
    }

    // ==================== 内部工具 ====================

    private void flush(List<ChunkDraft> chunks, List<DocumentBlock> buffer) {
        if (buffer == null || buffer.isEmpty()) {
            return;
        }
        String content = joinBuffer(buffer);
        if (content.isBlank()) {
            return;
        }
        List<BlockKind> kinds = new ArrayList<>();
        for (DocumentBlock block : buffer) {
            kinds.add(BlockKind.from(block));
        }
        chunks.add(ChunkDraft.of(ChunkType.TEXT, content, dedupKinds(kinds),
                new ArrayList<>(buffer)));
    }

    /** 段落缓冲 → 文本（用 PARAGRAPH_SEPARATOR 连接，便于 overlap 定位最后一段）。 */
    private String joinBuffer(List<DocumentBlock> buffer) {
        StringBuilder builder = new StringBuilder();
        for (DocumentBlock block : buffer) {
            String text = textOf(block);
            if (text == null || text.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(PARAGRAPH_SEPARATOR);
            }
            builder.append(text.trim());
        }
        return builder.toString();
    }

    private String textOf(DocumentBlock block) {
        if (block instanceof ParagraphBlock paragraph) {
            return paragraph.getText();
        }
        if (block instanceof FootnoteBlock footnote) {
            return footnote.getText();
        }
        return null;
    }

    private List<ChunkDraft> structuredDrafts(DocumentBlock block, ChunkStatsAccumulator stats) {
        if (block instanceof ListBlock list) {
            return listChunker.chunk(list);
        }
        if (block instanceof FigureBlock figure) {
            return figureTableChunker.chunkFigure(figure, stats);
        }
        if (block instanceof TableBlock table) {
            return figureTableChunker.chunkTable(table, stats);
        }
        return List.of();
    }

    /** MIXED：短前置说明段落 + 结构块（至多 2 种 blockType）。 */
    private ChunkDraft mergeLeadIn(DocumentBlock leadIn, ChunkDraft structured) {
        List<BlockKind> kinds = new ArrayList<>();
        kinds.add(BlockKind.PARAGRAPH);
        kinds.addAll(structured.blockTypes());
        List<DocumentBlock> blocks = new ArrayList<>();
        blocks.add(leadIn);
        blocks.addAll(structured.sourceBlocks());
        String content = textOf(leadIn).trim() + PARAGRAPH_SEPARATOR + structured.content();
        return new ChunkDraft(ChunkType.MIXED, content, dedupKinds(kinds), blocks,
                structured.referenceIds());
    }

    private int lastTextChunkIndex(List<ChunkDraft> chunks) {
        for (int i = chunks.size() - 1; i >= 0; i--) {
            if (chunks.get(i).chunkType() == ChunkType.TEXT) {
                return i;
            }
        }
        return -1;
    }

    private List<BlockKind> appendKind(List<BlockKind> kinds, BlockKind extra) {
        List<BlockKind> merged = new ArrayList<>(kinds);
        merged.add(extra);
        return dedupKinds(merged);
    }

    private List<DocumentBlock> appendBlock(List<DocumentBlock> blocks, DocumentBlock extra) {
        List<DocumentBlock> merged = new ArrayList<>(blocks);
        merged.add(extra);
        return merged;
    }

    private List<BlockKind> dedupKinds(List<BlockKind> kinds) {
        return new ArrayList<>(new LinkedHashSet<>(kinds));
    }
}
