package com.example.ragdemo.document.cleaner;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Section;

/**
 * Section 层级规范化（skill §27 / §5.8）。
 * <p>
 * 第一阶段：修标题文本（trim、空白归一、newline→space、去纯编号尾部、去空白 title）。
 * 第二阶段：只做<b>保守</b>纠偏与异常检测，<b>不发明 section</b>（skill 明确禁止）。
 * <p>
 * 输出 warning：{@code LEVEL_JUMP} / {@code NULL_TITLE_WITH_CHILDREN} /
 * {@code SECTION_PATH_CONFLICT} / {@code DUPLICATE_SECTION_TITLE_ADJACENT} / {@code ORPHAN_SUBSECTION}。
 */
@Component
public class SectionHierarchyNormalizer {

    /** 纯编号尾部（如 "Methods 1." / "Results 2.1"） */
    private static final Pattern PURE_NUMBERING_TAIL =
            Pattern.compile("[\\s.\\-–—]*\\d+(\\.\\d+)*[.)]?\\s*$");

    /**
     * @return 归一过程中产生的 warning
     */
    public List<String> normalize(AcademicDocument document) {
        List<String> warnings = new ArrayList<>();
        if (document == null || document.getSections() == null) {
            return warnings;
        }
        List<Section> all = SectionUtils.flatten(document.getSections());

        for (Section section : all) {
            normalizeTitle(section, warnings);
            if (section.getPath() == null) {
                section.setPath(new ArrayList<>());
            }
            if (section.getLevel() == null || section.getLevel() < 1) {
                section.setLevel(1);
            }
            detectPathConflict(section, warnings);
            detectNullTitleWithChildren(section, warnings);
            detectLevelJump(section, warnings);
            detectOrphanSubsection(section, warnings);
        }
        detectAdjacentDuplicates(all, warnings);

        // 强制 level 与 path depth 一致（含 null-title section，skill §5.3/§5.6）
        enforceLevelPathConsistency(document.getSections(), 0);

        // 第二阶段：保守层级纠偏 + 一致性告警
        repairRootLevelDrop(document, warnings);
        detectLevelPathMismatch(document, warnings);
        return warnings;
    }

    /**
     * 强制 level 与真实层级一致（skill §5.3 / §5.6）：
     * <ul>
     *   <li>有标题且 path 非空 → {@code level = path.size()}</li>
     *   <li>无标题（或 path 为空）容器 → {@code level = parent.level + 1}（优先父子真实关系）</li>
     * </ul>
     * 不修改 title，不删除正文。
     */
    private void enforceLevelPathConsistency(List<Section> sections, int parentLevel) {
        if (sections == null) {
            return;
        }
        for (Section section : sections) {
            if (section.getTitle() != null && section.getPath() != null
                    && !section.getPath().isEmpty()) {
                section.setLevel(section.getPath().size());
            } else {
                section.setLevel(parentLevel + 1);
            }
            enforceLevelPathConsistency(section.getChildren(), section.getLevel());
        }
    }

    // ==================== 第二阶段：保守纠偏 ====================

    /**
     * Rule A（skill §5.4）：明显断层修复。
     * <p>
     * 若某个<b>顶层 root</b> section：
     * <ul>
     *   <li>不属于主章节白名单</li>
     *   <li>拥有 level>=2 的子节点（说明它是一组子章节的父）</li>
     * </ul>
     * 则它不应作为独立 root，而是当前主章节上下文下的 subsection →
     * 重挂到第一个 root（论文标题 section）之下，并同步 level/path/children。
     * <p>
     * 保守：没有子节点的非主章节 root <b>不动</b>（无足够依据）。
     */
    private void repairRootLevelDrop(AcademicDocument document, List<String> warnings) {
        List<Section> roots = document.getSections();
        if (roots == null || roots.size() < 2) {
            return;
        }
        String paperTitle = document.getMetadata() != null ? document.getMetadata().getTitle() : null;

        // anchor：优先 title == metadata.title 的 root；否则首个"有标题且有子节点"的 root
        Section anchor = null;
        if (paperTitle != null) {
            for (Section r : roots) {
                if (paperTitle.equals(r.getTitle())) {
                    anchor = r;
                    break;
                }
            }
        }
        if (anchor == null) {
            for (Section r : roots) {
                if (r.getTitle() != null && !r.getTitle().isBlank()
                        && r.getChildren() != null && !r.getChildren().isEmpty()) {
                    anchor = r;
                    break;
                }
            }
        }
        if (anchor == null) {
            return; // 找不到可信锚点：保守不动
        }

        List<Section> toReattach = new ArrayList<>();
        for (Section r : roots) {
            if (r == anchor) {
                continue;
            }
            if (r.getTitle() == null || r.getTitle().isBlank()) {
                continue; // untitled 容器：不动（可能承载正文）
            }
            if (paperTitle != null && paperTitle.equals(r.getTitle())) {
                continue; // 论文标题 root：不动
            }
            if (isMainSection(r.getTitle())) {
                continue; // 主章节：保持 root
            }
            if (r.getChildren() == null || r.getChildren().isEmpty()) {
                continue; // 无子节点：证据不足，保留
            }
            toReattach.add(r);
        }

        for (Section r : toReattach) {
            roots.remove(r);
            int oldLevel = r.getLevel() == null ? 1 : r.getLevel();
            List<String> oldPath = r.getPath() == null ? List.of() : new ArrayList<>(r.getPath());
            reattach(r, anchor);
            warnings.add("HIERARCHY_LEVEL_DROP_REPAIRED / ROOT_SECTION_REATTACHED: "
                    + "[" + r.getTitle() + "] oldLevel=" + oldLevel + " oldPath=" + oldPath
                    + " → newLevel=" + r.getLevel() + " newPath=" + r.getPath()
                    + " reason=orphan-root-with-children-under-title-anchor");
        }
    }

    private void reattach(Section section, Section parent) {
        int parentLevel = parent.getLevel() == null ? 1 : parent.getLevel();
        section.setLevel(parentLevel + 1);
        List<String> parentPath = parent.getPath() == null ? new ArrayList<>() : parent.getPath();
        List<String> newPath = new ArrayList<>(parentPath);
        if (section.getTitle() != null) {
            newPath.add(section.getTitle());
        }
        section.setPath(newPath);
        parent.getChildren().add(section);
        fixDescendants(section, newPath, section.getLevel());
    }

    /** 重挂后递归同步子树的 level/path，保证 level 与 path depth 一致 */
    private void fixDescendants(Section section, List<String> parentPath, int parentLevel) {
        if (section.getChildren() == null) {
            return;
        }
        for (Section child : section.getChildren()) {
            child.setLevel(parentLevel + 1);
            List<String> p = new ArrayList<>(parentPath);
            if (child.getTitle() != null) {
                p.add(child.getTitle());
            }
            child.setPath(p);
            fixDescendants(child, p, child.getLevel());
        }
    }

    /** LEVEL_PATH_MISMATCH：level 与 path depth 不一致（skill §5.6 禁止） */
    private void detectLevelPathMismatch(AcademicDocument document, List<String> warnings) {
        for (Section section : SectionUtils.flatten(document.getSections())) {
            if (section.getTitle() == null || section.getPath() == null) {
                continue;
            }
            int level = section.getLevel() == null ? 1 : section.getLevel();
            if (level != section.getPath().size()) {
                warnings.add("LEVEL_PATH_MISMATCH: [" + section.getTitle() + "] level=" + level
                        + " pathSize=" + section.getPath().size());
            }
        }
    }

    private boolean isMainSection(String title) {
        return MajorSectionAnchors.isMajorAnchor(title);
    }

    private void normalizeTitle(Section section, List<String> warnings) {
        String title = section.getTitle();
        if (title == null) {
            return;
        }
        String normalized = title.replaceAll("\\s+", " ").trim();
        if (title.contains("\n")) {
            // Marker 偶发把两个标题合并进一个 SectionHeader（skill §3.1）
            // 保守处理：仅归一为单行并告警，不擅自拆分
            warnings.add("section title 疑似合并标题（含换行）: " + normalized);
        }
        section.setTitle(normalized.isBlank() ? null : normalized);
    }

    /** SECTION_PATH_CONFLICT：path 末元素与 title 不一致 */
    private void detectPathConflict(Section section, List<String> warnings) {
        if (section.getTitle() == null || section.getPath() == null || section.getPath().isEmpty()) {
            return;
        }
        String last = section.getPath().get(section.getPath().size() - 1);
        if (!section.getTitle().equals(last)) {
            warnings.add("SECTION_PATH_CONFLICT: title=" + section.getTitle() + ", path=" + section.getPath());
        }
    }

    /** NULL_TITLE_WITH_CHILDREN：无标题却有子节点 */
    private void detectNullTitleWithChildren(Section section, List<String> warnings) {
        if (section.getTitle() == null
                && section.getChildren() != null && !section.getChildren().isEmpty()) {
            warnings.add("NULL_TITLE_WITH_CHILDREN: id=" + section.getId());
        }
    }

    /** LEVEL_JUMP：子节点 level 与父节点不连续（跳级） */
    private void detectLevelJump(Section section, List<String> warnings) {
        if (section.getChildren() == null || section.getChildren().isEmpty()) {
            return;
        }
        int parentLevel = section.getLevel() == null ? 1 : section.getLevel();
        for (Section child : section.getChildren()) {
            int childLevel = child.getLevel() == null ? 1 : child.getLevel();
            if (childLevel > parentLevel + 1) {
                warnings.add("LEVEL_JUMP: " + section.getTitle() + "(L" + parentLevel + ") → "
                        + child.getTitle() + "(L" + childLevel + ")");
            }
        }
    }

    /** ORPHAN_SUBSECTION：主章节名却处于较深层级（疑似层级错挂） */
    private void detectOrphanSubsection(Section section, List<String> warnings) {
        String title = section.getTitle();
        if (title == null || section.getLevel() == null || section.getLevel() <= 1) {
            return;
        }
        if (MajorSectionAnchors.isMajorAnchor(title)) {
            warnings.add("ORPHAN_SUBSECTION: 主章节名处于 L" + section.getLevel() + ": " + title);
        }
    }

    /** DUPLICATE_SECTION_TITLE_ADJACENT：相邻同名 section */
    private void detectAdjacentDuplicates(List<Section> sections, List<String> warnings) {
        for (int i = 1; i < sections.size(); i++) {
            String prev = sections.get(i - 1).getTitle();
            String cur = sections.get(i).getTitle();
            if (prev != null && prev.equals(cur)) {
                warnings.add("DUPLICATE_SECTION_TITLE_ADJACENT: " + cur);
            }
        }
    }
}
