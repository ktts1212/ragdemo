package com.example.ragdemo.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库表（knowledge_bases）实体
 */
@Data
public class KnowledgeBase {

    /** 主键 ID */
    private Long id;

    /** 知识库名称 */
    private String name;

    /** 知识库描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}