package com.example.ragdemo.document.repair;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PageRange;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.CaptionBlock;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.FootnoteBlock;
import com.example.ragdemo.document.domain.block.FormulaBlock;
import com.example.ragdemo.document.domain.block.NoiseBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;

import lombok.extern.slf4j.Slf4j;

/**
 * 阅读顺序修复（skill §17-20）。
 * <p>
 * 只修复明显的版面阅读顺序断裂，不做论文语义清洗。典型场景：
 * <pre>
 * Paragraph A
 * ↓ (Figure / Table / PageHeader / PageFooter / Footnote 等打断)
 * Paragraph B        ← 实际上 A+B 是一句话
 * </pre>
 * 判定规则（保守，skill §18）：A 长度 ≥ 20 且结尾非 .!?:;；B 首字母小写；
 * 且 B 不是 figure-associated text。绝不因为"两个段落相邻"就合并。
 * <p>
 * 每次合并产生 {@link ReadingOrderRepairAudit}，保证可追踪。
 */
@Slf4j
@Component
public class ReadingOrderRepair {

    private static final int CONTINUATION_MIN_LENGTH = 20;
    private static final String REASON_CONTINUATION = "cross-block sentence continuation";

    private final ReadingOrderRepairConfig config;
    private final FigureAssociatedTextDetector figureAssociatedTextDetector;

    public ReadingOrderRepair(ReadingOrderRepairConfig config,
            FigureAssociatedTextDetector figureAssociatedTextDetector) {
        this.config = config;
        this.figureAssociatedTextDetector = figureAssociatedTextDetector;
    }

    /**
     * 修复整篇文档的阅读顺序（原地修改 sections）。
     *
     * @return 所有合并操作的审计记录
     */
    public List<ReadingOrderRepairAudit> repair(AcademicDocument document) {
        List<ReadingOrderRepairAudit> audits = new ArrayList<>();
        if (document == null || document.getSections() == null) {
            return audits;
        }
        for (Section section : document.getSections()) {
            repairSection(section, audits);
        }
        if (!audits.isEmpty()) {
            log.info("ReadingOrderRepair 完成: mergeCount={}, documentId={}",
                    audits.size(), document.getDocumentId());
        }
        return audits;
    }

    private void repairSection(Section section, List<ReadingOrderRepairAudit> audits) {
        repairBlocks(section.getBlocks(), audits);
        if (section.getChildren() != null) {
            for (Section child : section.getChildren()) {
                repairSection(child, audits);
            }
        }
    }

    private void repairBlocks(List<DocumentBlock> blocks, List<ReadingOrderRepairAudit> audits) {
        if (blocks == null || blocks.size() < 2) {
            return;
        }
        // 每次成功合并后重启扫描，保证链式续接也能处理
        boolean merged = true;
        while (merged) {
            merged = false;
            for (int i = 0; i < blocks.size(); i++) {
                if (!(blocks.get(i) instanceof ParagraphBlock left)) {
                    continue;
                }
                if (!isContinuationLeft(left.getText())) {
                    continue;
                }
                int limit = Math.min(blocks.size() - 1, i + config.getMaxSkipBlocks());
                List<String> skipped = new ArrayList<>();
                for (int j = i + 1; j <= limit; j++) {
                    DocumentBlock candidate = blocks.get(j);
                    if (candidate instanceof ParagraphBlock right) {
                        // 仅修复"被透明块打断"的续接：相邻段落由 Marker 保证顺序，不合并
                        if (skipped.isEmpty()) {
                            break;
                        }
                        if (!isContinuationRight(right.getText())) {
                            break;
                        }
                        if (isFigureAssociated(right.getText())) {
                            break; // 图说明文字不得并入正文
                        }
                        mergeParagraphs(left, right);
                        audits.add(new ReadingOrderRepairAudit(
                                left.getSourceBlockId(),
                                right.getSourceBlockId(),
                                List.copyOf(skipped),
                                REASON_CONTINUATION));
                        // 只删除 B 与"纯噪声"块；保留 Figure/Table/Caption/Formula/Footnote。
                        // 这些有内容的透明块夹在待合并段落之间时不得被连带删除，否则内容丢失。
                        for (int k = j; k > i; k--) {
                            DocumentBlock between = blocks.get(k);
                            if (k == j || between instanceof NoiseBlock) {
                                blocks.remove(k);
                            }
                        }
                        merged = true;
                        break;
                    }
                    if (isTransparent(candidate)) {
                        skipped.add(blockIdOf(candidate));
                        continue;
                    }
                    break; // 遇到非透明、非段落块 → 停止（避免跨语义边界合并）
                }
                if (merged) {
                    break;
                }
            }
        }
    }

    // ==================== 判定 ====================

    /**
     * A 满足续接条件：长度 >= 20 且结尾不是 . ! ? : ;
     */
    private boolean isContinuationLeft(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.length() < CONTINUATION_MIN_LENGTH) {
            return false;
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        return last != '.' && last != '!' && last != '?' && last != ':' && last != ';';
    }

    /**
     * B 满足续接条件：首个字母为小写（非明显新段/新句）。
     * URL / DOI / 邮箱等开头的文本不算句子续接。
     */
    private boolean isContinuationRight(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("www.") || lower.startsWith("doi:")
                || lower.matches("^10\\.\\d{4,9}/.*")) {
            return false;
        }
        for (char c : trimmed.toCharArray()) {
            if (Character.isLetter(c)) {
                return Character.isLowerCase(c);
            }
        }
        return false;
    }

    private boolean isFigureAssociated(String text) {
        if (!config.isFigureAssociationEnabled()) {
            return false;
        }
        return figureAssociatedTextDetector.detect(text).associated();
    }

    /**
     * 判定某 block 是否"透明"（可跳过而不中断续接）。
     */
    private boolean isTransparent(DocumentBlock block) {
        if (block instanceof NoiseBlock) {
            return config.isTransparentNoiseEnabled();
        }
        return block instanceof FootnoteBlock
                || block instanceof FigureBlock
                || block instanceof TableBlock
                || block instanceof CaptionBlock
                || block instanceof FormulaBlock;
    }

    // ==================== 合并 ====================

    private void mergeParagraphs(ParagraphBlock left, ParagraphBlock right) {
        left.setText(left.getText() + " " + right.getText());

        // 页码：合并后跨度变大（start 保持，end 取右侧）
        PageRange leftRange = left.getPageRange();
        PageRange rightRange = right.getPageRange();
        if (leftRange != null && rightRange != null && rightRange.getEndPage() != null) {
            leftRange.setEndPage(rightRange.getEndPage());
        } else if (leftRange == null) {
            left.setPageRange(rightRange);
        }

        // 来源 block 追踪
        List<String> ids = new ArrayList<>(left.getSourceBlockIds());
        if (ids.isEmpty() && left.getSourceBlockId() != null) {
            ids.add(left.getSourceBlockId());
        }
        if (right.getSourceBlockId() != null && !ids.contains(right.getSourceBlockId())) {
            ids.add(right.getSourceBlockId());
        }
        left.setSourceBlockIds(ids);

        // 引用标记合并
        if (right.getCitations() != null && !right.getCitations().isEmpty()) {
            left.getCitations().addAll(right.getCitations());
        }
    }

    private String blockIdOf(DocumentBlock block) {
        return block.getId();
    }
}
