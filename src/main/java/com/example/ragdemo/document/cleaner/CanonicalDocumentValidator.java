package com.example.ragdemo.document.cleaner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PageRange;
import com.example.ragdemo.document.domain.PaperMetadata;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.CaptionBlock;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.NoiseBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;

import lombok.extern.slf4j.Slf4j;

/**
 * Canonical AcademicDocument 校验（skill §28）+ 质量度量（skill §29）。
 * <p>
 * 产出 errors（硬问题）/ warnings（软问题）/ metrics（结构指标），
 * 避免只返回 "parse success" 这种无信息量结果。
 */
@Slf4j
@Component
public class CanonicalDocumentValidator {

    private static final List<String> REFERENCE_TITLES = List.of(
            "references", "bibliography", "literature cited", "works cited");

    public ValidationResult validate(AcademicDocument document, int repairCount) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (document == null) {
            errors.add("document 为 null");
            return new ValidationResult(errors, warnings,
                    emptyMetrics(repairCount));
        }

        // documentId
        if (isBlank(document.getDocumentId())) {
            errors.add("documentId 为空");
        }

        // title（允许 warning）
        PaperMetadata metadata = document.getMetadata();
        if (metadata == null || isBlank(metadata.getTitle())) {
            warnings.add("title 为空");
        }

        // sections
        List<Section> allSections = SectionUtils.flatten(document.getSections());
        if (allSections.isEmpty()) {
            errors.add("sections 为空");
        }

        // section title 不应全部为空
        if (!allSections.isEmpty()) {
            boolean anyNamed = allSections.stream().anyMatch(s -> !isBlank(s.getTitle()));
            if (!anyNamed) {
                warnings.add("所有 section title 均为空");
            }
        }

        // blockIds 不重复 + page 合法
        validateBlocks(allSections, warnings);

        // References 不应全部滞留正文
        boolean referencesStuckInBody = allSections.stream()
                .anyMatch(s -> s.getTitle() != null
                        && REFERENCE_TITLES.contains(s.getTitle().toLowerCase(Locale.ROOT).trim()));
        if (referencesStuckInBody) {
            warnings.add("References 仍滞留在正文 sections（ReferenceExtractor 未命中）");
        }

        DocumentQualityMetrics metrics = computeMetrics(document, allSections, repairCount);

        if (metrics.headerFooterRemaining() > 0) {
            warnings.add("仍有 header/footer 残留: " + metrics.headerFooterRemaining());
        }
        if (metrics.base64RemainingCount() > 0) {
            errors.add("领域模型中仍残留 base64 图片: " + metrics.base64RemainingCount());
        }
        if (metrics.emptySectionCount() > 0) {
            warnings.add("存在空 section: " + metrics.emptySectionCount());
        }
        // ===== skill §8 收尾检查（均只作 warning）=====
        if (metrics.orphanCaptionCount() > 0) {
            warnings.add("存在未关联的 Caption: " + metrics.orphanCaptionCount());
        }
        if (metrics.frontMatterDuplicateCount() > 0) {
            warnings.add("正文仍残留 front matter 重复块(如 Keywords 行): "
                    + metrics.frontMatterDuplicateCount());
        }
        if (metrics.levelPathMismatchCount() > 0) {
            warnings.add("level 与 path depth 不一致的 section: " + metrics.levelPathMismatchCount());
        }
        if (metrics.duplicateAffiliationCount() > 0) {
            warnings.add("affiliation 重复: " + metrics.duplicateAffiliationCount());
        }
        if (metrics.rootTitleMismatch()) {
            warnings.add("root section 标题与 metadata.title 不一致");
        }
        // UNNAMED_NONEMPTY_SECTION：null-title 但非空（可能是合法正文容器，仅提示）
        long unnamedNonEmpty = allSections.stream()
                .filter(s -> isBlank(s.getTitle()) && !SectionUtils.isEmpty(s)).count();
        if (unnamedNonEmpty > 0) {
            warnings.add("UNNAMED_NONEMPTY_SECTION: " + unnamedNonEmpty);
        }

        // ===== skill §9 结构修复复核指标（均只作 warning，不 hard fail）=====
        if (metrics.majorHeadingNestedUnderMajorHeadingCount() > 0) {
            warnings.add("MAJOR_HEADING_NESTED_UNDER_MAJOR_HEADING: "
                    + metrics.majorHeadingNestedUnderMajorHeadingCount()
                    + "（主章节被嵌套在另一主章节下，会把错误 sectionPath 注入 embedding）");
        }
        if (metrics.sameDepthHeadingParentChildConflictCount() > 0) {
            warnings.add("SAME_DEPTH_HEADING_PARENT_CHILD_CONFLICT: "
                    + metrics.sameDepthHeadingParentChildConflictCount());
        }
        if (metrics.floatReferenceSectionMismatchCount() > 0) {
            warnings.add("FLOAT_REFERENCE_SECTION_MISMATCH: "
                    + metrics.floatReferenceSectionMismatchCount()
                    + "（正文引用与 Figure/Table 归属仍不一致）");
        }
        if (metrics.unresolvedFloatReferenceCount() > 0) {
            warnings.add("UNRESOLVED_FLOAT_REFERENCE: " + metrics.unresolvedFloatReferenceCount());
        }
        if (metrics.sectionPathMismatchCount() > 0) {
            warnings.add("SECTION_PATH_MISMATCH: " + metrics.sectionPathMismatchCount());
        }
        emptyAnchorSectionTitles(document)
                .forEach(title -> warnings.add("EMPTY_ANCHOR_SECTION_KEPT: " + title));

        log.info("Canonical 校验: valid={}, errors={}, warnings={}, metrics={}",
                errors.isEmpty(), errors.size(), warnings.size(), metrics);

        return new ValidationResult(errors, warnings, metrics);
    }

    // ==================== 校验 ====================

    private void validateBlocks(List<Section> sections, List<String> warnings) {
        Set<String> seenIds = new HashSet<>();
        Set<String> duplicateIds = new HashSet<>();
        for (Section section : sections) {
            if (section.getBlocks() == null) {
                continue;
            }
            for (DocumentBlock block : section.getBlocks()) {
                // blockIds 不重复
                String id = block.getId();
                if (id != null && !id.isBlank() && !seenIds.add(id)) {
                    duplicateIds.add(id);
                }
                // page 合法
                PageRange range = block.getPageRange();
                if (range != null) {
                    if (range.getStartPage() != null && range.getStartPage() < 1) {
                        warnings.add("block page 非法（start<1）: " + id);
                    }
                    if (range.getStartPage() != null && range.getEndPage() != null
                            && range.getStartPage() > range.getEndPage()) {
                        warnings.add("block page 非法（start>end）: " + id);
                    }
                }
                // Figure imageKey 不应包含 base64
                if (block instanceof FigureBlock figure && containsBase64(figure.getImageKey())) {
                    warnings.add("Figure imageKey 含 base64: " + id);
                }
                if (block instanceof TableBlock table && containsBase64(table.getHtml())) {
                    warnings.add("Table html 含 base64: " + id);
                }
            }
        }
        if (!duplicateIds.isEmpty()) {
            warnings.add("blockId 重复: " + duplicateIds);
        }
    }

    private boolean containsBase64(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("base64") || lower.startsWith("data:image");
    }

    // ==================== 度量 ====================

    private DocumentQualityMetrics computeMetrics(AcademicDocument document,
            List<Section> allSections, int repairCount) {
        int paragraphCount = SectionUtils.countBlocks(document.getSections(), ParagraphBlock.class);
        int figureCount = SectionUtils.countBlocks(document.getSections(), FigureBlock.class);
        int tableCount = SectionUtils.countBlocks(document.getSections(), TableBlock.class);
        int headerFooterRemaining = SectionUtils.countBlocks(document.getSections(), NoiseBlock.class);
        int unnamedSectionCount = (int) allSections.stream().filter(s -> isBlank(s.getTitle())).count();
        int emptySectionCount = (int) allSections.stream().filter(SectionUtils::isEmpty).count();
        int base64RemainingCount = countBase64Remaining(allSections);
        int crossPageMergeCount = countCrossPageParagraphs(allSections);

        // ===== skill §8 收尾检查指标 =====
        int frontMatterDuplicateCount = countFrontMatterDuplicates(allSections);
        int orphanCaptionCount = SectionUtils.countBlocks(document.getSections(), CaptionBlock.class);
        int levelPathMismatchCount = countLevelPathMismatch(allSections);
        int duplicateAffiliationCount = countDuplicateAffiliations(document);
        boolean rootTitleMismatch = isRootTitleMismatch(document);

        // ===== skill §9 结构修复复核指标（由 Validator 独立重新计算，而非复用修复组件的计数）=====
        Map<String, Set<String>> floatReferences = referenceSectionsByKey(document);
        int majorHeadingNested = countMajorHeadingNestedUnderMajorHeading(document);
        int sameDepthConflict = countSameDepthHeadingParentChildConflict(document);
        int floatReferenceMismatch = countFloatReferenceSectionMismatch(document, floatReferences);
        int unresolvedFloatReference = countUnresolvedFloatReference(document, floatReferences);
        int sectionPathMismatch = countSectionPathMismatch(allSections);

        return new DocumentQualityMetrics(
                allSections.size(),
                paragraphCount,
                figureCount,
                tableCount,
                document.getReferences() == null ? 0 : document.getReferences().size(),
                headerFooterRemaining,
                unnamedSectionCount,
                emptySectionCount,
                base64RemainingCount,
                crossPageMergeCount,
                repairCount,
                frontMatterDuplicateCount,
                orphanCaptionCount,
                levelPathMismatchCount,
                duplicateAffiliationCount,
                rootTitleMismatch,
                majorHeadingNested,
                sameDepthConflict,
                floatReferenceMismatch,
                unresolvedFloatReference,
                sectionPathMismatch);
    }

    // ==================== skill §9 结构修复复核指标 ====================

    /** 主章节锚点嵌套在另一个主章节锚点之下（例：Methods 挂在 Discussion 下）。 */
    private int countMajorHeadingNestedUnderMajorHeading(AcademicDocument document) {
        int count = 0;
        for (Section parent : SectionUtils.flatten(document.getSections())) {
            if (!MajorSectionAnchors.isMajorAnchor(parent.getTitle())) {
                continue;
            }
            for (Section child : parent.getChildren()) {
                if (MajorSectionAnchors.isMajorAnchor(child.getTitle())) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 标题编号层级 <= 父节点编号层级（编号表明本应同级，却被父子化）。 */
    private int countSameDepthHeadingParentChildConflict(AcademicDocument document) {
        int count = 0;
        for (Section parent : SectionUtils.flatten(document.getSections())) {
            Integer parentDepth = numberingDepth(parent.getTitle());
            if (parentDepth == null) {
                continue;
            }
            for (Section child : parent.getChildren()) {
                Integer childDepth = numberingDepth(child.getTitle());
                if (childDepth != null && childDepth <= parentDepth) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 标题编号深度（实现见 {@link HeadingNumbering}）：{@code "2.1"} → 2 / {@code "A."} → 2 / {@code "III."} → 1。 */
    private Integer numberingDepth(String title) {
        return HeadingNumbering.depth(title);
    }

    /**
     * 修复后仍与正文引用不一致的浮动块数：该 Figure/Table 有正文引用，
     * 但<b>其当前所属 Section 内没有任何引用</b>（说明归属仍不对）。
     */
    private int countFloatReferenceSectionMismatch(AcademicDocument document,
            Map<String, Set<String>> floatReferences) {
        int count = 0;
        for (Section section : SectionUtils.flatten(document.getSections())) {
            for (DocumentBlock block : section.getBlocks()) {
                String key = FloatIdentifiers.keyOf(block);
                if (key == null) {
                    continue;
                }
                Set<String> sections = floatReferences.get(key);
                if (sections == null || sections.isEmpty()) {
                    continue; // 无引用 → 计入 unresolved
                }
                if (!sections.contains(section.getId())) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 有编号但正文完全没有引用的浮动块数（保守不动）。 */
    private int countUnresolvedFloatReference(AcademicDocument document,
            Map<String, Set<String>> floatReferences) {
        int count = 0;
        for (Section section : SectionUtils.flatten(document.getSections())) {
            for (DocumentBlock block : section.getBlocks()) {
                String key = FloatIdentifiers.keyOf(block);
                if (key != null && !floatReferences.containsKey(key)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** {@code TYPE:N} → 出现该引用的 section id 集合。 */
    private Map<String, Set<String>> referenceSectionsByKey(AcademicDocument document) {
        Map<String, Set<String>> result = new HashMap<>();
        for (Section section : SectionUtils.flatten(document.getSections())) {
            for (DocumentBlock block : section.getBlocks()) {
                String text = blockText(block);
                if (isBlank(text)) {
                    continue;
                }
                addReferenceMatches(result, section.getId(), text,
                        FloatIdentifiers.FIGURE_PATTERN, FloatIdentifiers.FIGURE_PREFIX);
                addReferenceMatches(result, section.getId(), text,
                        FloatIdentifiers.TABLE_PATTERN, FloatIdentifiers.TABLE_PREFIX);
            }
        }
        return result;
    }

    private void addReferenceMatches(Map<String, Set<String>> target, String sectionId, String text,
            Pattern pattern, String prefix) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String key = prefix + matcher.group(1).toUpperCase(Locale.ROOT);
            target.computeIfAbsent(key, k -> new HashSet<>()).add(sectionId);
        }
    }

    private String blockText(DocumentBlock block) {
        if (block instanceof ParagraphBlock paragraph) {
            return paragraph.getText();
        }
        if (block instanceof com.example.ragdemo.document.domain.block.ListBlock list) {
            return list.getItems() == null ? null : String.join("\n", list.getItems());
        }
        return null;
    }

    /** path 末元素与 title 不一致的 section 数。 */
    private int countSectionPathMismatch(List<Section> sections) {
        int count = 0;
        for (Section section : sections) {
            if (section.getTitle() == null || section.getPath() == null || section.getPath().isEmpty()) {
                continue;
            }
            String last = section.getPath().get(section.getPath().size() - 1);
            if (!section.getTitle().equals(last)) {
                count++;
            }
        }
        return count;
    }

    /** 被保留的空主章节 section 标题（EmptyContentCleaner 刻意不删，此处告警以便可观测）。 */
    private List<String> emptyAnchorSectionTitles(AcademicDocument document) {
        List<String> titles = new ArrayList<>();
        for (Section section : SectionUtils.flatten(document.getSections())) {
            if (SectionUtils.isEmpty(section) && MajorSectionAnchors.isMajorAnchor(section.getTitle())) {
                titles.add(section.getTitle());
            }
        }
        return titles;
    }

    /** 正文中仍残留的 front matter 重复块（如未消费的 Keywords 行） */
    private int countFrontMatterDuplicates(List<Section> sections) {
        java.util.regex.Pattern keywords = java.util.regex.Pattern.compile(
                "^(keywords|key\\s*words|index\\s*terms)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
        int count = 0;
        for (Section section : sections) {
            if (section.getBlocks() == null) {
                continue;
            }
            for (DocumentBlock block : section.getBlocks()) {
                if (block instanceof ParagraphBlock p && p.getText() != null
                        && keywords.matcher(p.getText().trim()).find()) {
                    count++;
                }
            }
        }
        return count;
    }

    /** level 与 path depth 不一致的 section 数 */
    private int countLevelPathMismatch(List<Section> sections) {
        int count = 0;
        for (Section section : sections) {
            if (section.getTitle() == null || section.getPath() == null) {
                continue;
            }
            int level = section.getLevel() == null ? 1 : section.getLevel();
            if (level != section.getPath().size()) {
                count++;
            }
        }
        return count;
    }

    /** 归一化后重复的 affiliation 数 */
    private int countDuplicateAffiliations(AcademicDocument document) {
        if (document.getMetadata() == null || document.getMetadata().getAffiliations() == null) {
            return 0;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        int dup = 0;
        for (var aff : document.getMetadata().getAffiliations()) {
            String key = ((aff.getInstitution() == null ? "" : aff.getInstitution())
                    + "|" + (aff.getAddress() == null ? "" : aff.getAddress()))
                    .toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
            if (!seen.add(key)) {
                dup++;
            }
        }
        return dup;
    }

    /** root section 标题与 metadata.title 是否不一致 */
    private boolean isRootTitleMismatch(AcademicDocument document) {
        if (document.getMetadata() == null || document.getMetadata().getTitle() == null
                || document.getSections() == null) {
            return false;
        }
        for (Section root : document.getSections()) {
            if (root.getTitle() != null && !root.getTitle().isBlank()) {
                return !root.getTitle().equals(document.getMetadata().getTitle());
            }
        }
        return false;
    }

    private int countBase64Remaining(List<Section> sections) {
        int count = 0;
        for (Section section : sections) {
            if (section.getBlocks() == null) {
                continue;
            }
            for (DocumentBlock block : section.getBlocks()) {
                if (block instanceof FigureBlock figure && containsBase64(figure.getImageKey())) {
                    count++;
                }
                if (block instanceof TableBlock table && containsBase64(table.getHtml())) {
                    count++;
                }
            }
        }
        return count;
    }

    private int countCrossPageParagraphs(List<Section> sections) {
        int count = 0;
        for (Section section : sections) {
            if (section.getBlocks() == null) {
                continue;
            }
            for (DocumentBlock block : section.getBlocks()) {
                if (block instanceof ParagraphBlock paragraph && paragraph.getPageRange() != null) {
                    Integer start = paragraph.getPageRange().getStartPage();
                    Integer end = paragraph.getPageRange().getEndPage();
                    if (start != null && end != null && !start.equals(end)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private DocumentQualityMetrics emptyMetrics(int repairCount) {
        return new DocumentQualityMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, repairCount,
                0, 0, 0, 0, false, 0, 0, 0, 0, 0);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
