package com.example.ragdemo.document.cleaner;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * Front matter 预扫描收集到的候选信息（skill §4/§5.1）。
 * <p>
 * 在 HeaderFooterCleaner 删除 NoiseBlock <b>之前</b>收集，避免 DOI 等位于 PageFooter
 * 的信息在后续步骤前丢失。PreScan 只"收集候选"，不删除正文。
 */
@Getter
@Setter
public class FrontMatterContext {

    /** DOI 候选（已去除尾部标点） */
    private String doi;

    /** 期刊名候选 */
    private String journal;

    /** 标题候选（原始，未清洗） */
    private String candidateTitle;

    /** 作者行候选 */
    private final List<String> authorLineCandidates = new ArrayList<>();

    /** 单位/机构候选 */
    private final List<String> affiliationCandidates = new ArrayList<>();

    /** 关键词候选（原始行） */
    private final List<String> keywordCandidates = new ArrayList<>();

    /** 来源页（用于按页筛选） */
    private int maxScannedPage;

    public boolean hasDoi() {
        return doi != null && !doi.isBlank();
    }
}
