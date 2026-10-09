package com.example.ragdemo.document.cleaner;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.NoiseBlock;

import lombok.extern.slf4j.Slf4j;

/**
 * Header/Footer 清洗（skill §21 步骤 1、§3.6）。
 * <p>
 * Marker 的 PageHeader/PageFooter 在 Parser 阶段被保留为 {@link NoiseBlock}（可追踪），
 * 本组件负责将其从正文中移除，避免页眉页脚污染检索。
 */
@Slf4j
@Component
public class HeaderFooterCleaner {

    /**
     * @return 移除的噪声块数量
     */
    public int clean(AcademicDocument document) {
        if (document == null || document.getSections() == null) {
            return 0;
        }
        int removed = 0;
        for (Section section : SectionUtils.flatten(document.getSections())) {
            if (section.getBlocks() == null) {
                continue;
            }
            int before = section.getBlocks().size();
            section.getBlocks().removeIf(block -> block instanceof NoiseBlock);
            removed += before - section.getBlocks().size();
        }
        if (removed > 0) {
            log.debug("HeaderFooterCleaner: 移除噪声块 {} 个", removed);
        }
        return removed;
    }
}
