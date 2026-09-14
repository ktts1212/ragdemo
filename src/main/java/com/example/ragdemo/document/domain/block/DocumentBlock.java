package com.example.ragdemo.document.domain.block;

import com.example.ragdemo.document.domain.PageRange;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Section 内不同类型内容的统一抽象。
 * <p>
 * 不要把正文、图片、表格、公式、列表全部压成一个 String ——
 * 每种内容类型保持独立语义，供后续结构感知分块使用。
 * <p>
 * sealed 封闭类型集：新增内容类型必须显式修改 permits，防止静默扩展。
 * Jackson 多态序列化：JSON 中携带 {@code blockType} 字段用于恢复具体子类型。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "blockType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ParagraphBlock.class, name = "PARAGRAPH"),
        @JsonSubTypes.Type(value = FigureBlock.class, name = "FIGURE"),
        @JsonSubTypes.Type(value = TableBlock.class, name = "TABLE"),
        @JsonSubTypes.Type(value = FormulaBlock.class, name = "FORMULA"),
        @JsonSubTypes.Type(value = ListBlock.class, name = "LIST")
})
public sealed interface DocumentBlock
        permits ParagraphBlock, FigureBlock, TableBlock, FormulaBlock, ListBlock {

    String getId();

    Integer getOrder();

    PageRange getPageRange();
}
