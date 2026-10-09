package com.example.ragdemo.document.cleaner;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.CaptionBlock;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FootnoteBlock;
import com.example.ragdemo.document.domain.block.ListBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;

/**
 * 空内容清理（skill §5.9，替代单纯的 EmptySectionCleaner）。
 * <p>
 * 执行顺序：
 * <ol>
 *   <li>删除 {@code text} 为空的 ParagraphBlock</li>
 *   <li>删除 caption 为空的 CaptionBlock</li>
 *   <li>清理空 ListItem；List 清空则删除 ListBlock</li>
 *   <li>递归删除真正空的 Section（无 block 且无子节点）</li>
 * </ol>
 * <b>不</b>删除 {@code title == null 但 blocks 非空} 的 Section —— 它可能是合法的正文容器。
 * Figure/Table 即使无 caption 也保留（其本身即内容）。
 * <p>
 * <b>例外（skill canonical_section_float_repair）</b>：标题命中主章节锚点的空 Section
 * <b>必须保留</b>。例如空得只剩标题的 {@code Methods}，删除它会让其后的 {@code Datasets} /
 * {@code Pre-processing} 等彻底丢失父级语义，把错误的 sectionPath 注入 embedding。
 */
@Component
public class EmptyContentCleaner {

    /**
     * @return 移除的 block 数 + section 数
     */
    public int clean(AcademicDocument document) {
        if (document == null || document.getSections() == null) {
            return 0;
        }
        int removedBlocks = 0;
        for (Section section : SectionUtils.flatten(document.getSections())) {
            if (section.getBlocks() == null) {
                continue;
            }
            int before = section.getBlocks().size();
            section.getBlocks().removeIf(this::isBlankBlock);
            removedBlocks += before - section.getBlocks().size();
        }
        int removedSections = SectionUtils.removeIf(document.getSections(), this::isRemovableEmptySection);
        return removedBlocks + removedSections;
    }

    /**
     * 可删除的空 Section：无 block、无子节点，<b>且</b>标题不是主章节锚点。
     */
    private boolean isRemovableEmptySection(Section section) {
        if (!SectionUtils.isEmpty(section)) {
            return false;
        }
        return !MajorSectionAnchors.isMajorAnchor(section.getTitle());
    }

    private boolean isBlankBlock(DocumentBlock block) {
        if (block instanceof ParagraphBlock p) {
            return p.getText() == null || p.getText().isBlank();
        }
        if (block instanceof CaptionBlock c) {
            return c.getText() == null || c.getText().isBlank();
        }
        if (block instanceof FootnoteBlock f) {
            return f.getText() == null || f.getText().isBlank();
        }
        if (block instanceof ListBlock list) {
            if (list.getItems() != null) {
                list.getItems().removeIf(item -> item == null || item.isBlank());
            }
            return list.getItems() == null || list.getItems().isEmpty();
        }
        // Figure/Table 即使无 caption 也保留
        return false;
    }
}
