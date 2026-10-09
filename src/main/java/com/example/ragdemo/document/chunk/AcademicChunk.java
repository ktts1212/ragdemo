package com.example.ragdemo.document.chunk;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SectionAwareChunker 的输出单元（skill §4）。
 * <p>
 * 与 {@code AcademicDocument} 类似，它不等价于数据库 {@code document_chunks} 表：
 * 本对象描述"这篇论文在语义上应该切成哪些检索单元"，入库/向量化属于后续阶段。
 * <p>
 * 关键字段语义：
 * <ul>
 *   <li>{@code content}：纯净内容（无任何前缀），用于展示与统计</li>
 *   <li>{@code embeddingText}：{@code content} 加上 Document / Section 上下文前缀，是<b>唯一</b>送入 embedding 的字段</li>
 *   <li>{@code tokenCount}：<b>基于 content</b> 统计，避免前缀污染 token 预算</li>
 *   <li>{@code chunkId}：{@code documentId:scopeId:localIndex}，稳定可复现（不含随机量）</li>
 *   <li>{@code contentHash}：embeddingText 的 SHA-256 前 16 位，用于变更检测（不参与 chunkId，保证 ID 稳定）</li>
 * </ul>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcademicChunk {

    /** 稳定 ID：documentId:scopeId:localIndex */
    private String chunkId;

    private String documentId;

    private ChunkType chunkType;

    /** 所属 Section id；ABSTRACT / REFERENCE chunk 为 null */
    private String sectionId;

    /** 所属 Section 标题；可为 null */
    private String sectionTitle;

    /** 所属 Section 路径（已过滤空白段）；ABSTRACT / REFERENCE chunk 为空列表 */
    @Builder.Default
    private List<String> sectionPath = new ArrayList<>();

    private Integer sectionLevel;

    /** 纯净内容（无前缀） */
    private String content;

    /** 送入 embedding 的文本 = 上下文前缀 + content */
    private String embeddingText;

    /** 组成该 chunk 的原始 block 类型（有序去重） */
    @Builder.Default
    private List<BlockKind> blockTypes = new ArrayList<>();

    /** 来源解析器 block id 并集（有序去重） */
    @Builder.Default
    private List<String> sourceBlockIds = new ArrayList<>();

    /** 因 overlap 被重复计入的 block id（仅 TEXT chunk 可能出现） */
    @Builder.Default
    private List<String> overlapBlockIds = new ArrayList<>();

    private Integer startPage;

    private Integer endPage;

    /** 基于 content 估算的 token 数 */
    private int tokenCount;

    private int charCount;

    /** 全文档内的稳定顺序（0 起，ABSTRACT → sections DFS → references） */
    private int order;

    /** tokenCount > maxTokens（由超长段落/超长 item 强制切分产生） */
    private boolean oversized;

    /** tokenCount < minTokens（极短 section 等，v1 保留独立 chunk 不强合并） */
    private boolean undersized;

    /** embeddingText 的 SHA-256 前 16 位 */
    private String contentHash;

    /** 文档标题上下文 */
    private String titleContext;

    /** 来源文献 id（仅 REFERENCE chunk 使用） */
    @Builder.Default
    private List<String> referenceIds = new ArrayList<>();
}
