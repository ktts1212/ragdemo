package com.example.ragdemo.model.entity;

import java.time.LocalDateTime;

import lombok.Data;

/** L3 长期记忆行（V7 + V10）。 */
@Data
public class LongTermMemory {

    private Long memoryId;
    private String principalId;
    private String scopeKey;
    private String memoryType;
    private String subject;
    private String predicate;
    private String canonicalKey;
    private String content;
    private String contentHash;
    private String embeddingStatus;
    /** pgvector 文本（"[0.1,0.2,…]"）；仅用于 INSERT 参数绑定，不是表列。 */
    private String embeddingLiteral;
    private String status;
    private Integer version;
    private Float confidence;
    private String sourceConversationId;
    private String sourceMessageId;
    private String sourceRunId;
    private Long supersededBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
