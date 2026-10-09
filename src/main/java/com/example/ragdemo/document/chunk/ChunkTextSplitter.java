package com.example.ragdemo.document.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 超长文本的降级切分工具（skill §9.3）。
 * <p>
 * 优先级：句子边界 → 标点（由句子切分隐含）→ token 硬切（最后兜底）。
 * 切分后按 {@code targetTokens} 重新打包，避免产生大量极短碎片。
 * <p>
 * 注意：硬切<b>不依赖固定字符比</b>，而是二分查找"不超过 maxTokens 的最大前缀"，
 * 因此更换 {@link ChunkTokenEstimator} 实现时依然正确。
 */
@Component
public class ChunkTextSplitter {

    /** 句末标点 + 空白（中英文兼顾） */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?。！？])\\s+");

    private final ChunkTokenEstimator estimator;

    public ChunkTextSplitter(ChunkTokenEstimator estimator) {
        this.estimator = estimator;
    }

    /** 按句末标点切句；无法切分时返回原文本单元素列表。 */
    public List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String trimmed = text.trim();
        List<String> sentences = new ArrayList<>();
        for (String part : SENTENCE_BOUNDARY.split(trimmed)) {
            if (part != null && !part.isBlank()) {
                sentences.add(part.trim());
            }
        }
        return sentences.isEmpty() ? List.of(trimmed) : sentences;
    }

    /**
     * 超长文本 → 句子级切分 →（必要时）token 硬切 → 按 target 重新打包。
     *
     * @return 打包后的文本片段列表（k 段之和 = 原文内容）
     */
    public List<String> splitAndPack(String text, int targetTokens, int maxTokens) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int target = Math.max(1, targetTokens);
        int max = Math.max(1, maxTokens);

        List<String> units = new ArrayList<>();
        for (String sentence : splitSentences(text)) {
            if (estimator.estimate(sentence) > max) {
                units.addAll(hardSplit(sentence, max));
            } else {
                units.add(sentence);
            }
        }
        if (units.isEmpty()) {
            return List.of(text.trim());
        }

        List<String> packed = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int tokens = 0;
        for (String unit : units) {
            int unitTokens = estimator.estimate(unit);
            if (buffer.length() > 0 && tokens + unitTokens > target) {
                packed.add(buffer.toString());
                buffer.setLength(0);
                tokens = 0;
            }
            if (buffer.length() > 0) {
                buffer.append(' ');
            }
            buffer.append(unit);
            tokens += unitTokens;
        }
        if (buffer.length() > 0) {
            packed.add(buffer.toString());
        }
        return packed;
    }

    /**
     * token 硬切兜底：二分查找不超过 maxTokens 的最大前缀，逐段推进。
     * 仅在单个句子本身超过 maxTokens 时触发。
     */
    private List<String> hardSplit(String text, int maxTokens) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int length = text.length();
        while (start < length) {
            int low = start + 1;
            int high = length;
            int best = start + 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (estimator.estimate(text.substring(start, mid)) <= maxTokens) {
                    best = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            parts.add(text.substring(start, best).trim());
            start = best;
        }
        parts.removeIf(String::isBlank);
        return parts.isEmpty() ? List.of(text.trim()) : parts;
    }
}
