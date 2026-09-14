package com.example.ragdemo.document.domain;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 正文中的文献引用标记。
 * <p>
 * 例：正文 "Previous studies [12, 15] showed..." 中 "[12, 15]" 即为一个 citation marker。
 * 通过 targetReferenceIds 关联 {@link Reference#getId()}，是未来构建引用关系/知识图谱的基础。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitationMarker {

    /** 标记原文，如 "[12]" */
    private String text;

    /** 目标文献 ID 列表，对应 Reference.id */
    @Builder.Default
    private List<String> targetReferenceIds = new ArrayList<>();
}
