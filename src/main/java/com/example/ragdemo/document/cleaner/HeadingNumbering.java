package com.example.ragdemo.document.cleaner;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标题编号深度解析（skill §5.2）。
 * <p>
 * 支持：
 * <pre>
 * ^\d+(\.\d+)*[\.\)]?\s+     2 → 1, 2.1 → 2, 2.1.1 → 3
 * ^[A-Z]\.\d+                A.1 → 3（附录小节）
 * ^[A-Z][\.\)]\s+            A. / B. → 2（字母子节）
 * ^[IVXLC]+[\.\)]\s+         II. / III. → 1（罗马数字一级）
 * </pre>
 * <b>歧义处理</b>：单字符罗马数字（{@code I.} / {@code V.} / {@code C.}）与字母子节形式相同，
 * 仅当其后标题文本<b>全为大写</b>时才判为罗马数字（一级），否则视为字母子节（二级）。
 * 该逻辑与解析期 {@code MarkerSectionBuilder.levelFromNumbering} 保持一致。
 * <p>
 * 只做"可解析/不可解析"判定：返回 {@code null} 表示标题无编号，纠偏时不得据此推断。
 */
final class HeadingNumbering {

    private static final Pattern ROMAN_PATTERN = Pattern.compile("^([IVXLC]+)\\.\\s+(.*)$");

    /** 严格阿拉伯编号前缀：{@code 4} / {@code 4.6} / {@code 4.4.6.}（尾部句点可选） */
    private static final Pattern NUMERIC_PREFIX =
            Pattern.compile("^(\\d+(?:\\.\\d+)*)\\.?\\s+.*$", Pattern.DOTALL);

    private HeadingNumbering() {
    }

    /**
     * @return 编号深度；无编号或不可解析时为 {@code null}
     */
    static Integer depth(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String value = title.trim();
        // 附录小节：A.1 / B.2 → 三级
        if (value.matches("^[A-Z]\\.\\d+.*")) {
            return 3;
        }
        // 罗马数字：多字符（II./III./IV.）→ 一级；单字符仅当后续标题全大写才判为罗马数字
        Matcher roman = ROMAN_PATTERN.matcher(value);
        if (roman.matches() && (roman.group(1).length() > 1 || isAllCaps(roman.group(2)))) {
            return 1;
        }
        // 阿拉伯多级：2.1 / 3.2.1 / 4.4.6.（带尾部句点，学术 PDF 常见）→ 点数
        if (value.matches("^\\d+(\\.\\d+)+\\.?\\s+.*")) {
            String numbering = value.split("\\s+")[0].replaceAll("\\.$", "");
            return numbering.split("\\.").length;
        }
        // 单字母：A. / C. / D. → 二级
        if (value.matches("^[A-Z]\\.\\s+.*")) {
            return 2;
        }
        // 阿拉伯数字：1 / 1. → 一级
        if (value.matches("^\\d+\\.?\\s+.*")) {
            return 1;
        }
        return null;
    }

    /**
     * 纯阿拉伯多级编号 token（用于前缀运算）。
     * <p>
     * 只认严格形如 {@code N} / {@code N.N} / {@code N.N.N.} 的编号：
     * {@code "4.6. Model robustness"} → {@code "4.6"}、{@code "4. Experiments and results"} → {@code "4"}。
     * 罗马数字 / 字母 / 无编号一律返回 {@code null}（这些形式的前缀语义不确定，保守跳过）。
     */
    static String numericToken(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        Matcher matcher = NUMERIC_PREFIX.matcher(title.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * 上一级编号 token：{@code "4.6"} → {@code "4"}；{@code "4.4.6"} → {@code "4.4"}；
     * 已是顶层（{@code "4"}）→ {@code null}。
     */
    static String parentToken(String numericToken) {
        if (numericToken == null || numericToken.isBlank()) {
            return null;
        }
        int index = numericToken.lastIndexOf('.');
        return index < 0 ? null : numericToken.substring(0, index);
    }

    private static boolean isAllCaps(String text) {        boolean hasLetter = false;
        for (char c : text.toCharArray()) {
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (!Character.isUpperCase(c)) {
                    return false;
                }
            }
        }
        return hasLetter;
    }
}
