package com.example.ragdemo.document.marker.raw;

/**
 * Marker block 类型枚举（skill §9）。
 * <p>
 * 未知类型映射为 {@link #UNKNOWN}，不抛异常。
 */
public enum MarkerBlockType {

    DOCUMENT,
    PAGE,
    PAGE_HEADER,
    PAGE_FOOTER,
    SECTION_HEADER,
    TEXT,
    FIGURE,
    FIGURE_GROUP,
    CAPTION,
    TABLE,
    TABLE_GROUP,
    LIST,
    LIST_GROUP,
    LIST_ITEM,
    EQUATION,
    FOOTNOTE,
    /** 图片类：Marker 偶发输出的图（等同 Figure） */
    PICTURE,
    /** 图表类：Marker 偶发输出的图解（等同 Figure） */
    DIAGRAM,
    /** 参考文献条目块（内容同 ListItem，归入 references 候选） */
    REFERENCE,
    UNKNOWN;

    /**
     * 宽松解析：null / 未知 / 大小写差异均安全映射。
     */
    public static MarkerBlockType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        // 先把 CamelCase 拆成 SNAKE，再转大写（顺序不可颠倒，否则正则失效）
        String normalized = raw.trim()
                .replace('-', '_')
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toUpperCase();
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
