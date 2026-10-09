package com.example.ragdemo.document.cleaner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.example.ragdemo.document.domain.Section;

/**
 * Section 树遍历工具（cleaner 包内部共用）。
 * <p>
 * 避免在每个 Cleaner 里重复写递归逻辑。
 */
final class SectionUtils {

    private SectionUtils() {
    }

    /** 深度优先展平所有 Section（含子节点）。 */
    static List<Section> flatten(List<Section> sections) {
        List<Section> result = new ArrayList<>();
        collect(sections, result);
        return result;
    }

    private static void collect(List<Section> sections, List<Section> acc) {
        if (sections == null) {
            return;
        }
        for (Section section : sections) {
            acc.add(section);
            collect(section.getChildren(), acc);
        }
    }

    /**
     * 递归删除满足条件的 Section。
     *
     * @return 删除数量
     */
    static int removeIf(List<Section> sections, Predicate<Section> predicate) {
        if (sections == null) {
            return 0;
        }
        int removed = 0;
        var iterator = sections.iterator();
        while (iterator.hasNext()) {
            Section section = iterator.next();
            if (predicate.test(section)) {
                iterator.remove();
                removed++;
                continue;
            }
            removed += removeIf(section.getChildren(), predicate);
        }
        return removed;
    }

    /** 递归统计所有 block 中的指定类型数量。 */
    static int countBlocks(List<Section> sections, Class<?> type) {
        int count = 0;
        if (sections == null) {
            return 0;
        }
        for (Section section : sections) {
            if (section.getBlocks() != null) {
                for (Object block : section.getBlocks()) {
                    if (type.isInstance(block)) {
                        count++;
                    }
                }
            }
            count += countBlocks(section.getChildren(), type);
        }
        return count;
    }

    /** 判断 Section 是否为空（无 block 且无子节点）。 */
    static boolean isEmpty(Section section) {
        boolean noBlocks = section.getBlocks() == null || section.getBlocks().isEmpty();
        boolean noChildren = section.getChildren() == null || section.getChildren().isEmpty();
        return noBlocks && noChildren;
    }
}
