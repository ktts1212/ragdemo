package com.example.ragdemo.agent.model;

import java.util.List;

/**
 * 最终输出中的来源条目（来自本 Run 的真实 Tool Evidence）。
 * <p>
 * 不伪造：{@code pageNumber} 为 null 时保持 null，{@code verifiedAgainstOriginal} 原样复制，
 * 表格来自 Canonical TSV 时其 warnings 一并保留。
 *
 * @param excerpt 便于前端展示的证据摘要（受服务端上限裁剪）
 */
public record AgentSource(Long documentId, String documentName, Integer documentVersion, Integer pageNumber,
        String sourceType, LocatorView locator, String extractionMethod, boolean verifiedAgainstOriginal,
        boolean truncated, List<String> warnings, String excerpt) {

    public AgentSource {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 供 JSON 输出的定位信息（与 Step 2 的 Locator 字段一致）。 */
    public record LocatorView(String type, String label, String blockId, String sectionId,
            List<String> sectionPath, String chunkKey, Integer chunkIndex, Integer order) {
        public LocatorView {
            sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        }
    }
}
