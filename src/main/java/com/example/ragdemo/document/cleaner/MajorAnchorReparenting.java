package com.example.ragdemo.document.cleaner;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Section;

import lombok.extern.slf4j.Slf4j;

/**
 * 主章节锚点的保守重挂（skill §4 Rule 4，本仓库收敛版）。
 * <p>
 * <b>要解决的问题</b>（目标论文的真实形态）：
 * <pre>
 * L1 Discussion
 *   L2 ...（Discussion 自己的子节）
 *   L2 Methods          ← 锚点、无 block 无子节点
 *   L2 Datasets         ← Marker 把三者标成同一 depth（同级）
 *   L2 Pre-processing
 *     L3 Patch segmentation strategy ...
 * </pre>
 * 期望：
 * <pre>
 * L1 Discussion
 *   L2 ...（Discussion 自己的子节）
 * L1 Methods            ← 提升为 root
 *   L2 Datasets
 *   L2 Pre-processing
 *     L3 Patch segmentation strategy ...
 * </pre>
 * <b>为什么需要「收编」</b>：只把空的 Methods 提为 root 会留下一个无内容的空 root，
 * Datasets/Pre-processing 的 sectionPath 仍是 {@code Discussion > Datasets}，问题并未解决。
 * <p>
 * <b>保守触发条件（必须全部满足）</b>：
 * <ol>
 *   <li>{@code M.title} 命中主章节锚点（严格归一化精确匹配）</li>
 *   <li>{@code M} <b>无 block 且无子节点</b> —— 这是"Marker 把其子节拉平成同级"的典型签名</li>
 *   <li>{@code M} 的父 {@code P} 存在，且 {@code P} 也是主章节锚点
 *       （即 Validator 的 {@code MAJOR_HEADING_NESTED_UNDER_MAJOR_HEADING}）</li>
 *   <li>{@code M} 与 {@code P} <b>都有 Marker rawLevel</b>（必须有 Marker 佐证）</li>
 *   <li>存在可收编对象：{@code M} 之后<b>连续</b>的同级 sibling，满足
 *       {@code rawLevel == M.rawLevel} 且标题不是主章节锚点</li>
 * </ol>
 * 任一条不满足即<b>不动</b>（含"锚点子节有正文"的情况 —— 证据不足，留给人工/后续）。
 * <p>
 * 收编边界由两条独立证据共同界定：<b>遇到下一个主章节锚点标题就停</b>（语义边界），
 * 且 <b>rawLevel 必须与 M 相同</b>（Marker 层级佐证）。
 */
@Slf4j
@Component
public class MajorAnchorReparenting {

    /** 重挂轮数上限（防病态输入不收敛） */
    private static final int MAX_ROUNDS = 50;

    /**
     * @return 审计记录 + warnings
     */
    public Result reparent(AcademicDocument document) {
        List<Audit> audits = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (document == null || document.getSections() == null) {
            return new Result(audits, warnings);
        }

        int rounds = 0;
        while (rounds++ < MAX_ROUNDS && reparentOne(document, audits)) {
            // 每轮只重挂一次，随后重新扫描
        }

        int remaining = countAnchorNestedUnderAnchor(document.getSections());
        if (remaining > 0) {
            // 未被修复的锚点嵌套：多为"锚点子节带正文"等证据不足情形 —— 只观测
            warnings.add("MAJOR_HEADING_NESTED_UNDER_MAJOR_HEADING_REMAINING: " + remaining
                    + "（锚点嵌套锚点但仍存在；多为证据不足（子节非空 / 缺 rawLevel / 无可收编同级），"
                    + "按保守原则不自动重挂）");
        }
        if (!audits.isEmpty()) {
            warnings.add("MAJOR_ANCHOR_REPARENTED: " + audits.size());
            for (Audit audit : audits) {
                log.info("主章节锚点重挂: [{}] {} 由 {} 的子节提升为 root；收编={}；"
                                + "rawLevel={}/{}；level {} → {}；path {} → {}",
                        audit.sectionId(), audit.title(), audit.oldParentTitle(),
                        audit.adoptedSectionTitles(), audit.parentRawLevel(), audit.childRawLevel(),
                        audit.oldLevel(), audit.newLevel(), audit.oldPath(), audit.newPath());
            }
        }
        log.info("MajorAnchorReparenting 完成: reparented={}, remainingAnchorNested={}",
                audits.size(), remaining);
        return new Result(audits, warnings);
    }

    // ==================== 重挂 ====================

    /** 扫描全树，做<b>一次</b>重挂；返回是否发生。 */
    private boolean reparentOne(AcademicDocument document, List<Audit> audits) {
        for (Section candidate : SectionTreeUtils.flatten(document.getSections())) {
            if (!MajorSectionAnchors.isMajorAnchor(candidate.getTitle())) {
                continue;
            }
            if (!isEmptyOrphan(candidate)) {
                continue; // 条件 2：必须是"空得只剩标题"的孤儿锚点
            }
            Section parent = SectionTreeUtils.findParent(document.getSections(), candidate);
            if (parent == null) {
                continue; // 已是 root
            }
            if (!MajorSectionAnchors.isMajorAnchor(parent.getTitle())) {
                continue; // 条件 3：父也必须是锚点
            }
            if (candidate.getRawLevel() == null || parent.getRawLevel() == null) {
                continue; // 条件 4：必须有 Marker 佐证
            }
            List<Section> adoptable = adoptableSiblings(parent, candidate);
            if (adoptable.isEmpty()) {
                continue; // 条件 5：无可收编对象 → 不产出空 root
            }
            promote(document, parent, candidate, adoptable, audits);
            return true;
        }
        return false;
    }

    /** 空得只剩标题：无自身 block，且无子节点。 */
    private boolean isEmptyOrphan(Section section) {
        boolean noBlocks = section.getBlocks() == null || section.getBlocks().isEmpty();
        boolean noChildren = section.getChildren() == null || section.getChildren().isEmpty();
        return noBlocks && noChildren;
    }

    /**
     * 可收编的同级：从 {@code anchor} 的下一个 sibling 起，取<b>连续</b>满足
     * {@code rawLevel == anchor.rawLevel} 且标题非主章节锚点的一段。
     */
    private List<Section> adoptableSiblings(Section parent, Section anchor) {
        List<Section> siblings = parent.getChildren();
        int start = siblings.indexOf(anchor);
        if (start < 0) {
            return List.of();
        }
        List<Section> adoptable = new ArrayList<>();
        for (int i = start + 1; i < siblings.size(); i++) {
            Section sibling = siblings.get(i);
            if (MajorSectionAnchors.isMajorAnchor(sibling.getTitle())) {
                break; // 语义边界：遇到下一个主章节标题即停
            }
            if (sibling.getRawLevel() == null || !sibling.getRawLevel().equals(anchor.getRawLevel())) {
                break; // Marker 层级佐证不成立即停
            }
            adoptable.add(sibling);
        }
        return adoptable;
    }

    /** 把 anchor 提升为 root（紧随其最顶层祖先之后），并把可收编同级挂到它之下。 */
    private void promote(AcademicDocument document, Section parent, Section anchor,
            List<Section> adoptable, List<Audit> audits) {
        Integer oldLevel = anchor.getLevel();
        List<String> oldPath = SectionTreeUtils.copyPath(anchor);
        List<String> adoptedTitles = adoptable.stream().map(Section::getTitle).toList();

        List<Section> parentChildren = parent.getChildren();
        parentChildren.remove(anchor);
        for (Section sibling : adoptable) {
            parentChildren.remove(sibling);
        }

        List<Section> roots = document.getSections();
        Section anchorRoot = SectionTreeUtils.rootAncestor(roots, parent);
        int index = anchorRoot == null ? -1 : roots.indexOf(anchorRoot);
        if (index < 0) {
            roots.add(anchor);
        } else {
            roots.add(index + 1, anchor); // 紧随其最顶层祖先之后，保持文档顺序
        }

        anchor.setChildren(new ArrayList<>(adoptable));
        SectionTreeUtils.rebuildLevelAndPath(anchor, List.of(), 0);

        audits.add(new Audit(anchor.getId(), anchor.getTitle(), parent.getId(), parent.getTitle(),
                oldLevel, anchor.getLevel(), oldPath, SectionTreeUtils.copyPath(anchor),
                adoptedTitles, parent.getRawLevel(), anchor.getRawLevel(),
                "orphan-major-anchor-nested-under-major-anchor"));
    }

    // ==================== 观测 ====================

    /** 仍存在的"锚点嵌套锚点"数量（含未被修复的证据不足情形）。 */
    private int countAnchorNestedUnderAnchor(List<Section> sections) {
        int count = 0;
        for (Section parent : SectionTreeUtils.flatten(sections)) {
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

    /**
     * 一次重挂的审计记录（skill §8）。
     *
     * @param adoptedSectionTitles 被收编为子节的同级标题（有序）
     */
    public record Audit(String sectionId, String title, String oldParentId, String oldParentTitle,
            Integer oldLevel, Integer newLevel, List<String> oldPath, List<String> newPath,
            List<String> adoptedSectionTitles, Integer parentRawLevel, Integer childRawLevel,
            String reason) {
    }

    /**
     * @param audits   全部重挂记录
     * @param warnings 重挂/观测告警
     */
    public record Result(List<Audit> audits, List<String> warnings) {

        public int reparentedCount() {
            return audits == null ? 0 : audits.size();
        }
    }
}
