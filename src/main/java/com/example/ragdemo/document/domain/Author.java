package com.example.ragdemo.document.domain;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 论文作者。
 * <p>
 * 不简单保存为 String，保留结构化字段以支持作者过滤、检索、ORCID、机构关联等后续能力。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Author {

    /** 完整姓名，用于直接展示 */
    private String fullName;

    private String firstName;

    private String middleName;

    private String lastName;

    /** 允许缺失 */
    private String email;

    /** 允许缺失 */
    private String orcid;

    /** 关联本论文中机构对象的 ID（对应 {@link Affiliation#getId()}） */
    @Builder.Default
    private List<String> affiliationIds = new ArrayList<>();
}
