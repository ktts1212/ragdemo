package com.example.ragdemo.retrieval.qa;

import java.util.List;

/**
 * 一个可检索单元 —— <b>新旧两条链路共用的统一口径</b>（skill §18 注）。
 * <p>
 * 旧字符切分链路没有 {@code sourceBlockIds}，因此 A/B 公平对照必须让两边都能产出
 * 尽可能一致的证据字段（页码 / 章节 / 证据文本）。
 *
 * @param id              链路内稳定 id（如 {@code b:12} / {@code legacy:3}）
 * @param text            实际参与 embedding 的文本
 * @param chunkType       高层类型（旧链路统一记 TEXT）
 * @param sourceBlockIds  来源 Marker block id（旧链路可为空）
 * @param sectionPath     章节路径（旧链路可为空）
 * @param pages           覆盖页码（升序，可为空）
 */
public record RetrievalUnit(
        String id,
        String text,
        String chunkType,
        List<String> sourceBlockIds,
        List<String> sectionPath,
        List<Integer> pages
) {

    public static RetrievalUnit of(String id, String text, String chunkType,
            List<String> sourceBlockIds, List<String> sectionPath, List<Integer> pages) {
        return new RetrievalUnit(id, text, chunkType,
                sourceBlockIds == null ? List.of() : sourceBlockIds,
                sectionPath == null ? List.of() : sectionPath,
                pages == null ? List.of() : pages);
    }
}
