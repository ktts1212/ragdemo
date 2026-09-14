package com.example.ragdemo.document.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 作者所属机构。
 * <p>
 * 第一版只保留 RAG/检索/作者机构关系可能需要的主要字段，不为完整表达 TEI affiliation schema 设计大量字段。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Affiliation {

    /** 本论文内的机构 ID，供 Author.affiliationIds 关联 */
    private String id;

    /** 机构名，如 "University of XXX" */
    private String institution;

    /** 院系，如 "Department of Computer Science" */
    private String department;

    /** 地址，如 "Tokyo, Japan" */
    private String address;

    private String country;
}
