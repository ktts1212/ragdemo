package com.example.ragdemo.document.domain.block;

import com.example.ragdemo.document.domain.PageRange;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 图/表题注（Caption）块。
 * <p>
 * Parser 阶段忠实保留独立的 Caption，不做"关联到最近 Figure/Table"的猜测——
 * 该关联属于 Cleaner 的 CaptionRecovery（skill §24）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class CaptionBlock implements DocumentBlock {

    private String id;

    /** 来源解析器 block id */
    private String sourceBlockId;

    private String text;

    private Integer order;

    private PageRange pageRange;
}
