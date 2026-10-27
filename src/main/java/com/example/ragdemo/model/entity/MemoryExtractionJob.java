package com.example.ragdemo.model.entity;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * L3 记忆提取任务（Outbox 行）。Phase 2A：只负责"可靠入队/领取/成败标记/过期恢复"，
 * 不含任何 LLM 调用；{@code extractorVersion} 参与幂等键，便于策略升级后重新提取。
 */
@Data
public class MemoryExtractionJob {

    private Long jobId;
    private String principalId;
    private String conversationId;
    private String sourceMessageId;
    private String sourceRunId;
    private String idempotencyKey;
    private String extractorVersion;
    private String status;
    private Integer attempts;
    private String lastError;
    private LocalDateTime nextRetryAt;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    private Integer generation;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
