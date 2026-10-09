package com.example.ragdemo.document.domain.block;

import com.example.ragdemo.document.domain.PageRange;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 噪声块（PageHeader / PageFooter / 其他无检索价值内容）。
 * <p>
 * Parser 阶段保留（可追踪 Principle 4），由 Cleaner 的 HeaderFooterCleaner 决定移除。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class NoiseBlock implements DocumentBlock {

    private String id;

    /** 来源解析器 block id */
    private String sourceBlockId;

    private String text;

    private Integer order;

    private PageRange pageRange;

    /** 噪声来源类型，如 PAGE_HEADER / PAGE_FOOTER / UNKNOWN */
    private String noiseType;
}
