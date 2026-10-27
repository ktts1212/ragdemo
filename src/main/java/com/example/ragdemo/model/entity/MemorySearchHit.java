package com.example.ragdemo.model.entity;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * L3 检索结果行（Phase 3）。{@code score}/{@code matchKind} 由 SQL 计算，不是表列。
 */
@Data
public class MemorySearchHit {

    private Long memoryId;
    private String memoryType;
    private String subject;
    private String predicate;
    private String content;
    private Integer version;
    private Float confidence;
    private String canonicalKey;
    private String sourceConversationId;
    private String sourceMessageId;
    private String sourceRunId;
    private LocalDateTime createdAt;
    private Double score;
    private String matchKind;
}
