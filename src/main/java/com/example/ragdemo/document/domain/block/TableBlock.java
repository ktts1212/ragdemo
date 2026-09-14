package com.example.ragdemo.document.domain.block;

import com.example.ragdemo.document.domain.PageRange;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 论文中的表格。
 * <p>
 * GROBID TEI 中表格可能以 {@code <figure type="table">} 表达，但领域模型中 Figure != Table，
 * 因此独立建模。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class TableBlock implements DocumentBlock {

    private String id;

    /** 表格编号文本，如 "Table 1" */
    private String label;

    /** 表注（允许缺失） */
    private String caption;

    /** 表格的文本表达；后续可升级为二维表格结构，当前不要求 */
    private String content;

    private Integer order;

    private PageRange pageRange;
}
