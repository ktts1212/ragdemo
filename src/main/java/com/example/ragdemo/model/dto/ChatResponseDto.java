package com.example.ragdemo.model.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatResponseDto {

    private String answer;
    private List<SourceReference> sources;
    private String conversationId;

    /**
     * Phase 1：本轮会话记忆是否降级（回答已生成，但记忆未完整落库）。
     * <p>只由**真实异常捕获**置位，不根据返回文本猜测；不包含堆栈或数据库细节。
     */
    private boolean memoryDegraded = false;

    /** 降级原因码：{@code CONVERSATION_PERSIST_FAILED} / {@code MEMORY_DERIVED_UPDATE_FAILED}；正常为 null。 */
    private String memoryWarning;

    /** 兼容既有三参构造（旧 API 调用方无需改动）。 */
    public ChatResponseDto(String answer, List<SourceReference> sources, String conversationId) {
        this.answer = answer;
        this.sources = sources;
        this.conversationId = conversationId;
    }
}
