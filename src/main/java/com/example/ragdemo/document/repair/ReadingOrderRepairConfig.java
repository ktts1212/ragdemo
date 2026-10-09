package com.example.ragdemo.document.repair;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * ReadingOrderRepair 配置，绑定 application.yml 中 rag.repair.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.repair")
public class ReadingOrderRepairConfig {

    /** 允许跨过的最大中间 block 数（Figure/Noise/Footnote 等） */
    private int maxSkipBlocks = 8;

    /** 是否允许跳过 PageHeader/PageFooter 等噪声块做续接 */
    private boolean transparentNoiseEnabled = true;

    /** 是否启用 figure-associated text 保护（避免把图说明错误并入正文） */
    private boolean figureAssociationEnabled = true;
}
