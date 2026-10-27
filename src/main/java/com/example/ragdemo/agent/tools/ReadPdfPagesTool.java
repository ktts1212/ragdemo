package com.example.ragdemo.agent.tools;

import com.example.ragdemo.agent.tools.model.ToolResult;
import com.example.ragdemo.paper.PdfHandleService;
import com.example.ragdemo.paper.PdfPageReadingService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tool 5/6：按页读取原始 PDF（Step 2 §5.5）。
 * <p>
 * 输入只有 pdfHandle + 页码（不接受 URL / 磁盘路径 / bucket / key）。
 * 每次调用都会<b>重新校验句柄授权</b>（领取成功不等于之后一直有效）。
 * 页数/字符数/文件大小/渲染尺寸均有硬上限；图像只给受控句柄，不返回二进制。
 */
public class ReadPdfPagesTool extends AbstractPaperTool {

    private static final String TOOL_NAME = "ReadPdfPagesTool";

    private final PdfHandleService pdfHandleService;
    private final PdfPageReadingService pdfPageReadingService;

    public ReadPdfPagesTool(PaperToolExecutionContext context, PdfHandleService pdfHandleService,
            PdfPageReadingService pdfPageReadingService) {
        super(context);
        this.pdfHandleService = pdfHandleService;
        this.pdfPageReadingService = pdfPageReadingService;
    }

    @Tool("按页读取原始 PDF 的文本层（每次最多 3 页），可选返回受控页面图像句柄。无文本层的页会返回 OCR_REQUIRED。")
    public ToolResult<PdfPageReadingService.PdfPagesView> readPdfPages(
            @P("GetOriginalPdf 返回的 pdfHandle") String pdfHandle,
            @P("起始页，1-based") Integer startPage,
            @P("结束页，1-based；可空表示只读起始页") Integer endPage,
            @P("是否返回页面图像句柄（受控，默认否）") Boolean includePageImage) {
        return guarded(TOOL_NAME, () -> {
            int from = requirePositivePage(startPage, "startPage");
            int to = endPage == null ? from : requirePositivePage(endPage, "endPage");
            if (to < from) {
                throw PaperToolException.invalidArgument("endPage 不能小于 startPage");
            }
            // §5.4：使用前必须重新验证句柄授权（不能只依赖最初领取成功）
            PdfHandleService.VerifiedHandle verified = pdfHandleService.verify(context(), pdfHandle);
            if (!PdfHandleService.KIND_PDF.equals(verified.kind())) {
                throw PaperToolException.invalidArgument("该句柄不是原始 PDF 句柄，不能按页读取");
            }
            return ToolResult.ok(pdfPageReadingService.readPages(context(), verified, from, to,
                    Boolean.TRUE.equals(includePageImage)));
        });
    }

    private int requirePositivePage(Integer page, String name) {
        if (page == null) {
            throw PaperToolException.invalidArgument(name + " 不能为空（1-based）");
        }
        if (page <= 0) {
            throw PaperToolException.invalidArgument(name + " 必须为 1-based 正整数");
        }
        return page;
    }
}
