package com.example.ragdemo.document.chunk;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.ListBlock;

/**
 * LIST block → chunk draft（skill §10）。
 * <p>
 * 规则：
 * <ul>
 *   <li>整体不超过 maxTokens → 一个 LIST chunk</li>
 *   <li>否则按 item 分批至 targetTokens</li>
 *   <li>禁止拆单个 item，除非该 item 自身超过 maxTokens（此时句子级降级切分）</li>
 * </ul>
 */
@Component
public class ListChunker {

    /** 列表项展示前缀，使 embedding 能感知"这是列表" */
    private static final String ITEM_PREFIX = "- ";

    private final ChunkTokenEstimator estimator;
    private final ChunkTextSplitter textSplitter;
    private final RagProperties properties;

    public ListChunker(ChunkTokenEstimator estimator, ChunkTextSplitter textSplitter,
            RagProperties properties) {
        this.estimator = estimator;
        this.textSplitter = textSplitter;
        this.properties = properties;
    }

    public List<ChunkDraft> chunk(ListBlock block) {
        List<String> items = nonBlankItems(block);
        if (items.isEmpty()) {
            return List.of();
        }
        int target = properties.getChunker().getTargetTokens();
        int max = properties.getChunker().getMaxTokens();

        String joined = render(items);
        if (estimator.estimate(joined) <= max) {
            return List.of(draft(joined, block));
        }

        List<ChunkDraft> chunks = new ArrayList<>();
        List<String> batch = new ArrayList<>();
        int tokens = 0;
        for (String item : items) {
            int itemTokens = estimator.estimate(item);
            if (itemTokens > max) {
                if (!batch.isEmpty()) {
                    chunks.add(draft(render(batch), block));
                    batch = new ArrayList<>();
                    tokens = 0;
                }
                for (String part : textSplitter.splitAndPack(item, target, max)) {
                    chunks.add(ChunkDraft.of(ChunkType.LIST, ITEM_PREFIX + part,
                            List.of(BlockKind.LIST), List.of((DocumentBlock) block)));
                }
                continue;
            }
            if (!batch.isEmpty() && tokens + itemTokens > target) {
                chunks.add(draft(render(batch), block));
                batch = new ArrayList<>();
                tokens = 0;
            }
            batch.add(item);
            tokens += itemTokens;
        }
        if (!batch.isEmpty()) {
            chunks.add(draft(render(batch), block));
        }
        return chunks;
    }

    private List<String> nonBlankItems(ListBlock block) {
        List<String> items = new ArrayList<>();
        if (block.getItems() == null) {
            return items;
        }
        for (String item : block.getItems()) {
            if (item != null && !item.isBlank()) {
                items.add(item.trim());
            }
        }
        return items;
    }

    private String render(List<String> items) {
        StringBuilder builder = new StringBuilder();
        for (String item : items) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(ITEM_PREFIX).append(item);
        }
        return builder.toString();
    }

    private ChunkDraft draft(String content, ListBlock block) {
        return ChunkDraft.of(ChunkType.LIST, content, List.of(BlockKind.LIST),
                List.of((DocumentBlock) block));
    }
}
