package com.example.ragdemo.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档表（documents）实体
 * <p>
 * 记录上传的文档元数据及 ingestion 状态。
 * 状态流转：uploaded → parsing → chunking → embedding → indexed (终态)
 * 异常终态：failed
 */
@Data
@TableName("documents")
public class KnowledgeDocument {

    /** 主键 ID */
    private Long id;

    /** 所属知识库 ID */
    private Long knowledgeBaseId;

    /** 原始文件名（用户上传时的文件名） */
    private String fileName;

    /** 存储在磁盘上的文件名（防重名） */
    private String storedFileName;

    /** 文件在磁盘上的存储路径 */
    private String storagePath;

    /** 文件大小（字节） */
    private Long fileSize;

    /** MIME 类型 */
    private String contentType;

    /** 文件扩展名（如 .pdf, .docx, .txt） */
    private String fileExt;

    /** 文件校验和（SHA-256 等，可用于去重） */
    private String checksum;

    /**
     * 入库解析模式（三级决策的<b>结果</b>）：AUTO / ACADEMIC / GENERIC。
     * <p>
     * 在上传时即确定并落库，异步 worker 据此选择链路（可审计、可重试）。
     * 同时作为 ACADEMIC 链路的领域 documentId 依据（见 {@link #checksum}）。
     */
    private String parseMode;

    /** 解析版本号（artifact key 的 {@code versions/{version}} 段）；重跑解析时递增 */
    private Integer version;

    /** 文档处理状态（uploaded / parsing / chunking / embedding / indexed / failed） */
    private String status;

    /** 处理进度（0-100） */
    private Integer progress;

    /** 已入库的 chunk 数量 */
    private Integer chunkCount;

    /** 处理失败时的错误信息 */
    private String errorMessage;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}