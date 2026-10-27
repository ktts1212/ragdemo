package com.example.ragdemo.agent.tools.model;

import java.util.List;

/**
 * 六个 Tool 的统一返回契约（Step 2 §4）。
 * <p>
 * <b>成功与失败都不抛异常</b>：Tool 层必须把异常收敛成结构化结果，避免 LangChain4j 把
 * 堆栈/内部路径透给模型。<br>
 * <b>可 JSON 序列化且字段稳定</b>：record + 枚举 ⇒ 字段名与取值不会随工具变化。
 *
 * @param ok       是否成功
 * @param data     成功时的载荷（失败为 null）
 * @param error    失败时的结构化错误（成功为 null）
 * @param warnings 警告（截断、对齐不可验证、OCR 缺失等；成功失败都可能有）
 */
public record ToolResult<T>(boolean ok, T data, ToolError error, List<String> warnings) {

    public ToolResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static <T> ToolResult<T> ok(T data) {
        return new ToolResult<>(true, data, null, List.of());
    }

    public static <T> ToolResult<T> ok(T data, List<String> warnings) {
        return new ToolResult<>(true, data, null, warnings);
    }

    public static <T> ToolResult<T> fail(ToolError error) {
        return new ToolResult<>(false, null, error, List.of());
    }

    public static <T> ToolResult<T> fail(ToolError error, List<String> warnings) {
        return new ToolResult<>(false, null, error, warnings);
    }

    public static <T> ToolResult<T> fail(ToolErrorCode code, String message) {
        return fail(ToolError.of(code, message));
    }
}
