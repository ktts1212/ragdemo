package com.example.ragdemo.agent.tools;

import java.util.List;

import com.example.ragdemo.agent.tools.model.ToolResult;
import com.example.ragdemo.paper.PdfHandleService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tool 4/6：原始 PDF 授权句柄（Step 2 §5.4）。
 * <p>
 * 只返回服务端签发的 opaque 句柄与展示信息：<b>不含</b> MinIO bucket/objectKey、不含可访问直链、
 * 不含 PDF 字节。句柄绑定知识库 + 文档 + 版本，短期有效，且每次使用都会重新授权。
 */
public class GetOriginalPdfTool extends AbstractPaperTool {

    private static final String TOOL_NAME = "GetOriginalPdfTool";

    private final PdfHandleService pdfHandleService;

    public GetOriginalPdfTool(PaperToolExecutionContext context, PdfHandleService pdfHandleService) {
        super(context);
        this.pdfHandleService = pdfHandleService;
    }

    @Tool("获取原始 PDF 的授权句柄（不含链接；后续用 ReadPdfPages 按页读取）。仅 PDF 文档可用。")
    public ToolResult<PdfHandleService.IssuedHandle> getOriginalPdf(@P("文档 ID（documents.id）") Long documentId) {
        return guarded(TOOL_NAME, () -> {
            PdfHandleService.IssuedHandle issued = pdfHandleService.issuePdfHandle(context(), documentId);
            return ToolResult.ok(issued, List.of(
                    "OPAQUE_HANDLE: 句柄为服务端短期凭证，不含可访问 URL/MinIO 路径，请勿跨知识库使用",
                    "HANDLE_TTL: 有效约 " + limits().handleTtlSeconds() + " 秒；重启服务后全部失效"));
        });
    }
}
