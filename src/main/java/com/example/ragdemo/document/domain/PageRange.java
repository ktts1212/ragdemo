package com.example.ragdemo.document.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 内容块在原 PDF 中对应的页码范围。
 * <p>
 * 可被 ParagraphBlock、FigureBlock、TableBlock、FormulaBlock、ListBlock 及后续 PaperChunk 复用。
 * 解析器无法稳定得到页码时允许 null，不伪造页码。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageRange {

    private Integer startPage;

    private Integer endPage;
}
