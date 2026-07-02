package com.example.ragdemo.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息表（chat_messages）实体。
 * <p>
 * 命名 ConversationMessage 而非 ChatMessage，
 * 避免与 LangChain4j 的 dev.langchain4j.data.message.ChatMessage 冲突。
 */
@Data
@TableName("chat_messages")
public class ConversationMessage {

    /** 自增主键 */
    private Long id;

    /** 所属会话 ID */
    private String conversationId;

    /** 消息角色：user / assistant / system */
    private String role;

    /** 消息正文 */
    private String content;

    /** token 数量（可选） */
    private Integer tokenCount;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
