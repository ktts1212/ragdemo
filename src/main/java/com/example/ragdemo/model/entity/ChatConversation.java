package com.example.ragdemo.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话表（chat_conversations）实体
 */
@Data
@TableName("chat_conversations")
public class ChatConversation {

    /** 会话唯一标识（UUID 字符串） */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 绑定的知识库 ID */
    private Long knowledgeBaseId;

    /** 使用的模型 key（deepseek / zhipu / ...） */
    private String modelKey;

    /** 会话标题 */
    private String title;

    /** 滚动摘要（二期实现） */
    private String summary;

    /** 结构化会话状态 JSON（二期实现） */
    private String sessionState;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
