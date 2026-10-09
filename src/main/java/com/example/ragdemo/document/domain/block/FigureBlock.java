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
 * 论文中的普通图片或图（Figure）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class FigureBlock implements DocumentBlock {

    private String id;

    /** 来源解析器 block id 列表（Figure 可能由多个 raw block 组成） */
    @Builder.Default
    private List<String> sourceBlockIds = new ArrayList<>();

    /** 图编号文本，如 "Figure 2" */
    private String label;

    /** 图注（允许缺失） */
    private String caption;

    private Integer order;

    private PageRange pageRange;

    /**
     * 图片资源 key（MinIO object key 或本地测试 key）。
     * <p>
     * 禁止保存 base64。当前可为空；Parser 解析到 images 后经 FigureImageStorage 落盘再填。
     */
    private String imageKey;

    /** 图内说明文字（descriptionText，区别于 caption） */
    private String descriptionText;
}
