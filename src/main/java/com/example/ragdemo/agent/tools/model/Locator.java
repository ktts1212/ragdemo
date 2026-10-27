package com.example.ragdemo.agent.tools.model;

import java.util.List;

/**
 * 证据定位信息（"这条内容在文档里的哪个位置"）。
 * <p>
 * 只填真实可得的字段，取不到一律 null，<b>不推断、不伪造</b>。
 *
 * @param type       证据类型：CHUNK / TABLE / FIGURE / EQUATION / PARAGRAPH / KEYWORD / SECTION
 * @param label      编号标签，如 "Table 2"、"Figure 1"、"(3)"（可空）
 * @param blockId    Canonical block id（可空）
 * @param sectionId  Canonical section 稳定 id（可空）
 * @param sectionPath 章节路径（如 ["Methods","Contrastive Objective"]，可空）
 * @param chunkKey   学术链路 chunk_key（可空）
 * @param chunkIndex 文档内 chunk 序号（可空）
 * @param order      Canonical block/section 顺序（可空）
 */
public record Locator(String type, String label, String blockId, String sectionId,
        List<String> sectionPath, String chunkKey, Integer chunkIndex, Integer order) {

    public Locator {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
    }

    public static Locator ofType(String type, String label) {
        return new Locator(type, label, null, null, List.of(), null, null, null);
    }
}
