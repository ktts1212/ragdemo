package com.example.ragdemo.agent.tools;

import com.example.ragdemo.agent.tools.model.ToolResult;
import com.example.ragdemo.paper.PaperCatalogService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tool 2/6：论文目录（Step 2 §5.2）。
 * <p>
 * 只读 {@code documents} 表，强制 {@code knowledge_base_id = 受信 kbId}，稳定排序 + 分页。
 * 不读全文、不返回 MinIO key、不虚构 {@code documents} 表中不存在的年份/作者列。
 */
public class ListPapersTool extends AbstractPaperTool {

    private static final String TOOL_NAME = "ListPapersTool";

    private final PaperCatalogService catalogService;

    public ListPapersTool(PaperToolExecutionContext context, PaperCatalogService catalogService) {
        super(context);
        this.catalogService = catalogService;
    }

    @Tool("列出当前授权知识库中的论文（可选按标题关键字/状态/解析模式过滤，支持分页）。不返回全文。")
    public ToolResult<PaperCatalogService.PaperPage> listPapers(
            @P("标题关键字，可空；为空则不过滤") String titleContains,
            @P("状态过滤 uploaded/parsing/chunking/embedding/indexed/failed，可空") String status,
            @P("解析模式过滤 AUTO/ACADEMIC/GENERIC，可空") String parseMode,
            @P("每页条数，可空（上限 20）") Integer pageSize,
            @P("偏移量，可空（默认 0）") Integer offset) {
        return guarded(TOOL_NAME, () -> ToolResult.ok(catalogService.list(context(), titleContains, status,
                parseMode, pageSize, offset)));
    }
}
