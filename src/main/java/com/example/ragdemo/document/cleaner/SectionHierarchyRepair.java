package com.example.ragdemo.document.cleaner;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Section;

import lombok.extern.slf4j.Slf4j;

/**
 * Section 层级纠偏（skill §5.2「编号标题：优先使用」）。
 * <p>
 * 只修一类<b>可判定</b>的结构错误：<b>编号深度相同的标题被错误父子化</b>。
 * <pre>
 * 错误：  2.1 Pre-processing
 *           └── 2.2 Baseline models        （编号 2.1 / 2.2 深度相同 → 本应同级）
 * 修正：  2.1 Pre-processing
 *         2.2 Baseline models
 * </pre>
 * <b>为什么只信编号、不信 rawLevel</b>（基于 5 篇真实论文的证据）：
 * <pre>
 * [rawLevel 相同却父子]  raw=2/2  3 Method  &gt;  3.1 Event Reconstruction    ← 合法父子！
 * [rawLevel 相同却父子]  raw=2/2  5 Experiments  &gt;  5.1 Pretraining       ← 合法父子！
 * [编号同深度却父子]     num=3/3  A.1 Pretraining  &gt;  A.2 Effect ...      ← 真错误
 * </pre>
 * 每篇论文有 8~13 例"rawLevel 相同却父子"，其中绝大多数是<b>合法父子</b>
 * （Marker 的 {@code section_hierarchy} 粒度较粗，常把节与其子节标成同一 depth）。
 * 因此 {@code rawLevel} <b>不能</b>作为"应同级"的正向信号，仅用于审计记录与观测告警。
 * <p>
 * 保守边界（skill §17：正确归属 &gt; 保守不修 &gt; 错误重排）：
 * <ul>
 *   <li>仅当父子标题<b>都有编号</b>且<b>深度完全相同</b>时才提级</li>
 *   <li>无编号标题一律不动（属 skill §5.3，需更多结构证据，本轮不实现）</li>
 *   <li>每轮只做一次，避免遍历中改树；移动后重建该子树的 level/path</li>
 * </ul>
 * <p>
 * <b>Rule 2（A2）：子编号比父编号更浅</b> —— 例：
 * <pre>
 * 错误：  4.4.6. Ablations ...                （编号 3）
 *           ├── 4.6. Model robustness ...     （编号 2）← 比父更浅，编号矛盾
 *           └── 4.7. Visualization            （编号 2）
 * 修正：  4.6. / 4.7. 挂回「编号前缀为 4」的父节下：
 *         4. Experiments and results           （编号 1）
 *           ├── 4.4. Ablation studies         （编号 2）
 *           ├── 4.6. Model robustness ...     （编号 2）
 *           └── 4.7. Visualization            （编号 2）
 * </pre>
 * 判据仍是文档自身的编号：{@code "4.6"} 的父节必须是编号为 {@code "4"} 的 section。
 * 找不到该父节 / 目标非法（自身或后代）→ <b>不动</b>，只出 {@code SHALLOWER_CHILD_UNRESOLVED} 告警。
 * 仅处理<b>纯阿拉伯多级编号</b>（罗马 / 字母编号的前缀语义不确定，保守跳过）。
 */
@Slf4j
@Component
public class SectionHierarchyRepair {

    /** 提级轮数上限（防病态输入导致不收敛） */
    private static final int MAX_ROUNDS = 100;

    /** 审计 reason：编号同深度却被父子化 → 平级（skill §5.2） */
    private static final String REASON_SIBLING = "numbered-same-depth-sibling";

    /** 审计 reason：子编号比父编号更浅 → 挂回「编号前缀对应的父节」（A2） */
    private static final String REASON_SHALLOWER = "numbered-shallower-child-reparent";

    /**
     * @return 审计记录 + warnings
     */
    public Result repair(AcademicDocument document) {
        List<Audit> audits = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (document == null || document.getSections() == null) {
            return new Result(audits, warnings);
        }

        // Rule 1（skill §5.2）：编号同深度却被父子化 → 平级
        int rounds = 0;
        while (rounds++ < MAX_ROUNDS && promoteOne(document, audits)) {
            // 每轮只处理一次，随后重新扫描以暴露新的冲突
        }

        // Rule 2（A2）：子编号比父编号更浅 → 挂回「编号前缀对应的父节」
        int shallowerRounds = 0;
        while (shallowerRounds++ < MAX_ROUNDS && reparentShallowerOne(document, audits)) {
            // 同上：每轮只处理一次
        }

        int sameRawNested = countSameRawLevelNested(document.getSections());
        if (sameRawNested > 0) {
            warnings.add("SAME_RAW_LEVEL_BUT_NESTED: " + sameRawNested
                    + "（Marker rawLevel 相同却呈父子；实测多数为合法父子，故仅观测不提级）");
        }
        int unresolvedShallower = countUnresolvedShallower(document);
        if (unresolvedShallower > 0) {
            warnings.add("SHALLOWER_CHILD_UNRESOLVED: " + unresolvedShallower
                    + "（父编号比子编号更深，但找不到编号前缀对应的父节 → 保守不动）");
        }
        int siblingPromoted = countByReason(audits, REASON_SIBLING);
        int shallowerReparented = countByReason(audits, REASON_SHALLOWER);
        if (siblingPromoted > 0) {
            warnings.add("SIBLING_REPAIRED_BY_NUMBERING: " + siblingPromoted);
        }
        if (shallowerReparented > 0) {
            warnings.add("SHALLOWER_CHILD_REPARENTED: " + shallowerReparented);
        }
        for (Audit audit : audits) {
            log.info("Section 层级纠偏: [{}] {} → 新父={} oldLevel={} newLevel={} rawLevel={}/{}"
                            + "（rawLevel 冲突={}）reason={} path {} → {}",
                    audit.sectionId(), audit.title(), audit.newParentId(),
                    audit.oldLevel(), audit.newLevel(),
                    audit.parentRawLevel(), audit.childRawLevel(), audit.rawLevelConflict(),
                    audit.reason(), audit.oldPath(), audit.newPath());
        }
        log.info("SectionHierarchyRepair 完成: siblingPromoted={}, shallowerReparented={}, "
                        + "sameRawLevelNested={}, unresolvedShallower={}",
                siblingPromoted, shallowerReparented, sameRawNested, unresolvedShallower);
        return new Result(audits, warnings);
    }

    // ==================== 提级 ====================

    /** 扫描全树，做<b>一次</b>提级；返回是否发生了提级。 */
    private boolean promoteOne(AcademicDocument document, List<Audit> audits) {
        List<Section> all = SectionTreeUtils.flatten(document.getSections());
        for (Section parent : all) {
            Integer parentDepth = HeadingNumbering.depth(parent.getTitle());
            if (parentDepth == null || parent.getChildren().isEmpty()) {
                continue;
            }
            for (Section child : new ArrayList<>(parent.getChildren())) {
                Integer childDepth = HeadingNumbering.depth(child.getTitle());
                if (childDepth == null || !childDepth.equals(parentDepth)) {
                    continue;
                }
                promote(document, parent, child, audits);
                return true;
            }
        }
        return false;
    }

    /** 把 child 从 parent 的子节点提升为 parent 的<b>同级</b>（紧随 parent 之后），并重建其子树 level/path。 */
    private void promote(AcademicDocument document, Section parent, Section child, List<Audit> audits) {
        Integer oldLevel = child.getLevel();
        List<String> oldPath = child.getPath() == null ? List.of() : new ArrayList<>(child.getPath());
        Section grandParent = SectionTreeUtils.findParent(document.getSections(), parent);

        parent.getChildren().remove(child);
        List<Section> siblings = grandParent == null ? document.getSections() : grandParent.getChildren();
        int index = siblings.indexOf(parent);
        if (index < 0) {
            siblings.add(child);
        } else {
            siblings.add(index + 1, child);
        }

        List<String> parentPath = grandParent == null || grandParent.getPath() == null
                ? List.of()
                : grandParent.getPath();
        int parentLevel = grandParent == null || grandParent.getLevel() == null
                ? 0
                : grandParent.getLevel();
        SectionTreeUtils.rebuildLevelAndPath(child, parentPath, parentLevel);

        audits.add(new Audit(
                child.getId(),
                child.getTitle(),
                parent.getId(),
                grandParent == null ? null : grandParent.getId(),
                oldLevel,
                child.getLevel(),
                oldPath,
                new ArrayList<>(child.getPath()),
                parent.getRawLevel(),
                child.getRawLevel(),
                // rawLevel 冲突仅记录，不影响决策
                parent.getRawLevel() != null && child.getRawLevel() != null
                        && !child.getRawLevel().equals(parent.getRawLevel()),
                REASON_SIBLING));
    }

    // ==================== Rule 2（A2）：子编号比父编号更浅 ====================

    /**
     * 扫描全树，处理<b>一次</b>「子编号比父更浅」的编号矛盾；返回是否移动过一次。
     * <p>
     * 依据仍是文档自身的编号：{@code "4.6"} 的父节必须是编号为 {@code "4"} 的 section。
     * 找不到该父节、或目标非法（自身 / 自身后代）→ 跳过（保守不动，由观测告警体现）。
     */
    private boolean reparentShallowerOne(AcademicDocument document, List<Audit> audits) {
        List<Section> all = SectionTreeUtils.flatten(document.getSections());
        for (Section parent : all) {
            Integer parentDepth = HeadingNumbering.depth(parent.getTitle());
            if (parentDepth == null || parent.getChildren().isEmpty()) {
                continue;
            }
            for (Section child : new ArrayList<>(parent.getChildren())) {
                Integer childDepth = HeadingNumbering.depth(child.getTitle());
                if (childDepth == null || childDepth >= parentDepth) {
                    continue; // 只处理「子比父更浅」这一种编号矛盾
                }
                Section target = findNumberingParent(all, child);
                if (target == null || target == parent || isSelfOrDescendant(target, child)) {
                    continue;
                }
                reparentTo(child, parent, target, audits);
                return true;
            }
        }
        return false;
    }

    /** 依据纯阿拉伯编号前缀定位应挂的父节：{@code "4.6"} → 找编号为 {@code "4"} 的 section。 */
    private Section findNumberingParent(List<Section> all, Section child) {
        String parentToken = HeadingNumbering.parentToken(
                HeadingNumbering.numericToken(child.getTitle()));
        if (parentToken == null) {
            return null;
        }
        for (Section candidate : all) {
            if (parentToken.equals(HeadingNumbering.numericToken(candidate.getTitle()))) {
                return candidate;
            }
        }
        return null;
    }

    /** candidate 是否就是 node 本身或其后代（用于防止把节点挂到自己的子树里）。 */
    private boolean isSelfOrDescendant(Section candidate, Section node) {
        if (candidate == node) {
            return true;
        }
        for (Section child : node.getChildren()) {
            if (isSelfOrDescendant(candidate, child)) {
                return true;
            }
        }
        return false;
    }

    /** 把 child 从 oldParent 移到 newParent（按文档 order 插入），并重建其子树 level/path。 */
    private void reparentTo(Section child, Section oldParent, Section newParent, List<Audit> audits) {
        Integer oldLevel = child.getLevel();
        List<String> oldPath = SectionTreeUtils.copyPath(child);

        oldParent.getChildren().remove(child);
        insertByOrder(newParent.getChildren(), child);

        SectionTreeUtils.rebuildLevelAndPath(child,
                newParent.getPath() == null ? List.of() : newParent.getPath(),
                newParent.getLevel() == null ? 0 : newParent.getLevel());

        audits.add(new Audit(child.getId(), child.getTitle(), oldParent.getId(), newParent.getId(),
                oldLevel, child.getLevel(), oldPath, SectionTreeUtils.copyPath(child),
                oldParent.getRawLevel(), child.getRawLevel(), false, REASON_SHALLOWER));
    }

    /** 按全局 order 插入，保持文档顺序（order 为 null 视为最大）。 */
    private void insertByOrder(List<Section> siblings, Section section) {
        int order = section.getOrder() == null ? Integer.MAX_VALUE : section.getOrder();
        int index = siblings.size();
        for (int i = 0; i < siblings.size(); i++) {
            int current = siblings.get(i).getOrder() == null
                    ? Integer.MAX_VALUE
                    : siblings.get(i).getOrder();
            if (current > order) {
                index = i;
                break;
            }
        }
        siblings.add(index, section);
    }

    /** 仍未解决的「子比父更浅」数量（找不到编号前缀对应的父节）。 */
    private int countUnresolvedShallower(AcademicDocument document) {
        List<Section> all = SectionTreeUtils.flatten(document.getSections());
        int count = 0;
        for (Section parent : all) {
            Integer parentDepth = HeadingNumbering.depth(parent.getTitle());
            if (parentDepth == null) {
                continue;
            }
            for (Section child : parent.getChildren()) {
                Integer childDepth = HeadingNumbering.depth(child.getTitle());
                if (childDepth != null && childDepth < parentDepth) {
                    count++;
                }
            }
        }
        return count;
    }

    private int countByReason(List<Audit> audits, String reason) {
        int count = 0;
        for (Audit audit : audits) {
            if (reason.equals(audit.reason())) {
                count++;
            }
        }
        return count;
    }

    // ==================== 观测（只告警，不修） ====================

    /** rawLevel 相同却呈父子关系的数量（多数为合法父子，仅用于观测 Marker 层级粒度）。 */
    private int countSameRawLevelNested(List<Section> sections) {
        int count = 0;
        for (Section parent : SectionTreeUtils.flatten(sections)) {
            for (Section child : parent.getChildren()) {
                if (parent.getRawLevel() != null && child.getRawLevel() != null
                        && child.getRawLevel() <= parent.getRawLevel()) {
                    count++;
                }
            }
        }
        return count;
    }

    // ==================== 工具 ====================

    /**
     * 一次同级提级的审计记录（skill §8）。
     *
     * @param rawLevelConflict 父与子的 Marker rawLevel 不一致（仅记录，不影响决策）
     */
    public record Audit(String sectionId, String title, String oldParentId, String newParentId,
            Integer oldLevel, Integer newLevel, List<String> oldPath, List<String> newPath,
            Integer parentRawLevel, Integer childRawLevel, boolean rawLevelConflict, String reason) {
    }

    /**
     * @param audits   全部提级记录
     * @param warnings 提级/观测告警
     */
    public record Result(List<Audit> audits, List<String> warnings) {

        /** 全部修复数（Rule 1 + Rule 2） */
        public int promotedCount() {
            return audits == null ? 0 : audits.size();
        }

        /** Rule 1：编号同深度却被父子化 → 平级 */
        public int siblingPromotedCount() {
            return countByReason(REASON_SIBLING);
        }

        /** Rule 2（A2）：子编号比父更浅 → 挂回编号前缀对应的父节 */
        public int shallowerReparentedCount() {
            return countByReason(REASON_SHALLOWER);
        }

        private int countByReason(String reason) {
            int count = 0;
            if (audits != null) {
                for (Audit audit : audits) {
                    if (reason.equals(audit.reason())) {
                        count++;
                    }
                }
            }
            return count;
        }
    }
}
