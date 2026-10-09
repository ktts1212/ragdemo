package com.example.ragdemo.document.chunk;

/**
 * 高层 chunk 类型（skill §5）。
 * <p>
 * 注意：这不是原始 {@code BlockType} 的简单拷贝，而是"检索语义单元"的分类，
 * 用于后续监控、降权与检索过滤（例如 {@link #REFERENCE} 默认价值较低）。
 */
public enum ChunkType {

    /** 连续 PARAGRAPH 组成 */
    TEXT,

    /** 一个 LIST（可能带短前置说明段落 → MIXED） */
    LIST,

    /** 图（caption / descriptionText） */
    FIGURE,

    /** 表格（caption + 表格内容） */
    TABLE,

    /** 公式 */
    FORMULA,

    /** 脚注 */
    FOOTNOTE,

    /** 未能与图/表绑定的孤立题注（Cleaner 遗留给 Chunker 处理，不丢弃） */
    CAPTION,

    /** 同 Section 内少量异构块（v1 仅 PARAGRAPH + 一种结构块） */
    MIXED,

    /** 论文摘要（高价值检索入口） */
    ABSTRACT,

    /** 参考文献条目（不在正文 Section 内，独立成 chunk） */
    REFERENCE
}
