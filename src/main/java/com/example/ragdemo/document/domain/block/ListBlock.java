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
 * 论文中的列表内容。
 * <p>
 * 列表直接打平成长字符串会损失结构，因此作为独立 Block 保留。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class ListBlock implements DocumentBlock {

    private String id;

    /** 列表项 */
    @Builder.Default
    private List<String> items = new ArrayList<>();

    private Integer order;

    private PageRange pageRange;
}
