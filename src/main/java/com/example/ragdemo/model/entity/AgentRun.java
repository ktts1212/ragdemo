package com.example.ragdemo.model.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * Agent Run Registry 实体（{@code agent_runs}，Step 4 §3）。
 * <p>
 * <b>不得</b>放入原文、PDF 句柄、API Key；{@code questionDigest} 只是问题摘要。
 */
@Data
@TableName("agent_runs")
public class AgentRun {

    /** 一次 Agent 执行的 ID（服务端生成 UUID） */
    private String runId;

    /** 跨请求会话 ID（可空） */
    private String conversationId;

    /** 受信知识库 ID：恢复时只用于与请求授权结果做一致性校验 */
    private Long knowledgeBaseId;

    private String principalId;

    /** HTTP 重试幂等键（可空） */
    private String requestKey;

    /** LangGraph4j 本轮 checkpoint 身份：{@code agent-<runId>} */
    private String threadId;

    /** CREATED / RUNNING / INTERRUPTED / COMPLETED / FAILED */
    private String status;

    /** 问题摘要（sha256），不存原文 */
    private String questionDigest;

    /** 本轮问答是否已写入会话记忆（幂等标记） */
    private Boolean memoryCommitted;

    /** 乐观并发版本号 */
    private Integer revision;

    private String lastErrorCode;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;
}
