package com.example.ragdemo.document.domain.block;

import com.example.ragdemo.document.domain.PageRange;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 论文中的独立公式。
 * <p>
 * 只负责存储解析结果，不在实体类里做 LaTeX 转换、MathML 解析、Embedding 或公式理解。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class FormulaBlock implements DocumentBlock {

    private String id;

    /** 公式编号文本，如 "(1)" */
    private String label;

    /** 公式内容（按解析器输出原样保存） */
    private String formula;

    private Integer order;

    private PageRange pageRange;
}
