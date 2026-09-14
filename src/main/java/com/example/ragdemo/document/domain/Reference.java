package com.example.ragdemo.document.domain;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 参考文献列表中的一条结构化文献。
 * <p>
 * 区别于正文里的 {@link CitationMarker}（正文的 [12] 是引用标记，这里才是文献本身）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Reference {

    /** 文献 ID，与 CitationMarker.targetReferenceIds 关联 */
    private String id;

    /** 原始参考文献文本，必须保留（GROBID 字段拆分不完整时仍有价值） */
    private String rawText;

    private String title;

    /** 文献作者姓名列表 */
    @Builder.Default
    private List<String> authors = new ArrayList<>();

    private String journal;

    private PartialDate publicationDate;

    private String volume;

    private String issue;

    private String pages;

    private String doi;

    private String pmid;

    private String pmcId;

    private String arxivId;

    private String url;
}
