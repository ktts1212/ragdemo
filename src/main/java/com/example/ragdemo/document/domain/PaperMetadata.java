package com.example.ragdemo.document.domain;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 论文身份与出版元数据。
 * <p>
 * 只保留 RAG/检索/作者机构关系真正需要的字段，不因解析器能识别更多字段而膨胀模型
 * （电话、传真、copyright 原始信息等低价值字段不纳入）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperMetadata {

    private String title;

    @Builder.Default
    private List<Author> authors = new ArrayList<>();

    @Builder.Default
    private List<Affiliation> affiliations = new ArrayList<>();

    @Builder.Default
    private List<String> keywords = new ArrayList<>();

    private String doi;

    private String arxivId;

    private String journal;

    private String publisher;

    /** 出版日期（可能不完整，如只有年份） */
    private PartialDate publicationDate;
}
