package com.example.ragdemo.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档摄入任务表（ingestion_tasks）实体
 * <p>
 * 每个文档上传后创建一个异步 ingestion 任务。
 * 状态流转：pending → running → success (终态)
 * 异常终态：failed
 */
@Data
@TableName("ingestion_tasks")
public class IngestionTask {

    /** 主键 ID */
    private Long id;

    /** 被处理文档的 ID（关联 documents.id） */
    private Long documentId;

    /** 所属知识库 ID（关联 knowledge_bases.id） */
    private Long knowledgeBaseId;

    /** 任务状态（pending / running / success / failed） */
    private String status;

    /** 当前处理的步骤描述（如 "parsing"、"chunking"、"embedding"） */
    private String currentStep;

    /** 任务进度（0-100） */
    private Integer progress;

    /** 任务开始时间 */
    private LocalDateTime startedAt;

    /** 任务完成时间 */
    private LocalDateTime finishedAt;

    /** 任务失败时的错误信息 */
    private String errorMessage;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    // ==================== Step 5：租约 / 原子抢占 / fencing ====================

    /** 当前持有执行权的 Worker 唯一 ID（每次启动/执行可区分） */
    private String leaseOwner;

    /** 租约到期时间（以数据库时钟为基准）；过期后可被其他 Worker 原子抢占 */
    private LocalDateTime leaseExpiresAt;

    /** fencing token：每次成功抢占单调递增；业务关键写入必须校验 owner + token */
    private Long fencingToken;

    /** 已尝试次数（用于有限重试与终态判定） */
    private Integer attemptCount;

    /** 瞬时错误的下次可重试时间 */
    private LocalDateTime nextRetryAt;

    /** 结构化错误码（用于区分永久错误与瞬时错误） */
    private String lastErrorCode;
}