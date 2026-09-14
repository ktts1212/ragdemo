package com.example.ragdemo.document.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 可能不完整的学术日期。
 * <p>
 * 论文日期不一定有 year-month-day（可能只有 "2024" 或 "March 2024"），
 * 强制使用 LocalDate 会丢信息，因此用可空字段 + 原始文本兜底。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartialDate {

    private Integer year;

    private Integer month;

    private Integer day;

    /** 解析器原始识别文本（如 "March 2024"），标准化失败时信息不丢失 */
    private String rawText;
}
