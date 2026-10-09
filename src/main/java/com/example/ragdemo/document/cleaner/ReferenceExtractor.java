package com.example.ragdemo.document.cleaner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PartialDate;
import com.example.ragdemo.document.domain.Reference;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.ListBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;

import lombok.extern.slf4j.Slf4j;

/**
 * References 提取（skill §25）。
 * <p>
 * Marker 的 References 有多种形态，绝不能只看 block_type == Reference：
 * <ol>
 *   <li><b>路径 1</b>：存在标题为 References/Bibliography 的 Section → 从其 List/Paragraph 提取</li>
 *   <li><b>路径 2（真实语料必需）</b>：Marker 常把 REFERENCES 标题识别为空（title 为空），
 *       参考文献落在 ListGroup → ListBlock 中。此时按<b>内容特征</b>识别：
 *       条目数 ≥ 3 且多数条目像参考文献（含年份 / et al / DOI / 编号）</li>
 * </ol>
 * 目标：正文 Sections 中不再包含 References 全文，{@code AcademicDocument.references} 被填充。
 */
@Slf4j
@Component
public class ReferenceExtractor {

    private static final List<String> REFERENCE_TITLES = List.of(
            "references", "bibliography", "literature cited", "works cited");

    private static final Pattern LEADING_NUMBER_PATTERN =
            Pattern.compile("^\\s*(?:\\[(\\d+)\\]|\\((\\d+)\\)|(\\d+)[.)])\\s*");
    private static final Pattern DOI_PATTERN =
            Pattern.compile("\\b(10\\.\\d{4,9}/[-._;()/:A-Za-z0-9]+)\\b");
    private static final Pattern YEAR_PATTERN =
            Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final Pattern ET_AL_PATTERN =
            Pattern.compile("\\bet\\s+al\\b", Pattern.CASE_INSENSITIVE);

    /** reference-like 列表判定阈值 */
    private static final int MIN_LIST_ITEMS = 3;
    private static final int MIN_ITEM_LENGTH = 25;

    private final ReferenceItemSplitter itemSplitter;

    public ReferenceExtractor(ReferenceItemSplitter itemSplitter) {
        this.itemSplitter = itemSplitter;
    }

    /**
     * @return 提取到的 reference 数量
     */
    public int extract(AcademicDocument document) {
        if (document == null || document.getSections() == null) {
            return 0;
        }

        // 路径 1：标题明确的 References Section
        Section refSection = findReferencesSection(document.getSections());
        if (refSection != null) {
            List<Reference> references = toReferences(collectCandidates(refSection));
            if (!references.isEmpty()) {
                document.setReferences(references);
                SectionUtils.removeIf(document.getSections(), section -> section == refSection);
                log.debug("ReferenceExtractor（按 section 标题）: 提取 {} 条", references.size());
                return references.size();
            }
        }

        // 路径 2：按内容特征识别 reference-like ListBlock（真实语料主路径）
        return extractFromReferenceLikeList(document);
    }

    private int extractFromReferenceLikeList(AcademicDocument document) {
        for (Section section : SectionUtils.flatten(document.getSections())) {
            if (section.getBlocks() == null) {
                continue;
            }
            for (DocumentBlock block : new ArrayList<>(section.getBlocks())) {
                if (!(block instanceof ListBlock list) || !isReferenceLikeList(list)) {
                    continue;
                }
                List<String> refItems = new ArrayList<>();
                for (String item : list.getItems()) {
                    if (looksLikeReference(item)) {
                        refItems.add(item);
                    }
                }
                List<Reference> references = toReferences(refItems);
                if (references.isEmpty()) {
                    continue;
                }
                document.setReferences(references);

                // 从列表移除已提取的参考文献；非参考文献条目（如续接正文）保留在正文
                list.getItems().removeIf(this::looksLikeReference);
                if (list.getItems().isEmpty()) {
                    section.getBlocks().remove(block);
                }
                log.debug("ReferenceExtractor（按内容特征）: 提取 {} 条", references.size());
                return references.size();
            }
        }
        return 0;
    }

    // ==================== 判定 ====================

    private Section findReferencesSection(List<Section> sections) {
        for (Section section : SectionUtils.flatten(sections)) {
            String title = section.getTitle();
            if (title != null && REFERENCE_TITLES.contains(title.toLowerCase(Locale.ROOT).trim())) {
                return section;
            }
        }
        return null;
    }

    /**
     * 判定 ListBlock 是否"像参考文献列表"：条目数达标且多数条目具备参考文献特征。
     */
    private boolean isReferenceLikeList(ListBlock list) {
        if (list.getItems() == null || list.getItems().size() < MIN_LIST_ITEMS) {
            return false;
        }
        long refLike = list.getItems().stream().filter(this::looksLikeReference).count();
        return refLike >= Math.max(2, list.getItems().size() / 2);
    }

    /**
     * 单条文本是否像参考文献：足够长，且含年份 / et al / DOI / 编号。
     */
    private boolean looksLikeReference(String text) {
        if (text == null || text.trim().length() < MIN_ITEM_LENGTH) {
            return false;
        }
        return YEAR_PATTERN.matcher(text).find()
                || ET_AL_PATTERN.matcher(text).find()
                || DOI_PATTERN.matcher(text).find()
                || LEADING_NUMBER_PATTERN.matcher(text).find();
    }

    // ==================== 构建 ====================

    private List<String> collectCandidates(Section section) {
        List<String> candidates = new ArrayList<>();
        if (section.getBlocks() == null) {
            return candidates;
        }
        for (DocumentBlock block : section.getBlocks()) {
            if (block instanceof ListBlock list && list.getItems() != null) {
                for (String item : list.getItems()) {
                    if (item != null && !item.isBlank()) {
                        candidates.add(item.trim());
                    }
                }
            } else if (block instanceof ParagraphBlock paragraph
                    && paragraph.getText() != null && !paragraph.getText().isBlank()) {
                candidates.add(paragraph.getText().trim());
            }
        }
        return candidates;
    }

    private List<Reference> toReferences(List<String> candidates) {
        List<Reference> references = new ArrayList<>();
        int index = 1;
        for (String candidate : candidates) {
            // skill §5.6：对"疑似包含多条 reference 的单个 item"按连续编号拆分
            for (String part : itemSplitter.split(candidate)) {
                Reference reference = toReference(part, index);
                if (reference != null) {
                    references.add(reference);
                    index++;
                }
            }
        }
        return references;
    }

    private Reference toReference(String rawCandidate, int fallbackIndex) {
        String rawText = rawCandidate.trim();
        if (rawText.isBlank()) {
            return null;
        }

        Integer numbering = null;
        Matcher numberMatcher = LEADING_NUMBER_PATTERN.matcher(rawText);
        String withoutNumber = rawText;
        if (numberMatcher.find()) {
            for (int g = 1; g <= 3; g++) {
                if (numberMatcher.group(g) != null) {
                    numbering = Integer.parseInt(numberMatcher.group(g));
                    break;
                }
            }
            withoutNumber = rawText.substring(numberMatcher.end()).trim();
        }
        if (withoutNumber.isBlank()) {
            return null;
        }

        String doi = null;
        Matcher doiMatcher = DOI_PATTERN.matcher(rawText);
        if (doiMatcher.find()) {
            doi = doiMatcher.group(1);
        }

        Integer year = null;
        Matcher yearMatcher = YEAR_PATTERN.matcher(rawText);
        if (yearMatcher.find()) {
            year = Integer.parseInt(yearMatcher.group());
        }

        String id = "ref-" + (numbering != null ? numbering : fallbackIndex);

        return Reference.builder()
                .id(id)
                .rawText(rawText)
                .title(null) // 不猜测标题（保守）
                .authors(new ArrayList<>())
                .publicationDate(year != null
                        ? PartialDate.builder().year(year).rawText(rawText).build()
                        : null)
                .doi(doi)
                .build();
    }
}
