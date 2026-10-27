package com.example.ragdemo.agent.tools;

import java.util.List;

import com.example.ragdemo.agent.tools.model.ToolError;
import com.example.ragdemo.agent.tools.model.ToolErrorCode;

/**
 * Tool/Service 内部受控异常：统一携带 {@link ToolErrorCode}，由 {@link AbstractPaperTool}
 * 收敛成 {@link com.example.ragdemo.agent.tools.model.ToolResult} 失败结果。
 * <p>
 * 安全约束：{@code message} 由本类工厂生成，<b>不得</b>拼入用户内容、MinIO key/bucket、
 * 内部绝对路径或密钥；越权与不存在必须返回同一个错误码。
 */
public class PaperToolException extends RuntimeException {

    private final ToolErrorCode code;
    private final boolean retryable;
    private final List<String> details;

    private PaperToolException(ToolErrorCode code, String message, boolean retryable,
            List<String> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public ToolErrorCode code() {
        return code;
    }

    public ToolError toError() {
        return new ToolError(code, getMessage(), retryable, details);
    }

    // ==================== 工厂 ====================

    public static PaperToolException invalidArgument(String message) {
        return new PaperToolException(ToolErrorCode.INVALID_ARGUMENT, message, false, List.of(), null);
    }

    /**
     * 资源不存在<b>或</b>不在当前受信知识库范围内。
     * <p>刻意不区分二者：避免通过错误差异探测其他知识库的记录是否存在。
     */
    public static PaperToolException notFoundOrForbidden(String resource) {
        return new PaperToolException(ToolErrorCode.NOT_FOUND_OR_FORBIDDEN,
                resource + "不存在或不在当前知识库范围内", false, List.of(), null);
    }

    public static PaperToolException limitExceeded(String what, long actual, long max) {
        return new PaperToolException(ToolErrorCode.LIMIT_EXCEEDED,
                what + "超出上限", true, List.of("actual=" + actual, "max=" + max), null);
    }

    public static PaperToolException unsupportedType(String message) {
        return new PaperToolException(ToolErrorCode.UNSUPPORTED_DOCUMENT_TYPE, message, false, List.of(), null);
    }

    public static PaperToolException structureNotAvailable(String message) {
        return new PaperToolException(ToolErrorCode.STRUCTURE_NOT_AVAILABLE, message, false, List.of(), null);
    }

    public static PaperToolException evidenceNotFound(String message) {
        return new PaperToolException(ToolErrorCode.EVIDENCE_NOT_FOUND, message, false, List.of(), null);
    }

    public static PaperToolException ocrRequired(String message) {
        return new PaperToolException(ToolErrorCode.OCR_REQUIRED, message, false, List.of(), null);
    }

    public static PaperToolException pdfTextUnavailable(String message, Throwable cause) {
        return new PaperToolException(ToolErrorCode.PDF_TEXT_UNAVAILABLE, message, false, List.of(), cause);
    }

    public static PaperToolException pageImageUnavailable(String message) {
        return new PaperToolException(ToolErrorCode.PAGE_IMAGE_UNAVAILABLE, message, false, List.of(), null);
    }

    public static PaperToolException handleInvalid(String message) {
        return new PaperToolException(ToolErrorCode.HANDLE_INVALID, message, false, List.of(), null);
    }

    public static PaperToolException internal(String message, Throwable cause) {
        return new PaperToolException(ToolErrorCode.INTERNAL_ERROR, message, false, List.of(), cause);
    }
}
