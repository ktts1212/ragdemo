package com.example.ragdemo.document.chunk;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.TableBlock;

/**
 * FIGURE / TABLE block → chunk draft（skill §11/§12）。
 * <p>
 * FIGURE：{@code label + caption + descriptionText}；三者全空则不产出 chunk（仅计数），
 * 避免生成"只有 imageKey 的空向量 chunk"；{@code imageKey} 永不进入文本。
 * <p>
 * TABLE：{@code caption(或 label) + 表格文本}；超长时按行切分，
 * 且<b>每一批都重复 caption + 表头行</b>，避免片段失去上下文；
 * {@code html} 只作为 metadata 保留，不进入 embedding。
 */
@Component
public class FigureTableChunker {

    private final ChunkTokenEstimator estimator;
    private final RagProperties properties;

    public FigureTableChunker(ChunkTokenEstimator estimator, RagProperties properties) {
        this.estimator = estimator;
        this.properties = properties;
    }

    public List<ChunkDraft> chunkFigure(FigureBlock figure, ChunkStatsAccumulator stats) {
        // 契约（skill §11.1）：caption 与 descriptionText 都为空时不产出文本 chunk。
        // 只带 label（如 "Figure 3"）的图无检索价值，同样跳过，避免产生无意义短 chunk。
        if (isBlank(figure.getCaption()) && isBlank(figure.getDescriptionText())) {
            stats.addSkippedFigure();
            return List.of();
        }
        String content = ChunkTextUtils.joinNonBlank(
                figure.getLabel(), figure.getCaption(), figure.getDescriptionText());
        if (content.isBlank()) {
            stats.addSkippedFigure();
            return List.of();
        }
        return List.of(ChunkDraft.of(ChunkType.FIGURE, content, List.of(BlockKind.FIGURE),
                List.of((DocumentBlock) figure)));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public List<ChunkDraft> chunkTable(TableBlock table, ChunkStatsAccumulator stats) {
        String caption = ChunkTextUtils.firstNonBlank(table.getCaption(), table.getLabel());
        String body = table.getContent() == null ? "" : table.getContent();
        String content = caption == null ? body.trim() : (caption + "\n" + body.trim()).trim();
        if (content.isBlank()) {
            stats.addSkippedTable();
            return List.of();
        }
        int max = properties.getChunker().getMaxTokens();
        if (estimator.estimate(content) <= max) {
            return List.of(ChunkDraft.of(ChunkType.TABLE, content, List.of(BlockKind.TABLE),
                    List.of((DocumentBlock) table)));
        }

        // ===== 超长表格：caption + header + row group，每批重复 caption 与表头 =====
        String[] rows = body.split("\n", -1);
        String headerRow = rows.length > 0 ? rows[0].trim() : "";
        List<String> dataRows = new ArrayList<>();
        for (int i = 1; i < rows.length; i++) {
            if (!rows[i].isBlank()) {
                dataRows.add(rows[i]);
            }
        }
        String prefix = (caption == null ? "" : caption + "\n") + headerRow + "\n";
        int target = properties.getChunker().getTargetTokens();
        int prefixTokens = estimator.estimate(prefix);

        List<ChunkDraft> chunks = new ArrayList<>();
        List<String> batch = new ArrayList<>();
        int tokens = prefixTokens;
        for (String row : dataRows) {
            int rowTokens = estimator.estimate(row + "\n");
            if (!batch.isEmpty() && tokens + rowTokens > target) {
                chunks.add(draft(prefix + String.join("\n", batch), table));
                batch = new ArrayList<>();
                tokens = prefixTokens;
            }
            batch.add(row);
            tokens += rowTokens;
        }
        if (!batch.isEmpty()) {
            chunks.add(draft(prefix + String.join("\n", batch), table));
        }
        if (chunks.isEmpty()) {
            chunks.add(draft(prefix.trim(), table));
        }
        return chunks;
    }

    private ChunkDraft draft(String content, TableBlock table) {
        return ChunkDraft.of(ChunkType.TABLE, content, List.of(BlockKind.TABLE),
                List.of((DocumentBlock) table));
    }
}
