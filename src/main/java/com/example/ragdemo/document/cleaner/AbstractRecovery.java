package com.example.ragdemo.document.cleaner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PageRange;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;

import lombok.extern.slf4j.Slf4j;

/**
 * Abstract 恢复（skill §23 / §5.4）。
 * <p>
 * 三条路径：
 * <ol>
 *   <li>标题为 Abstract/Summary 的 Section</li>
 *   <li>正文内联标记 {@code <i>Abstract</i>—正文}</li>
 *   <li><b>位置式</b>：front matter 中位于 title 之后、Keywords 之前的长段落（无 "Abstract" 关键字）</li>
 * </ol>
 */
@Slf4j
@Component
public class AbstractRecovery {

    private static final List<String> ABSTRACT_TITLES = List.of("abstract", "summary");

    /** 内联 Abstract 标记：Abstract—/Abstract:/Abstract- 后的正文 */
    private static final Pattern INLINE_ABSTRACT_PATTERN =
            Pattern.compile("(?is)^\\s*abstract\\s*[:：—–\\-.]\\s*(.+)$");
    private static final Pattern KEYWORDS_PREFIX =
            Pattern.compile("^(keywords|key\\s*words|index\\s*terms)\\b\\s*[:：]?",
                    Pattern.CASE_INSENSITIVE);

    private static final int MIN_ABSTRACT_LENGTH = 50;
    /** 位置式摘要的最小长度（skill §5.4：>= 200） */
    private static final int POSITIONAL_MIN_LENGTH = 200;
    private static final int POSITIONAL_MAX_PAGE = 2;

    /**
     * @return 是否成功恢复 abstract
     */
    public boolean recover(AcademicDocument document) {
        if (document == null || document.getSections() == null) {
            return false;
        }
        if (document.getAbstractText() != null && !document.getAbstractText().isBlank()) {
            return false;
        }

        // 路径 1：标题式
        Section abstractSection = findAbstractSection(document.getSections());
        if (abstractSection != null) {
            String text = collectParagraphText(abstractSection);
            if (!text.isBlank()) {
                document.setAbstractText(text);
                SectionUtils.removeIf(document.getSections(), section -> section == abstractSection);
                log.debug("AbstractRecovery（section 标题）: length={}", text.length());
                return true;
            }
        }

        // 路径 2：内联标记
        if (recoverFromInlineMarker(document)) {
            return true;
        }

        // 路径 3：位置式（front matter）
        return recoverFromPosition(document);
    }

    // ==================== 路径 2 ====================

    private boolean recoverFromInlineMarker(AcademicDocument document) {
        for (Section section : SectionUtils.flatten(document.getSections())) {
            if (section.getBlocks() == null) {
                continue;
            }
            for (DocumentBlock block : new ArrayList<>(section.getBlocks())) {
                if (!(block instanceof ParagraphBlock paragraph) || paragraph.getText() == null) {
                    continue;
                }
                Matcher matcher = INLINE_ABSTRACT_PATTERN.matcher(paragraph.getText().trim());
                if (!matcher.find()) {
                    continue;
                }
                String abstractText = matcher.group(1).trim();
                if (abstractText.length() < MIN_ABSTRACT_LENGTH) {
                    continue;
                }
                document.setAbstractText(abstractText);
                section.getBlocks().remove(block);
                log.debug("AbstractRecovery（内联标记）: length={}", abstractText.length());
                return true;
            }
        }
        return false;
    }

    // ==================== 路径 3（位置式） ====================

    /**
     * front matter 位置式摘要：位于 title 之后、Keywords 之前的长段落。
     * <p>
     * 高置信条件（skill §5.4）：前 2 页、长度 >= 200、在 Keywords 之前（若存在）、
     * 不像作者行 / 单位 / DOI。命中后写入 abstractText 并从正文移除。
     */
    private boolean recoverFromPosition(AcademicDocument document) {
        List<Section> sections = SectionUtils.flatten(document.getSections());
        List<BlockRef> ordered = orderedBlocks(sections);

        // 找 Keywords 块位置（若存在，摘要须在其之前）
        int keywordsIndex = -1;
        for (int i = 0; i < ordered.size(); i++) {
            String text = textOf(ordered.get(i).block());
            if (text != null && KEYWORDS_PREFIX.matcher(text.trim()).find()) {
                keywordsIndex = i;
                break;
            }
        }

        for (int i = 0; i < ordered.size(); i++) {
            if (keywordsIndex >= 0 && i >= keywordsIndex) {
                break; // 只在 Keywords 之前找
            }
            BlockRef ref = ordered.get(i);
            if (!(ref.block() instanceof ParagraphBlock paragraph) || paragraph.getText() == null) {
                continue;
            }
            String text = paragraph.getText().trim();
            if (text.length() < POSITIONAL_MIN_LENGTH) {
                continue;
            }
            if (!isWithinFirstPages(ref.block(), POSITIONAL_MAX_PAGE)) {
                continue;
            }
            if (looksLikeAuthorLine(text) || looksLikeAffiliation(text) || looksLikeDoiOrFooter(text)) {
                continue;
            }
            document.setAbstractText(text);
            ref.section().getBlocks().remove(ref.block());
            log.debug("AbstractRecovery（位置式）: length={}", text.length());
            return true;
        }
        return false;
    }

    private record BlockRef(Section section, DocumentBlock block) {
    }

    private List<BlockRef> orderedBlocks(List<Section> sections) {
        List<BlockRef> refs = new ArrayList<>();
        for (Section section : sections) {
            if (section.getBlocks() == null) {
                continue;
            }
            for (DocumentBlock block : section.getBlocks()) {
                refs.add(new BlockRef(section, block));
            }
        }
        refs.sort(Comparator.comparingInt(r ->
                r.block().getOrder() == null ? Integer.MAX_VALUE : r.block().getOrder()));
        return refs;
    }

    private boolean isWithinFirstPages(DocumentBlock block, int pages) {
        PageRange range = block.getPageRange();
        if (range == null || range.getStartPage() == null) {
            return true; // 无页码信息时不因此排除
        }
        return range.getStartPage() <= pages;
    }

    private boolean looksLikeAuthorLine(String text) {
        String t = text.trim();
        if (t.length() > 300 || t.endsWith(".")) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        boolean hasSep = t.contains(",") || t.contains("&") || lower.contains(" and ");
        return hasSep && t.length() < 200;
    }

    private boolean looksLikeAffiliation(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("university") || lower.contains("institute")
                || lower.contains("department of") || lower.contains("school of");
    }

    private boolean looksLikeDoiOrFooter(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("doi.org") || lower.startsWith("doi:")
                || lower.contains("all rights reserved") || lower.contains("correspondence");
    }

    // ==================== 工具 ====================

    private Section findAbstractSection(List<Section> sections) {
        for (Section section : SectionUtils.flatten(sections)) {
            String title = section.getTitle();
            if (title != null && ABSTRACT_TITLES.contains(title.toLowerCase(Locale.ROOT).trim())) {
                return section;
            }
        }
        return null;
    }

    private String collectParagraphText(Section section) {
        List<String> parts = new ArrayList<>();
        if (section.getBlocks() != null) {
            for (DocumentBlock block : section.getBlocks()) {
                if (block instanceof ParagraphBlock paragraph
                        && paragraph.getText() != null && !paragraph.getText().isBlank()) {
                    parts.add(paragraph.getText().trim());
                }
            }
        }
        return String.join("\n\n", parts);
    }

    private String textOf(DocumentBlock block) {
        return block instanceof ParagraphBlock p ? p.getText() : null;
    }
}
