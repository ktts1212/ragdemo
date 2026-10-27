package com.example.ragdemo.agent.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 六个学术 Tool 的工程配置（{@code rag.tools.*}）。
 * <p>
 * 与 {@link PaperToolLimits} 一一对应：配置对象可变（Spring 绑定），但一旦转成
 * {@link PaperToolLimits}（record）即不可变，运行期模型无法修改。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.tools")
public class PaperToolProperties {

    private int listPageSizeMax = 20;
    private int listOffsetMax = 10_000;
    private int titleFilterMaxChars = 200;
    private int searchTopKMax = 10;
    private int searchContentMaxChars = 12_000;
    private int queryMaxChars = 512;
    private int structureDepthMax = 6;
    private int structureTextMaxChars = 12_000;
    private int structureAbstractMaxChars = 4_000;
    private int readPdfMaxPages = 3;
    private int readPdfTextMaxChars = 15_000;
    private int pdfImageMaxEdgePixels = 1_600;
    private long pdfMaxBytes = 52_428_800L;
    private int pdfMaxPages = 500;
    private int pdfReadTimeoutMs = 20_000;
    private int handleTtlSeconds = 900;
    private int evidenceMaxItems = 5;
    private int evidenceTextMaxChars = 12_000;
    private boolean pageImageEnabled = true;

    public PaperToolLimits toLimits() {
        return new PaperToolLimits(listPageSizeMax, listOffsetMax, titleFilterMaxChars, searchTopKMax,
                searchContentMaxChars, queryMaxChars, structureDepthMax, structureTextMaxChars,
                structureAbstractMaxChars, readPdfMaxPages, readPdfTextMaxChars, pdfImageMaxEdgePixels,
                pdfMaxBytes, pdfMaxPages, pdfReadTimeoutMs, handleTtlSeconds, evidenceMaxItems,
                evidenceTextMaxChars, pageImageEnabled);
    }
}
