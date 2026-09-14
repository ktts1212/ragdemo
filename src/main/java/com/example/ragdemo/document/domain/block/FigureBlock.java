package com.example.ragdemo.document.domain.block;

import com.example.ragdemo.document.domain.PageRange;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 论文中的普通图片或图（Figure）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class FigureBlock implements DocumentBlock {

    private String id;

    /** 图编号文本，如 "Figure 2" */
    private String label;

    /** 图注（允许缺失） */
    private String caption;

    private Integer order;

    private PageRange pageRange;

    /** 图片资源路径（当前可为空；后续提取图片后可保存 MinIO object key，不保存本地绝对路径） */
    private String assetPath;
}
