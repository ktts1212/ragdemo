package com.example.ragdemo.document.cleaner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.CaptionBlock;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;

import lombok.extern.slf4j.Slf4j;

/**
 * Caption 关联（skill §24 / §5.5）。
 * <p>
 * 局部<b>双向</b>关联：
 * <ol>
 *   <li>优先：紧邻<b>前</b>一个 Figure/Table（跨度不超过 MAX_SKIP 个 block）</li>
 *   <li>其次：紧邻<b>后</b>一个 Figure/Table</li>
 * </ol>
 * 约束：类型约束（以 Fig./Figure 开头只关联 Figure；以 Table/TABLE 开头只关联 Table）；
 * 停止条件：Section 边界、长段落、另一个 Caption、另一个 Figure/Table。
 * 无法关联时<b>保留</b>独立 CaptionBlock（不丢信息）。
 */
@Slf4j
@Component
public class CaptionRecovery {

    /** 与目标之间允许跨越的最大 block 数 */
    private static final int MAX_SKIP = 3;
    /** 可跳过的"极短说明性 block"上限（skill §6.3） */
    private static final int SHORT_PARAGRAPH_MAX = 40;

    /**
     * @return 成功关联的 caption 数量
     */
    public int recover(AcademicDocument document) {
        if (document == null || document.getSections() == null) {
            return 0;
        }
        int associated = 0;
        for (Section section : SectionUtils.flatten(document.getSections())) {
            associated += recoverInSection(section);
        }
        if (associated > 0) {
            log.debug("CaptionRecovery: 关联 caption {} 个", associated);
        }
        return associated;
    }

    private int recoverInSection(Section section) {
        List<DocumentBlock> blocks = section.getBlocks();
        if (blocks == null || blocks.isEmpty()) {
            return 0;
        }
        int associated = 0;
        List<DocumentBlock> result = new ArrayList<>(blocks.size());

        for (int i = 0; i < blocks.size(); i++) {
            DocumentBlock block = blocks.get(i);
            if (!(block instanceof CaptionBlock caption)) {
                result.add(block);
                continue;
            }

            // 1) 向前找最近的 Figure/Table
            DocumentBlock target = findPreceding(result, caption);
            // 2) 找不到则向后找（caption 在目标之前的情况）
            if (target == null) {
                target = findFollowing(blocks, i + 1, caption);
            }

            if (target == null) {
                result.add(block); // 无法关联：保留，不丢
                continue;
            }
            attachCaption(target, caption);
            associated++;
        }

        blocks.clear();
        blocks.addAll(result);
        return associated;
    }

    /** 从已处理块中反向查找最近的 Figure/Table（窗口 + 停止条件 + 类型约束） */
    private DocumentBlock findPreceding(List<DocumentBlock> processed, CaptionBlock caption) {
        int steps = 0;
        for (int k = processed.size() - 1; k >= 0 && steps <= MAX_SKIP; k--, steps++) {
            DocumentBlock candidate = processed.get(k);
            if (candidate instanceof FigureBlock || candidate instanceof TableBlock) {
                return matchesType(candidate, caption) ? candidate : null;
            }
            if (isSkippable(candidate)) {
                continue; // 跳过空/极短说明性 block / Noise（skill §6.3）
            }
            return null; // 长段落 / 另一个 Caption / 其他 → 停止
        }
        return null;
    }

    /** 从后续块中查找最近的 Figure/Table */
    private DocumentBlock findFollowing(List<DocumentBlock> blocks, int start, CaptionBlock caption) {
        int steps = 0;
        for (int k = start; k < blocks.size() && steps <= MAX_SKIP; k++, steps++) {
            DocumentBlock candidate = blocks.get(k);
            if (candidate instanceof FigureBlock || candidate instanceof TableBlock) {
                return matchesType(candidate, caption) ? candidate : null;
            }
            if (isSkippable(candidate)) {
                continue;
            }
            return null;
        }
        return null;
    }

    /**
     * 可跳过的 block（skill §6.3）：空 Paragraph / 极短说明性 block / Noise-like block。
     * 长段落与另一个 Caption 不可跳过（立即停止）。
     */
    private boolean isSkippable(DocumentBlock block) {
        if (block instanceof com.example.ragdemo.document.domain.block.NoiseBlock) {
            return true;
        }
        if (block instanceof ParagraphBlock p) {
            String text = p.getText();
            return text == null || text.isBlank() || text.trim().length() < SHORT_PARAGRAPH_MAX;
        }
        return false;
    }

    /**
     * 类型约束（skill §5.5）：caption 以 Fig./Figure 开头 → 只关联 Figure；
     * 以 Table 开头 → 只关联 Table；无明确前缀 → 允许任意。
     */
    private boolean matchesType(DocumentBlock target, CaptionBlock caption) {
        String text = caption.getText() == null ? "" : caption.getText().trim().toLowerCase(Locale.ROOT);
        boolean looksTable = text.startsWith("table") || text.startsWith("tab.");
        boolean looksFigure = text.startsWith("fig.") || text.startsWith("figure")
                || text.startsWith("fig ");
        if (looksTable) {
            return target instanceof TableBlock;
        }
        if (looksFigure) {
            return target instanceof FigureBlock;
        }
        return true;
    }

    private void attachCaption(DocumentBlock target, CaptionBlock caption) {
        String captionText = caption.getText() == null ? "" : caption.getText().trim();
        if (target instanceof FigureBlock figure) {
            figure.setCaption(joinCaption(figure.getCaption(), captionText));
            appendSourceBlockId(figure.getSourceBlockIds(), caption.getSourceBlockId());
        } else if (target instanceof TableBlock table) {
            table.setCaption(joinCaption(table.getCaption(), captionText));
            appendSourceBlockId(table.getSourceBlockIds(), caption.getSourceBlockId());
        }
    }

    private String joinCaption(String existing, String incoming) {
        if (incoming.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return incoming;
        }
        return existing + " " + incoming;
    }

    private void appendSourceBlockId(List<String> ids, String id) {
        if (id != null && !id.isBlank() && !ids.contains(id)) {
            ids.add(id);
        }
    }
}
