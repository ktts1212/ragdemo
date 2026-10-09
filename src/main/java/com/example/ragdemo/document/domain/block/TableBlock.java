package com.example.ragdemo.document.domain.block;

import java.util.ArrayList;
import java.util.List;

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

    /** 来源解析器 block id 列表（Table 可能由多个 raw block 组成） */
    @Builder.Default
    private List<String> sourceBlockIds = new ArrayList<>();

    /** 表格编号文本，如 "Table 1" */
    private String label;

    /** 表注（允许缺失） */
    private String caption;

    /** 表格 HTML 原文（Marker 直接提供，保留结构，不 flatten 成 Paragraph） */
    private String html;

    /** 表格纯文本表达（TSV：row \n、cell \t）；后续可升级为二维结构 */
    private String content;

    private Integer order;

    private PageRange pageRange;
}
