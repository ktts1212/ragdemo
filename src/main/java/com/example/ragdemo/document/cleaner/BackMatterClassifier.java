package com.example.ragdemo.document.cleaner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Section;

import lombok.extern.slf4j.Slf4j;

/**
 * Back matter 分类（skill §26）。
 * <p>
 * 至少识别：Acknowledgements / Author contributions / Funding / Competing interests /
 * Conflict of interest / Data availability / Code availability / Ethics /
 * Supplementary material / Additional information / Correspondence / Peer review /
 * Publisher note / License。
 * <p>
 * 分类策略：
 * <ul>
 *   <li>保留有检索价值：Data availability / Code availability / 部分 Supplementary / Ethics</li>
 *   <li>从主 scientific body 移除：Publisher note / License / Reprints / 纯版权文本 /
 *       Peer review / Additional information（编辑性元信息）</li>
 * </ul>
 */
@Slf4j
@Component
public class BackMatterClassifier {

    /** 从主正文移除（无检索价值 / 编辑性元信息） */
    private static final List<String> REMOVE_TITLES = List.of(
            "publisher note", "publisher's note", "license", "licence",
            "reprints", "reprints and permissions", "rights and permissions",
            "peer review", "peer-review", "additional information",
            "author information", "supplementary information",
            "about the authors", "open access", "terms of use");

    /** 明确保留（有检索价值） */
    private static final List<String> KEEP_TITLES = List.of(
            "data availability", "data availability statement",
            "code availability", "availability of data and materials",
            "ethics", "ethics statement", "ethics approval",
            "acknowledgements", "acknowledgments", "author contributions",
            "funding", "competing interests", "conflict of interest",
            "declaration of competing interest", "supplementary material",
            "supplementary materials");

    private static final int TITLE_MATCH_MAX_LENGTH = 80;

    /**
     * @return 被移除的 back matter section 数量
     */
    public int classify(AcademicDocument document) {
        if (document == null || document.getSections() == null) {
            return 0;
        }
        int removed = SectionUtils.removeIf(document.getSections(),
                section -> classify(section.getTitle()) == Category.REMOVE);
        if (removed > 0) {
            log.debug("BackMatterClassifier: 移除 back matter section {} 个", removed);
        }
        return removed;
    }

    /**
     * 对单个 section 标题分类（供测试与审计使用）。
     */
    public Category classify(String sectionTitle) {
        if (sectionTitle == null || sectionTitle.isBlank()) {
            return Category.NOT_BACK_MATTER;
        }
        String normalized = sectionTitle.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (normalized.length() > TITLE_MATCH_MAX_LENGTH) {
            return Category.NOT_BACK_MATTER;
        }
        // 先判 REMOVE（更具体的编辑性元信息），再判 KEEP
        for (String title : REMOVE_TITLES) {
            if (normalized.equals(title) || normalized.startsWith(title)) {
                return Category.REMOVE;
            }
        }
        for (String title : KEEP_TITLES) {
            if (normalized.equals(title) || normalized.startsWith(title)) {
                return Category.KEEP;
            }
        }
        return Category.NOT_BACK_MATTER;
    }

    /**
     * Back matter 分类结果。
     */
    public enum Category {
        /** 有检索价值，保留 */
        KEEP,
        /** 编辑性元信息 / 无检索价值，移除 */
        REMOVE,
        /** 非 back matter */
        NOT_BACK_MATTER
    }

    /**
     * 返回当前文档中命中的 back matter 标题分类（审计用）。
     */
    public List<String> audit(AcademicDocument document) {
        List<String> result = new ArrayList<>();
        if (document == null || document.getSections() == null) {
            return result;
        }
        for (Section section : SectionUtils.flatten(document.getSections())) {
            Category category = classify(section.getTitle());
            if (category != Category.NOT_BACK_MATTER) {
                result.add(category + ": " + section.getTitle());
            }
        }
        return result;
    }
}
