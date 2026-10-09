package com.example.ragdemo.document.cleaner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Affiliation;
import com.example.ragdemo.document.domain.Author;
import com.example.ragdemo.document.domain.PaperMetadata;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FootnoteBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;

import lombok.extern.slf4j.Slf4j;

/**
 * Front matter 清洗（skill §22 / §5.3）。
 * <p>
 * 五个内部步骤：A. Title（含尾部噪声清洗） B. Authors C. Affiliations D. Keywords E. DOI。
 * 原则：能可靠结构化的写入 {@link PaperMetadata}，不能可靠结构化的保守标记，<b>不编造</b>。
 * 优先使用 {@link FrontMatterPreScan} 收集的候选（避免 DOI 在 PageFooter 被先删的问题）。
 */
@Slf4j
@Component
public class FrontMatterCleaner {

    private static final Pattern DOI_PATTERN =
            Pattern.compile("\\b(10\\.\\d{4,9}/[-._;()/:A-Za-z0-9]+)\\b");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    /** affiliation 尾部孤立标签（skill §4.3） */
    private static final Pattern TRAILING_CORRESPONDENCE_LABEL = Pattern.compile(
            "(?i)(?:^|\\s)(?:e-?mail|correspondence|corresponding author)\\s*[:：]?\\s*$");
    private static final Pattern KEYWORDS_PREFIX =
            Pattern.compile("^(keywords|key\\s*words|index\\s*terms)\\b\\s*[:：]?\\s*",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTITUTION_PATTERN =
            Pattern.compile("([A-Z][A-Za-z&,\\- ]{2,80}?(?:University|Institute|College|Laboratory|Hospital))");
    private static final Pattern SUP_MARKER =
            Pattern.compile("[0-9*†‡§¶∗]+\\s*$");

    private static final List<String> FRONT_MATTER_NOISE_KEYWORDS = List.of(
            "received:", "accepted:", "published:", "published online",
            "correspondence", "check for updates", "copyright",
            "all rights reserved", "reprints and permission",
            "author information", "reprints", "license",
            "this article is licensed", "creative commons"
    );

    /** 标题尾部高置信噪声 token（仅删除作为末尾独立 token 时） */
    private static final List<String> TITLE_TAIL_NOISE = List.of(
            "OPEN", "ARTICLE", "RESEARCH ARTICLE", "ORIGINAL ARTICLE", "OPEN ACCESS");

    private static final int TITLE_MAX_LENGTH = 300;
    private static final int AUTHOR_NAME_MAX_LEN = 80;

    /** 常见章节名（首个 SectionHeader 若为这些，则不是论文标题） */
    private static final Set<String> COMMON_SECTION_NAMES = Set.of(
            "introduction", "methods", "method", "results", "discussion", "conclusion", "conclusions",
            "background", "related work", "materials and methods", "experiments", "experimental setup",
            "limitations", "references", "abstract", "appendix", "acknowledgements", "acknowledgments",
            "funding", "data availability", "ethics", "supplementary material", "index terms");

    /**
     * @param ctx 预扫描候选（可为 null，则退化为仅扫描当前正文）
     * @return 清洗过程中的 warning
     */
    public List<String> clean(AcademicDocument document, FrontMatterContext ctx) {
        List<String> warnings = new ArrayList<>();
        if (document == null || document.getSections() == null || document.getSections().isEmpty()) {
            return warnings;
        }
        if (document.getMetadata() == null) {
            document.setMetadata(PaperMetadata.builder().build());
        }
        PaperMetadata metadata = document.getMetadata();

        Section frontSection = findFrontMatterSection(document.getSections());
        List<DocumentBlock> blocks = frontSection != null && frontSection.getBlocks() != null
                ? frontSection.getBlocks()
                : java.util.Collections.emptyList();

        // A. Title（含尾部噪声清洗；不依赖 front section 非空）
        extractTitle(metadata, frontSection, ctx, warnings);
        // A2. root section 的 title/path 与 sanitize 后的 metadata.title 保持同步
        syncRootTitle(document, ctx, metadata, warnings);
        // B. Authors
        extractAuthors(metadata, blocks, ctx);
        // C. Affiliations
        extractAffiliations(metadata, blocks, ctx);
        // D. Keywords
        extractKeywords(metadata, blocks, ctx);
        // E. DOI（优先预扫描候选；不依赖 front section 非空）
        extractDoi(metadata, blocks, ctx);

        // 移除纯 front matter 噪声行
        int removed = removeFrontMatterNoise(blocks);
        if (removed > 0) {
            log.debug("FrontMatterCleaner: 移除头部噪声行 {} 个", removed);
        }
        return warnings;
    }

    /** 兼容入口（无预扫描上下文） */
    public List<String> clean(AcademicDocument document) {
        return clean(document, null);
    }

    /**
     * root section 的 title/path 与 sanitize 后的 metadata.title 同步（skill §4.4）。
     * <p>
     * 只替换<b>明确等于旧 root title 的 path segment</b>，不做全局字符串替换。
     */
    private void syncRootTitle(AcademicDocument document, FrontMatterContext ctx,
            PaperMetadata metadata, List<String> warnings) {
        if (metadata.getTitle() == null || ctx == null || ctx.getCandidateTitle() == null) {
            return;
        }
        String oldRoot = ctx.getCandidateTitle();
        String newTitle = metadata.getTitle();
        if (oldRoot.equals(newTitle)) {
            return;
        }
        for (Section root : document.getSections()) {
            if (oldRoot.equals(root.getTitle())) {
                root.setTitle(newTitle);
                fixPathRecursive(root, oldRoot, newTitle);
                warnings.add("ROOT_TITLE_SYNCED: " + oldRoot + " → " + newTitle);
            }
        }
    }

    private void fixPathRecursive(Section section, String oldTitle, String newTitle) {
        if (section.getPath() != null && !section.getPath().isEmpty()) {
            List<String> fixed = new ArrayList<>();
            for (String seg : section.getPath()) {
                fixed.add(oldTitle.equals(seg) ? newTitle : seg);
            }
            section.setPath(fixed);
        }
        if (section.getChildren() != null) {
            for (Section child : section.getChildren()) {
                fixPathRecursive(child, oldTitle, newTitle);
            }
        }
    }

    private Section findFrontMatterSection(List<Section> sections) {
        for (Section section : sections) {
            if (section.getBlocks() != null && !section.getBlocks().isEmpty()) {
                return section;
            }
        }
        return null;
    }

    // ==================== A. Title ====================

    private void extractTitle(PaperMetadata metadata, Section frontSection,
            FrontMatterContext ctx, List<String> warnings) {
        if (metadata.getTitle() != null && !metadata.getTitle().isBlank()) {
            return;
        }
        String candidate = (ctx != null && ctx.getCandidateTitle() != null)
                ? ctx.getCandidateTitle()
                : (frontSection != null ? frontSection.getTitle() : null);

        if (candidate == null) {
            // 情况 B：无 section 标题 → 取首段
            if (frontSection == null || frontSection.getBlocks() == null) {
                return;
            }
            for (DocumentBlock block : frontSection.getBlocks()) {
                if (block instanceof ParagraphBlock paragraph && paragraph.getText() != null
                        && isLikelyTitle(paragraph.getText().trim())) {
                    metadata.setTitle(sanitizePaperTitle(paragraph.getText().trim()));
                    return;
                }
            }
            return;
        }

        String sanitized = sanitizePaperTitle(candidate);
        if (!sanitized.equals(candidate)) {
            warnings.add("标题尾部噪声已清洗: " + candidate + " → " + sanitized);
        }
        if (isLikelyPaperTitle(sanitized)) {
            metadata.setTitle(sanitized);
        }
    }

    /**
     * 清洗标题尾部高置信噪声（skill §5.3A）：仅当噪声词作为标题末尾的独立 token 时删除。
     * 例： "... transformers OPEN" → "... transformers"
     */
    String sanitizePaperTitle(String title) {
        if (title == null) {
            return null;
        }
        String t = title.replaceAll("\\s+", " ").trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String noise : TITLE_TAIL_NOISE) {
                Matcher m = Pattern.compile("(?i)\\s+" + Pattern.quote(noise) + "\\s*$").matcher(t);
                if (m.find()) {
                    t = t.substring(0, m.start()).trim();
                    changed = true;
                }
            }
        }
        return t;
    }

    private boolean isLikelyPaperTitle(String title) {
        if (title == null) {
            return false;
        }
        String t = title.trim();
        if (t.length() < 8 || t.length() > TITLE_MAX_LENGTH) {
            return false;
        }
        if (t.matches("^(?:[IVXLC]+\\.|\\d+[.)]|[A-Z]\\.)\\s+.*")) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (COMMON_SECTION_NAMES.contains(lower)) {
            return false;
        }
        char last = t.charAt(t.length() - 1);
        return last != '.' && last != ',' && last != ';';
    }

    private boolean isLikelyTitle(String text) {
        if (text.isBlank() || text.length() > TITLE_MAX_LENGTH) {
            return false;
        }
        char last = text.charAt(text.length() - 1);
        if (last == '.' || last == ',' || last == ';') {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : FRONT_MATTER_NOISE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return false;
            }
        }
        return true;
    }

    // ==================== B. Authors ====================

    private void extractAuthors(PaperMetadata metadata, List<DocumentBlock> blocks, FrontMatterContext ctx) {
        if (metadata.getAuthors() != null && !metadata.getAuthors().isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        List<DocumentBlock> toConsume = new ArrayList<>();
        if (ctx != null) {
            lines.addAll(ctx.getAuthorLineCandidates());
        }
        // 直接扫描 front section：首个像作者行的段落
        for (DocumentBlock block : blocks) {
            if (block instanceof ParagraphBlock p && p.getText() != null
                    && looksLikeAuthorLine(p.getText())) {
                lines.add(p.getText().trim());
                toConsume.add(block);
                break;
            }
        }
        List<Author> authors = new ArrayList<>();
        // 去重：预扫描与直接扫描可能重复收集同一作者行
        for (String line : new java.util.LinkedHashSet<>(lines)) {
            for (String name : splitAuthorLine(line)) {
                authors.add(Author.builder().fullName(name).build());
            }
        }
        // 识别 → 成功构建 metadata → 再 consume（避免误删）
        if (!authors.isEmpty()) {
            metadata.setAuthors(authors);
            blocks.removeAll(toConsume);
        }
    }

    private boolean looksLikeAuthorLine(String text) {
        String t = text.trim();
        if (t.isEmpty() || t.length() > 300 || t.endsWith(".")) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("keywords") || lower.startsWith("abstract")) {
            return false;
        }
        boolean hasSep = t.contains(",") || t.contains("&") || lower.contains(" and ");
        boolean hasUpper = t.chars().anyMatch(Character::isUpperCase);
        return hasSep && hasUpper;
    }

    /** 把作者行拆成姓名列表（skill §5.3B：第一版只保存 fullName） */
    List<String> splitAuthorLine(String line) {
        List<String> names = new ArrayList<>();
        String[] parts = line.split("\\s*(?:,|&|;|\\band\\b)\\s*");
        for (String part : parts) {
            String name = SUP_MARKER.matcher(part).replaceFirst("")
                    .replaceAll("\\s+", " ").trim();
            if (!name.isBlank() && name.length() <= AUTHOR_NAME_MAX_LEN) {
                names.add(name);
            }
        }
        return names;
    }

    // ==================== C. Affiliations ====================

    private void extractAffiliations(PaperMetadata metadata, List<DocumentBlock> blocks,
            FrontMatterContext ctx) {
        if (metadata.getAffiliations() != null && !metadata.getAffiliations().isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        List<DocumentBlock> toConsume = new ArrayList<>();
        if (ctx != null) {
            lines.addAll(ctx.getAffiliationCandidates());
        }
        for (DocumentBlock block : blocks) {
            if (block instanceof FootnoteBlock f && f.getText() != null && !f.getText().isBlank()) {
                lines.add(f.getText().trim());
                toConsume.add(block);
            } else if (block instanceof ParagraphBlock p && p.getText() != null
                    && looksLikeAffiliation(p.getText())) {
                lines.add(p.getText().trim());
            }
        }

        List<Affiliation> affiliations = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String line : lines) {
            for (String part : splitAffiliationLine(line)) {
                String cleaned = stripCorrespondenceLabels(part);
                String key = cleaned.toLowerCase(Locale.ROOT).replaceAll("^\\d{1,2}\\s*", "").trim();
                if (key.isBlank() || !seen.add(key)) {
                    continue; // 去重：同一 affiliation 不重复构造
                }
                Matcher m = INSTITUTION_PATTERN.matcher(cleaned);
                String institution = m.find() ? m.group(1).trim() : null;
                String address = cleaned.length() > 300 ? cleaned.substring(0, 300) : cleaned;
                affiliations.add(Affiliation.builder()
                        .institution(institution)
                        .address(address)
                        .build());
            }
        }
        // 识别 → 成功构建 metadata → 再 consume（移除 affiliation footnote）
        if (!affiliations.isEmpty()) {
            metadata.setAffiliations(affiliations);
            blocks.removeAll(toConsume);
        }
    }

    /**
     * 清理 affiliation 文本（skill §4.3）：
     * <ol>
     *   <li>删除完整 email 地址段</li>
     *   <li>循环删除尾部孤立标签（{@code email:} / {@code e-mail:} / {@code correspondence:} /
     *       {@code corresponding author:}）—— 即使后面没有真实地址也不残留</li>
     *   <li>折叠空白</li>
     * </ol>
     * 只作用于 affiliation/correspondence 文本，不碰正文普通出现的 email 单词。
     */
    String stripCorrespondenceLabels(String text) {
        if (text == null) {
            return "";
        }
        String t = EMAIL_PATTERN.matcher(text).replaceAll(" ");
        boolean changed = true;
        while (changed) {
            changed = false;
            Matcher m = TRAILING_CORRESPONDENCE_LABEL.matcher(t);
            if (m.find()) {
                t = t.substring(0, m.start());
                changed = true;
            }
        }
        return t.replaceAll("\\s+", " ").trim();
    }

    /**
     * 按编号标记拆分一个 Footnote 中的多个单位（skill §4.3）。
     * <p>
     * 只把"行首 或 紧跟句点后的 1~2 位数字 + 大写字母"视为单位标记，
     * 避免把 "K1S" / "5B6" 这类邮编误当标记。
     */
    List<String> splitAffiliationLine(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }
        String text = EMAIL_PATTERN.matcher(line).replaceAll(" ").replaceAll("\\s+", " ").trim();
        List<Integer> markerStarts = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                continue;
            }
            int j = i - 1;
            while (j >= 0 && Character.isWhitespace(text.charAt(j))) {
                j--;
            }
            boolean okBefore = j < 0 || text.charAt(j) == '.';
            int k = i;
            while (k < text.length() && Character.isDigit(text.charAt(k))) {
                k++;
            }
            boolean okAfter = k < text.length() && Character.isUpperCase(text.charAt(k));
            if (okBefore && okAfter) {
                markerStarts.add(i);
                i = k - 1;
            }
        }
        if (markerStarts.size() < 2) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        for (int idx = 0; idx < markerStarts.size(); idx++) {
            int start = markerStarts.get(idx);
            int end = idx + 1 < markerStarts.size() ? markerStarts.get(idx + 1) : text.length();
            String part = text.substring(start, end).replaceAll("^\\d{1,2}\\s*", "").trim();
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private boolean looksLikeAffiliation(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("university") || lower.contains("institute")
                || lower.contains("department") || lower.contains("school of")
                || lower.contains("laboratory") || lower.contains("hospital");
    }

    // ==================== D. Keywords ====================

    private void extractKeywords(PaperMetadata metadata, List<DocumentBlock> blocks, FrontMatterContext ctx) {
        if (metadata.getKeywords() != null && !metadata.getKeywords().isEmpty()) {
            return;
        }
        List<String> rawLines = new ArrayList<>();
        List<DocumentBlock> toConsume = new ArrayList<>();
        if (ctx != null) {
            rawLines.addAll(ctx.getKeywordCandidates());
        }
        for (DocumentBlock block : blocks) {
            if (block instanceof ParagraphBlock p && p.getText() != null
                    && KEYWORDS_PREFIX.matcher(p.getText().trim()).find()) {
                rawLines.add(p.getText().trim());
                toConsume.add(block);
            }
        }
        List<String> keywords = new ArrayList<>();
        for (String raw : rawLines) {
            String stripped = KEYWORDS_PREFIX.matcher(raw).replaceFirst("");
            for (String kw : stripped.split("[,;]")) {
                String k = kw.trim();
                if (!k.isBlank() && k.length() <= 80) {
                    keywords.add(k);
                }
            }
        }
        // 去重（预扫描与直接扫描可能重复收集同一关键词块）
        List<String> deduped = new ArrayList<>(new java.util.LinkedHashSet<>(keywords));
        // 识别 → 成功构建 metadata → 再 consume
        if (!deduped.isEmpty()) {
            metadata.setKeywords(deduped);
            blocks.removeAll(toConsume);
        }
    }

    // ==================== E. DOI ====================

    private void extractDoi(PaperMetadata metadata, List<DocumentBlock> blocks, FrontMatterContext ctx) {
        if (metadata.getDoi() != null && !metadata.getDoi().isBlank()) {
            return; // 不覆盖
        }
        if (ctx != null && ctx.hasDoi()) {
            metadata.setDoi(ctx.getDoi());
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (DocumentBlock block : blocks) {
            if (block instanceof ParagraphBlock paragraph && paragraph.getText() != null) {
                sb.append(paragraph.getText()).append('\n');
            }
        }
        Matcher m = DOI_PATTERN.matcher(sb.toString());
        if (m.find()) {
            metadata.setDoi(m.group(1));
        }
    }

    private int removeFrontMatterNoise(List<DocumentBlock> blocks) {
        int before = blocks.size();
        blocks.removeIf(block -> {
            if (block instanceof ParagraphBlock paragraph && paragraph.getText() != null) {
                String lower = paragraph.getText().toLowerCase(Locale.ROOT).trim();
                return FRONT_MATTER_NOISE_KEYWORDS.stream().anyMatch(lower::contains);
            }
            return false;
        });
        return before - blocks.size();
    }
}
