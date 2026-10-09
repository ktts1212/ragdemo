package com.example.ragdemo.document.domain;

import java.util.ArrayList;
import java.util.List;

import com.example.ragdemo.document.domain.block.DocumentBlock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 论文中的一个章节或子章节，形成树结构。
 * <p>
 * 这是后续 section-aware chunking 最重要的领域对象。
 * 例：3 Methods → 3.1 Self-supervised Learning → 3.1.1 Contrastive Objective
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Section {

    /** 当前文档内的内部 ID；解析层优先映射可稳定复用的源 ID，否则生成 */
    private String id;

    /** 来源解析器 block id（如 Marker "/page/2/SectionHeader/3"），用于 trace/debug */
    private String sourceBlockId;

    /** 章节标题，如 "Methods" */
    private String title;

    /** 章节层级（一级/二级/三级） */
    private Integer level;

    /**
     * Marker 原始声明的 heading depth（即 {@code section_hierarchy} 的条目数）。
     * <p>
     * 与 {@link #level} 的区别：
     * <ul>
     *   <li>{@code level} 在解析期会被"标题编号推断"覆盖，在 Cleaner 期又会被按真实嵌套重算</li>
     *   <li>{@code rawLevel} 保留 Marker 的<b>原始意见</b>，作为层级纠偏的独立佐证</li>
     * </ul>
     * 非 Marker 来源（如 GROBID、测试构造）为 {@code null}，表示"无 Marker 佐证"。
     */
    private Integer rawLevel;

    /** 完整章节路径，如 ["Methods", "Self-supervised Learning", "Contrastive Objective"]；不存拼接字符串 */
    @Builder.Default
    private List<String> path = new ArrayList<>();

    /** 该 section 在论文中的原始顺序，用于恢复论文顺序、Chunk 排序、合并相邻 Chunk */
    private Integer order;

    /** 直属当前 Section 的正文内容块 */
    @Builder.Default
    private List<DocumentBlock> blocks = new ArrayList<>();

    /** 子章节 */
    @Builder.Default
    private List<Section> children = new ArrayList<>();
}
