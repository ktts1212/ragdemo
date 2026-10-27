package com.example.ragdemo.agent.tools;

import com.example.ragdemo.agent.tools.model.ToolResult;
import com.example.ragdemo.paper.PaperEvidenceService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tool 6/6：证据定位（Step 2 §5.6）。
 * <p>
 * 只返回证据与提取不确定性：候选匹配、原文片段、页码、标签、来源方法、引用锚点与 warnings。
 * <b>不</b>生成总结/分析结论，<b>不</b>猜测或纠正表格数值，无法定位就返回 EVIDENCE_NOT_FOUND。
 */
public class InspectPaperEvidenceTool extends AbstractPaperTool {

    private static final String TOOL_NAME = "InspectPaperEvidenceTool";

    private final PaperEvidenceService evidenceService;

    public InspectPaperEvidenceTool(PaperToolExecutionContext context, PaperEvidenceService evidenceService) {
        super(context);
        this.evidenceService = evidenceService;
    }

    @Tool("定位表格/图片/公式/段落/关键词的原文证据（返回页码、标签、片段与不确定性）。只给证据，不下结论。")
    public ToolResult<PaperEvidenceService.EvidenceView> inspectEvidence(
            @P("文档 ID，与 pdfHandle 二选一") Long documentId,
            @P("已授权 PDF 句柄，可空") String pdfHandle,
            @P("目标类型 TABLE/FIGURE/EQUATION/PARAGRAPH/KEYWORD") String targetType,
            @P("标签或查询串，如 \"Table 2\" 或关键词") String target,
            @P("可选页码过滤，1-based") Integer pageNumber) {
        return guarded(TOOL_NAME, () -> ToolResult.ok(evidenceService.inspect(context(), documentId, pdfHandle,
                targetType, target, pageNumber)));
    }
}
