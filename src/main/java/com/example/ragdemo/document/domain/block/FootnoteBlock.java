package com.example.ragdemo.document.domain.block;

import com.example.ragdemo.document.domain.PageRange;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 脚注（Footnote）块。
 * <p>
 * Parser 阶段独立保留，不与正文混排；是否过滤由 Cleaner 决定。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class FootnoteBlock implements DocumentBlock {

    private String id;

    /** 来源解析器 block id */
    private String sourceBlockId;

    private String text;

    private Integer order;

    private PageRange pageRange;
}
