package com.example.ragdemo.infrastructure.grobid;

/**
 * GROBID 客户端接口：定义暴露给上层 ingestion 服务的能力。
 * <p>
 * 本层只负责与外部 GROBID Docker 服务的 HTTP 通信，输出 TEI XML 字符串。
 * 不包含 TEI 解析、分块、embedding、存储等逻辑。
 */
public interface GrobidClient {

    /**
     * 调用 GROBID processFulltextDocument 解析 PDF 全文。
     *
     * @param pdfBytes PDF 文件字节内容，不能为 null 或空
     * @param fileName PDF 文件名（用于日志与 multipart 文件名）
     * @return 解析结果（TEI XML 字符串 + 耗时），不会返回 null
     * @throws com.example.ragdemo.infrastructure.grobid.exception.GrobidException
     *         以及其子类：超时/不可用/处理失败/无效响应
     */
    GrobidResponse processFulltext(byte[] pdfBytes, String fileName);

    /**
     * GROBID 存活检查（GET /api/isalive）。
     * 轻量调用，任何异常都视为不可用返回 false，不抛异常。
     */
    boolean isAlive();

    /**
     * GROBID 引擎池健康检查（GET /api/health）。
     * 主要用于诊断/Actuator 集成，不应在每次 PDF 请求前调用。
     *
     * @throws com.example.ragdemo.infrastructure.grobid.exception.GrobidUnavailableException
     *         GROBID 不可达或健康检查失败时抛出
     */
    GrobidHealth health();
}
