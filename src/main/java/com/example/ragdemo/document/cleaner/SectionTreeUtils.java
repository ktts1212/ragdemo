package com.example.ragdemo.document.cleaner;

import java.util.ArrayList;
import java.util.List;

import com.example.ragdemo.document.domain.Section;

/**
 * Section 树通用工具（cleaner 包内部共用）。
 * <p>
 * 避免在 {@link SectionHierarchyRepair} / {@link MajorAnchorReparenting} 之间重复实现
 * "展平、找父、重建 level+path"。
 */
final class SectionTreeUtils {

    private SectionTreeUtils() {
    }

    /** 深度优先展平（含自身与全部后代）。 */
    static void collect(List<Section> sections, List<Section> accumulator) {
        if (sections == null) {
            return;
        }
        for (Section section : sections) {
            if (section == null) {
                continue;
            }
            accumulator.add(section);
            collect(section.getChildren(), accumulator);
        }
    }

    /** 展平为列表。 */
    static List<Section> flatten(List<Section> sections) {
        List<Section> all = new ArrayList<>();
        collect(sections, all);
        return all;
    }

    /** 查找直接父节点；该节点为 root 时返回 {@code null}。 */
    static Section findParent(List<Section> roots, Section target) {
        if (roots == null) {
            return null;
        }
        for (Section section : roots) {
            if (section == null) {
                continue;
            }
            if (section.getChildren().contains(target)) {
                return section;
            }
            Section found = findParent(section.getChildren(), target);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 追溯某节点所在子树的最顶层祖先（即它所属的 root）；它本身是 root 时返回自身。 */
    static Section rootAncestor(List<Section> roots, Section target) {
        if (roots == null || target == null) {
            return null;
        }
        if (roots.contains(target)) {
            return target;
        }
        Section parent = findParent(roots, target);
        return parent == null ? null : rootAncestor(roots, parent);
    }

    /**
     * 依据新位置重建该子树（含全部后代）的 {@code level} 与 {@code path}。
     * <p>
     * {@code level = path.size()}；标题为空则不加入 path（与既有约定一致）。
     */
    static void rebuildLevelAndPath(Section section, List<String> parentPath, int parentLevel) {
        section.setLevel(parentLevel + 1);
        List<String> path = new ArrayList<>(parentPath == null ? List.of() : parentPath);
        if (section.getTitle() != null && !section.getTitle().isBlank()) {
            path.add(section.getTitle());
        }
        section.setPath(path);
        for (Section child : section.getChildren()) {
            rebuildLevelAndPath(child, path, section.getLevel());
        }
    }

    /** 复制 path（避免共享可变列表）。 */
    static List<String> copyPath(Section section) {
        return section.getPath() == null ? List.of() : new ArrayList<>(section.getPath());
    }
}
