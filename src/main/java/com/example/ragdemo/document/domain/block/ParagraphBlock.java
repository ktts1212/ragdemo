package com.example.ragdemo.document.domain.block;

import java.util.ArrayList;
import java.util.List;

import com.example.ragdemo.document.domain.CitationMarker;
import com.example.ragdemo.document.domain.PageRange;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 论文正文中的一个自然段 —— 后续 Chunker 最主要的数据输入单位。
 * <p>
 * SectionAwareChunker 优先按 Section → Paragraph → Sentence → Token 切分，
 * 因此 Paragraph 必须作为独立结构保留。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class ParagraphBlock implements DocumentBlock {

    private String id;

    private String text;

    private Integer order;

    private PageRange pageRange;

    /** 段落内出现的文献引用标记 */
    @Builder.Default
    private List<CitationMarker> citations = new ArrayList<>();
}
