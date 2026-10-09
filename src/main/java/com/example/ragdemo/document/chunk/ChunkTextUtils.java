package com.example.ragdemo.document.chunk;

/**
 * chunk 渲染用的小工具（仅本包使用）。
 */
final class ChunkTextUtils {

    private ChunkTextUtils() {
    }

    /** 以空格连接所有非空白片段；全为空白时返回空串。 */
    static String joinNonBlank(String... parts) {
        if (parts == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(part.trim());
        }
        return builder.toString();
    }

    /** 返回第一个非空白值；全为空白时返回 null。 */
    static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
