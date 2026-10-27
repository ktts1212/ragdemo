package com.example.ragdemo.agent.tools.model;

/**
 * Tool 稳定错误码（Step 2 §3/§4）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>越权与不存在共用 {@link #NOT_FOUND_OR_FORBIDDEN}，避免通过错误差异泄漏"跨知识库记录是否存在"；</li>
 *   <li>错误码是给 LLM/Agent 看的<b>稳定</b>契约，文案可改、code 不可随意改；</li>
 *   <li>能力缺口（OCR / 页图 / 文本层）各自独立，禁止用 INVALID_ARGUMENT 混淆。</li>
 * </ul>
 */
public enum ToolErrorCode {

    /** 参数非法（空 query、负数页码、非法 targetType 等） */
    INVALID_ARGUMENT,

    /** 资源不存在，或存在但不在当前受信知识库范围内（不区分二者） */
    NOT_FOUND_OR_FORBIDDEN,

    /** 超出硬上限（topK/页数/字符数/文件大小），且不允许被模型放宽 */
    LIMIT_EXCEEDED,

    /** 文档类型不支持该操作（非 PDF 取 PDF、GENERIC 取章节树） */
    UNSUPPORTED_DOCUMENT_TYPE,

    /** 该文档尚无可用 canonical 结构（未入库完成 / 无 artifact / GENERIC 链路） */
    STRUCTURE_NOT_AVAILABLE,

    /** 未定位到目标证据 */
    EVIDENCE_NOT_FOUND,

    /** 该页无文本层或文本层不可靠，需要 OCR（本步不实现 OCR） */
    OCR_REQUIRED,

    /** PDF 读取失败或文本层为空 */
    PDF_TEXT_UNAVAILABLE,

    /** 页面图像渲染/受控引用不可用 */
    PAGE_IMAGE_UNAVAILABLE,

    /** pdfHandle 被篡改、过期或不匹配当前知识库 */
    HANDLE_INVALID,

    /** 未预期的内部错误（已收敛，不向模型暴露堆栈/内容） */
    INTERNAL_ERROR
}
