package com.example.ragdemo.document.cleaner;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PageRange;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FootnoteBlock;
import com.example.ragdemo.document.domain.block.NoiseBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;

/**
 * Front matter 预扫描（skill §4/§5.1）。
 * <p>
 * 在 {@link HeaderFooterCleaner} 删除 NoiseBlock <b>之前</b>运行，只从前 1~2 页
 * 收集候选：DOI / 标题 / 作者行 / 单位 / 关键词 / 期刊。
 * <p>
 * <b>只观察，不删除</b>：候选由 {@link FrontMatterCleaner} 决定是否写入 metadata。
 */
@Component
public class FrontMatterPreScan {

    /** DOI：10.xxxx/...（合法字符不破坏，尾部标点单独剥离） */
    private static final Pattern DOI_PATTERN =
            Pattern.compile("10\\.\\d{4,9}/[^\\s\"<>]+");
    /** 带 doi.org / doi: 标记的文本（提升置信度） */
    private static final Pattern DOI_MARKER =
            Pattern.compile("doi\\.org/|doi\\s*:|\\bDOI\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEYWORDS_PREFIX =
            Pattern.compile("^(keywords|key\\s*words|index\\s*terms)\\b\\s*[:：]?\\s*",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern JOURNAL_PATTERN =
            Pattern.compile("\\b(Scientific Reports|Nature\\s+\\w+|IEEE\\S*|PLOS\\s+\\w+|arXiv)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final int PRESCAN_PAGES = 2;
    private static final int AUTHOR_LINE_MAX_LEN = 300;

    /**
     * 扫描文档前 1~2 页，返回候选上下文。
     */
    public FrontMatterContext scan(AcademicDocument document) {
        FrontMatterContext ctx = new FrontMatterContext();
        if (document == null || document.getSections() == null) {
            return ctx;
        }

        // Pass 1：前 1~2 页（含 NoiseBlock / Footnote，此时尚未被删）
        for (Section section : SectionUtils.flatten(document.getSections())) {
            if (section.getBlocks() == null) {
                continue;
            }
            for (DocumentBlock block : section.getBlocks()) {
                if (!isWithinFirstPages(block, PRESCAN_PAGES)) {
                    continue;
                }
                collect(block, ctx, false);
            }
        }
        ctx.setMaxScannedPage(PRESCAN_PAGES);

        // Pass 2（兜底）：全文找带 doi.org / doi: 标记的 DOI。
        // 优先级：含 "supplementary" 的块（论文自身 DOI 常出现在 Supplementary Information 声明）
        //         > 其他带标记的块（可能是数据集/引用 DOI）
        if (!ctx.hasDoi()) {
            String supplementaryDoi = null;
            String firstMarkedDoi = null;
            outer:
            for (Section section : SectionUtils.flatten(document.getSections())) {
                if (section.getBlocks() == null) {
                    continue;
                }
                for (DocumentBlock block : section.getBlocks()) {
                    String text = textOf(block);
                    if (text == null || !DOI_MARKER.matcher(text).find()) {
                        continue;
                    }
                    String doi = firstDoi(text);
                    if (doi == null) {
                        continue;
                    }
                    if (text.toLowerCase(Locale.ROOT).contains("supplementary")) {
                        supplementaryDoi = doi;
                        break outer;
                    }
                    if (firstMarkedDoi == null) {
                        firstMarkedDoi = doi;
                    }
                }
            }
            ctx.setDoi(supplementaryDoi != null ? supplementaryDoi : firstMarkedDoi);
        }
        return ctx;
    }

    private void collect(DocumentBlock block, FrontMatterContext ctx, boolean allowGlobalDoi) {
        String text = textOf(block);
        if (text == null || text.isBlank()) {
            return;
        }

        // DOI：前 2 页直接取；全局仅在带 doi.org/doi: 标记时取（降低误命中 reference DOI 的风险）
        if (!ctx.hasDoi() && (!allowGlobalDoi || DOI_MARKER.matcher(text).find())) {
            String doi = firstDoi(text);
            if (doi != null) {
                ctx.setDoi(doi);
            }
        }

        // 关键词
        if (KEYWORDS_PREFIX.matcher(text.trim()).find()) {
            ctx.getKeywordCandidates().add(text.trim());
        }

        // 期刊
        if (ctx.getJournal() == null) {
            Matcher jm = JOURNAL_PATTERN.matcher(text);
            if (jm.find()) {
                ctx.setJournal(jm.group(1));
            }
        }

        // 单位 / 作者行
        if (block instanceof FootnoteBlock) {
            ctx.getAffiliationCandidates().add(text.trim());
        } else if (block instanceof ParagraphBlock) {
            if (looksLikeAuthorLine(text)) {
                ctx.getAuthorLineCandidates().add(text.trim());
            } else if (looksLikeAffiliation(text)) {
                ctx.getAffiliationCandidates().add(text.trim());
            }
        }
    }

    /**
     * 标题候选：取"首个非空、非纯噪声 section"的标题。
     * <p>
     * 需跳过两类 section：
     * <ul>
     *   <li>无 block 的（空壳）</li>
     *   <li>仅含 {@link NoiseBlock} 的（如开头的 PageHeader 容器）</li>
     * </ul>
     * 若定位到的 section 无标题（标题在首段），则不设候选，由 FrontMatterCleaner 走首段回退。
     */
    public void fillTitleCandidate(AcademicDocument document, FrontMatterContext ctx) {
        for (Section section : document.getSections()) {
            if (section.getBlocks() == null || section.getBlocks().isEmpty()) {
                continue;
            }
            if (isNoiseOnly(section)) {
                continue;
            }
            if (section.getTitle() != null && !section.getTitle().isBlank()) {
                ctx.setCandidateTitle(section.getTitle());
            }
            return; // 只看首个"非空非噪声" section
        }
    }

    private boolean isNoiseOnly(Section section) {
        for (DocumentBlock block : section.getBlocks()) {
            if (!(block instanceof NoiseBlock)) {
                return false;
            }
        }
        return true;
    }

    private boolean isWithinFirstPages(DocumentBlock block, int pages) {
        PageRange range = block.getPageRange();
        if (range == null || range.getStartPage() == null) {
            return false;
        }
        return range.getStartPage() <= pages;
    }

    private String textOf(DocumentBlock block) {
        if (block instanceof ParagraphBlock p) {
            return p.getText();
        }
        if (block instanceof FootnoteBlock f) {
            return f.getText();
        }
        if (block instanceof NoiseBlock n) {
            return n.getText();
        }
        return null;
    }

    private boolean looksLikeAuthorLine(String text) {
        String t = text.trim();
        if (t.isEmpty() || t.length() > AUTHOR_LINE_MAX_LEN) {
            return false;
        }
        if (t.endsWith(".")) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("keywords") || lower.startsWith("abstract")) {
            return false;
        }
        // 含逗号/&/and，且首字母大写（姓名风格）
        boolean hasSep = t.contains(",") || t.contains("&") || lower.contains(" and ");
        boolean hasUpper = t.chars().anyMatch(Character::isUpperCase);
        return hasSep && hasUpper;
    }

    private boolean looksLikeAffiliation(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("university") || lower.contains("institute")
                || lower.contains("department") || lower.contains("school of")
                || lower.contains("laboratory") || lower.contains("hospital");
    }

    /** 提取首个 DOI 并剥离尾部标点 */
    private String firstDoi(String text) {
        Matcher m = DOI_PATTERN.matcher(text);
        if (!m.find()) {
            return null;
        }
        String doi = m.group();
        int end = doi.length();
        while (end > 0 && ".,;)]}\"'".indexOf(doi.charAt(end - 1)) >= 0) {
            end--;
        }
        return end > 0 ? doi.substring(0, end) : null;
    }
}
