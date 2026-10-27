package com.example.ragdemo.agent.tools.model;

import java.util.List;

/**
 * Tool 结构化错误（可 JSON 序列化，字段稳定）。
 *
 * @param code      稳定错误码
 * @param message   面向模型/人的简短说明（不含密钥、bucket/key、内部路径）
 * @param retryable 是否属于瞬时故障（Agent 可考虑重试）
 * @param details   补充信息（如超限时的实际值与上限值）
 */
public record ToolError(ToolErrorCode code, String message, boolean retryable, List<String> details) {

    public ToolError {
        details = details == null ? List.of() : List.copyOf(details);
        message = message == null ? "" : message;
    }

    public static ToolError of(ToolErrorCode code, String message) {
        return new ToolError(code, message, false, List.of());
    }

    public static ToolError of(ToolErrorCode code, String message, boolean retryable, List<String> details) {
        return new ToolError(code, message, retryable, details);
    }
}
