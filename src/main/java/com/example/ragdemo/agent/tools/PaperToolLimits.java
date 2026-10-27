package com.example.ragdemo.agent.tools;

/**
 * Tool 硬上限（Step 2 §4）。
 * <p>
 * <b>不可被模型提高</b>：本对象由受信配置构造，Tool 只读取不改写；
 * LangChain4j 暴露给 LLM 的参数里<b>不含</b>任何上限字段，模型只能缩小不能放大。
 * <p>
 * 所有上限是初始工程默认值（见 {@link PaperToolProperties}），可调但必须重新部署，运行期不可变。
 */
public record PaperToolLimits(
        /** ListPapers 每页最大条数 */
        int listPageSizeMax,
        /** ListPapers offset 上限（防止深分页） */
        int listOffsetMax,
        /** ListPapers 标题过滤串最大长度 */
        int titleFilterMaxChars,
        /** SearchPaper topK 上限 */
        int searchTopKMax,
        /** SearchPaper 单次返回正文总量上限（字符） */
        int searchContentMaxChars,
        /** SearchPaper query 最大长度 */
        int queryMaxChars,
        /** GetPaperStructure 章节树最大深度 */
        int structureDepthMax,
        /** GetPaperStructure 单次章节正文上限（字符） */
        int structureTextMaxChars,
        /** GetPaperStructure 摘要返回上限（字符） */
        int structureAbstractMaxChars,
        /** ReadPdfPages 单次最大页数 */
        int readPdfMaxPages,
        /** ReadPdfPages 单次文本上限（字符） */
        int readPdfTextMaxChars,
        /** 页面图像最长边（像素） */
        int pdfImageMaxEdgePixels,
        /** PDF 文件大小上限（字节） */
        long pdfMaxBytes,
        /** PDF 页数上限（防 zip bomb / 异常文件） */
        int pdfMaxPages,
        /** 单次 PDF 文本提取超时（毫秒） */
        int pdfReadTimeoutMs,
        /** pdfHandle 有效期（秒） */
        int handleTtlSeconds,
        /** InspectPaperEvidence 单次最多返回证据条数 */
        int evidenceMaxItems,
        /** InspectPaperEvidence 单次返回文本总量上限（字符） */
        int evidenceTextMaxChars,
        /** 是否允许渲染受控页面图像引用 */
        boolean pageImageEnabled) {

    /** 工程默认值（与 application.yml 中 rag.tools 默认一致）。 */
    public static PaperToolLimits defaults() {
        return new PaperToolLimits(20, 10_000, 200, 10, 12_000, 512, 6, 12_000, 4_000,
                3, 15_000, 1_600, 52_428_800L, 500, 20_000, 900, 5, 12_000, true);
    }
}
