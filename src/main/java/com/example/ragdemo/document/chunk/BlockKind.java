package com.example.ragdemo.document.chunk;

import com.example.ragdemo.document.domain.block.CaptionBlock;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.FootnoteBlock;
import com.example.ragdemo.document.domain.block.FormulaBlock;
import com.example.ragdemo.document.domain.block.ListBlock;
import com.example.ragdemo.document.domain.block.NoiseBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;

/**
 * chunk 内保留的原始 block 类型标记。
 * <p>
 * 与领域模型的 sealed {@link DocumentBlock} 一一对应，用于 chunk 的 provenance 与 QA。
 */
public enum BlockKind {

    PARAGRAPH,
    LIST,
    FIGURE,
    TABLE,
    FORMULA,
    FOOTNOTE,
    CAPTION,
    NOISE;

    /**
     * 领域 block → 类型标记。
     *
     * @throws IllegalArgumentException 出现未登记的 DocumentBlock 实现（sealed 集被扩展时需要同步修改）
     */
    public static BlockKind from(DocumentBlock block) {
        if (block == null) {
            throw new IllegalArgumentException("DocumentBlock 不能为 null");
        }
        if (block instanceof ParagraphBlock) {
            return PARAGRAPH;
        }
        if (block instanceof ListBlock) {
            return LIST;
        }
        if (block instanceof FigureBlock) {
            return FIGURE;
        }
        if (block instanceof TableBlock) {
            return TABLE;
        }
        if (block instanceof FormulaBlock) {
            return FORMULA;
        }
        if (block instanceof FootnoteBlock) {
            return FOOTNOTE;
        }
        if (block instanceof CaptionBlock) {
            return CAPTION;
        }
        if (block instanceof NoiseBlock) {
            return NOISE;
        }
        throw new IllegalArgumentException("未登记的 DocumentBlock 类型: " + block.getClass().getName());
    }
}
