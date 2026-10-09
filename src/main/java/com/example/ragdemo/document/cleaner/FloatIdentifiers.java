package com.example.ragdemo.document.cleaner;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.TableBlock;

/**
 * Figure/Table 编号识别（skill §6.3）。
 * <p>
 * 归一到 {@code FIGURE:4} / {@code TABLE:S3} 形式的 key，供
 * {@link FloatBlockSectionReassigner}（重挂）与
 * {@link CanonicalDocumentValidator}（独立复核 mismatch 计数）共用，避免正则重复定义。
 */
final class FloatIdentifiers {

    /** 图编号：Fig. 1 / Figure 1 / Fig 1 / Fig. S2 */
    static final Pattern FIGURE_PATTERN =
            Pattern.compile("(?i)\\bfig(?:ure)?\\.?\\s*(S?\\d+[A-Za-z]?)\\b");

    /** 表编号：Table 1 / Table S3 */
    static final Pattern TABLE_PATTERN =
            Pattern.compile("(?i)\\btable\\s*(S?\\d+[A-Za-z]?)\\b");

    static final String FIGURE_PREFIX = "FIGURE:";
    static final String TABLE_PREFIX = "TABLE:";

    private FloatIdentifiers() {
    }

    /**
     * 从 block 解析编号 key（label 优先、caption 兜底）。
     *
     * @return {@code FIGURE:4} / {@code TABLE:S3}；非 Figure/Table 或无编号时返回 null
     */
    static String keyOf(DocumentBlock block) {
        if (block instanceof FigureBlock figure) {
            String number = firstNumber(FIGURE_PATTERN, figure.getLabel(), figure.getCaption());
            return number == null ? null : FIGURE_PREFIX + number;
        }
        if (block instanceof TableBlock table) {
            String number = firstNumber(TABLE_PATTERN, table.getLabel(), table.getCaption());
            return number == null ? null : TABLE_PREFIX + number;
        }
        return null;
    }

    /** label 优先、caption 兜底；编号统一大写。 */
    static String firstNumber(Pattern pattern, String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Matcher matcher = pattern.matcher(candidate);
            if (matcher.find()) {
                return matcher.group(1).toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }
}
