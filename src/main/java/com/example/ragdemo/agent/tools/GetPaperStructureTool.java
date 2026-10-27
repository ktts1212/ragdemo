package com.example.ragdemo.agent.tools;

import com.example.ragdemo.agent.tools.model.ToolResult;
import com.example.ragdemo.paper.PaperStructureService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tool 3/6：论文结构（Step 2 §5.3）。
 * <p>
 * 默认返回元数据 / 摘要（存在才给）/ 有序章节树 / hasContent / 截断状态；
 * 正文字段必须显式指定 sectionId（或等价 pathKey 锚点）并按上限返回，<b>不默认整篇</b>。
 */
public class GetPaperStructureTool extends AbstractPaperTool {

    private static final String TOOL_NAME = "GetPaperStructureTool";

    private final PaperStructureService structureService;

    public GetPaperStructureTool(PaperToolExecutionContext context, PaperStructureService structureService) {
        super(context);
        this.structureService = structureService;
    }

    @Tool("获取论文元数据（标题/作者/年份/DOI）、摘要存在性与章节树（level/sectionId/pageStart/pageEnd/hasContent）。默认不返回全文。")
    public ToolResult<PaperStructureService.PaperStructureView> getStructure(@P("文档 ID（documents.id）") Long documentId) {
        return guarded(TOOL_NAME, () -> ToolResult.ok(structureService.getStructure(context(), documentId)));
    }

    @Tool("读取指定章节的正文（必须给 sectionId 或 pathKey），受字符上限约束，超限会标记 truncated。")
    public ToolResult<PaperStructureService.SectionContentView> readSection(
            @P("文档 ID（documents.id）") Long documentId,
            @P("章节稳定 id（或章节路径锚点 pathKey）") String sectionId,
            @P("最大返回字符数，可空；不得超过服务端上限") Integer maxChars) {
        return guarded(TOOL_NAME, () -> ToolResult.ok(structureService.readSection(context(), documentId,
                sectionId, maxChars)));
    }
}
