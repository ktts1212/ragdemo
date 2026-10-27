package com.example.ragdemo.paper;

import java.util.List;

import com.example.ragdemo.document.domain.PageRange;
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
 * Canonical {@link DocumentBlock} → 文本/标签/页码 的只读渲染工具（Step 2 §5.3/§5.6）。
 * <p>
 * <b>不做任何加工</b>：不总结、不换算、不补全缺失数值、不修正 OCR/解析错误。
 * 表格优先用 Marker 给出的 TSV（{@code content}），其次才退回原始 HTML ——
 * 两者都是解析产物原文，不是推断值。
 */
final class CanonicalBlockRenderer {

    private CanonicalBlockRenderer() {
    }

    /** block 类型名（与 canonical JSON 的 blockType 一致）。 */
    static String blockType(DocumentBlock block) {
        if (block instanceof ParagraphBlock) {
            return "PARAGRAPH";
        }
        if (block instanceof TableBlock) {
            return "TABLE";
        }
        if (block instanceof FigureBlock) {
            return "FIGURE";
        }
        if (block instanceof FormulaBlock) {
            return "FORMULA";
        }
        if (block instanceof ListBlock) {
            return "LIST";
        }
        if (block instanceof CaptionBlock) {
            return "CAPTION";
        }
        if (block instanceof FootnoteBlock) {
            return "FOOTNOTE";
        }
        if (block instanceof NoiseBlock) {
            return "NOISE";
        }
        return "UNKNOWN";
    }

    /** 编号标签（如 "Table 2" / "Figure 1" / "(3)"），取不到返回 null，不生成。 */
    static String label(DocumentBlock block) {
        if (block instanceof TableBlock table) {
            return blankToNull(table.getLabel());
        }
        if (block instanceof FigureBlock figure) {
            return blankToNull(figure.getLabel());
        }
        if (block instanceof FormulaBlock formula) {
            return blankToNull(formula.getLabel());
        }
        return null;
    }

    /** 题注文本（Figure/Table 才有），取不到返回 null。 */
    static String caption(DocumentBlock block) {
        if (block instanceof TableBlock table) {
            return blankToNull(table.getCaption());
        }
        if (block instanceof FigureBlock figure) {
            return blankToNull(figure.getCaption());
        }
        return null;
    }

    /** 渲染 block 的原文文本（表格为 TSV，公式为原样 LaTeX/文本）。 */
    static String render(DocumentBlock block) {
        if (block instanceof ParagraphBlock paragraph) {
            return orEmpty(paragraph.getText());
        }
        if (block instanceof TableBlock table) {
            // 表格内容<b>原样返回</b>：TSV 的前导 tab 表示空单元格，trim 会导致行列错位
            if (!isBlank(table.getContent())) {
                return table.getContent();
            }
            return orEmpty(table.getHtml());
        }
        if (block instanceof FigureBlock figure) {
            StringBuilder sb = new StringBuilder();
            if (figure.getLabel() != null) {
                sb.append('[').append(figure.getLabel()).append(']');
            }
            if (figure.getCaption() != null) {
                sb.append(' ').append(figure.getCaption());
            }
            if (figure.getDescriptionText() != null) {
                sb.append(' ').append(figure.getDescriptionText());
            }
            // 图本身是二进制资源：这里只给图注/说明，不给图像字节
            return sb.toString().trim();
        }
        if (block instanceof FormulaBlock formula) {
            String body = orEmpty(formula.getFormula());
            return formula.getLabel() == null ? body : "[" + formula.getLabel() + "] " + body;
        }
        if (block instanceof ListBlock list) {
            if (list.getItems() == null || list.getItems().isEmpty()) {
                return "";
            }
            return String.join("\n", list.getItems());
        }
        if (block instanceof CaptionBlock caption) {
            return orEmpty(caption.getText());
        }
        if (block instanceof FootnoteBlock footnote) {
            return orEmpty(footnote.getText());
        }
        return "";
    }

    /** 是否是可作为"正文"的内容块（排除噪声/脚注等）。 */
    static boolean isContentBlock(DocumentBlock block) {
        return block instanceof ParagraphBlock || block instanceof TableBlock
                || block instanceof FigureBlock || block instanceof FormulaBlock
                || block instanceof ListBlock || block instanceof CaptionBlock;
    }

    static Integer startPage(DocumentBlock block) {
        PageRange range = block.getPageRange();
        return range == null ? null : range.getStartPage();
    }

    static Integer endPage(DocumentBlock block) {
        PageRange range = block.getPageRange();
        return range == null ? null : range.getEndPage();
    }

    /** 页码区间是否覆盖给定页（任一端为 null 视为不可判定 ⇒ 不算命中）。 */
    static boolean coversPage(DocumentBlock block, int pageNumber) {
        Integer start = startPage(block);
        Integer end = endPage(block);
        if (start == null || end == null) {
            return false;
        }
        return pageNumber >= start && pageNumber <= end;
    }

    /** 章节路径的稳定锚点（供 sectionId 缺失时按路径取正文）。 */
    static String pathKey(List<String> path) {
        return path == null || path.isEmpty() ? "" : String.join(" / ", path);
    }

    static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
