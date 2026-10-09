package com.example.ragdemo.document.repair;

import java.util.List;

/**
 * ReadingOrderRepair 单次合并的审计记录（skill §20）。
 * <p>
 * 所有修复都必须可追踪：谁和谁合并、中间跳过了哪些 block、原因是什么。
 *
 * @param leftBlockId    被合并段落（左）的来源 block id
 * @param rightBlockId   合并进来的段落（右）的来源 block id
 * @param skippedBlockIds 中间被跳过的 block id 列表
 * @param reason         合并原因
 */
public record ReadingOrderRepairAudit(
        String leftBlockId,
        String rightBlockId,
        List<String> skippedBlockIds,
        String reason
) {
}
