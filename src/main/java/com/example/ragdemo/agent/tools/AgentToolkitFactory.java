package com.example.ragdemo.agent.tools;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ragdemo.paper.PaperCatalogService;
import com.example.ragdemo.paper.PaperEvidenceService;
import com.example.ragdemo.paper.PaperStructureService;
import com.example.ragdemo.paper.PdfHandleService;
import com.example.ragdemo.paper.PdfPageReadingService;
import com.example.ragdemo.retrieval.DocumentRetrievalService;

/**
 * 六个学术 Tool 的<b>按 Run 工厂</b>（Step 2 §3/§6）。
 * <p>
 * <b>为什么用工厂而不是单例工具</b>：{@code knowledgeBaseId} 绝不能是模型参数。本工厂在每次
 * Run 开始时按受信上下文创建一组工具实例（工具持有上下文引用），从而：
 * <ul>
 *   <li>不依赖 ThreadLocal / 单例存"当前知识库"，并发请求之间不会串库；</li>
 *   <li>Service 层不持有当前知识库状态，只接收显式传入的上下文；</li>
 *   <li>本步不启动 Graph、不注册 Agent，只产出可独立调用与测试的工具对象。</li>
 * </ul>
 */
@Component
public class AgentToolkitFactory {

    private final PaperToolProperties properties;
    private final DocumentRetrievalService retrievalService;
    private final PaperCatalogService catalogService;
    private final PaperStructureService structureService;
    private final PdfHandleService pdfHandleService;
    private final PdfPageReadingService pdfPageReadingService;
    private final PaperEvidenceService evidenceService;

    public AgentToolkitFactory(PaperToolProperties properties, DocumentRetrievalService retrievalService,
            PaperCatalogService catalogService, PaperStructureService structureService,
            PdfHandleService pdfHandleService, PdfPageReadingService pdfPageReadingService,
            PaperEvidenceService evidenceService) {
        this.properties = properties;
        this.retrievalService = retrievalService;
        this.catalogService = catalogService;
        this.structureService = structureService;
        this.pdfHandleService = pdfHandleService;
        this.pdfPageReadingService = pdfPageReadingService;
        this.evidenceService = evidenceService;
    }

    /** 受信应用层用：显式给出知识库范围（可带 principal/conversation）。 */
    public AgentToolkit create(PaperToolExecutionContext context) {
        return new AgentToolkit(context, retrievalService, catalogService, structureService, pdfHandleService,
                pdfPageReadingService, evidenceService);
    }

    /** 便捷入口：由受信应用层提供 kbId 与调用者身份，上限取服务端配置。 */
    public AgentToolkit createForRun(long knowledgeBaseId, String principalId, String conversationId) {
        PaperToolExecutionContext context = PaperToolExecutionContext.builder()
                .knowledgeBaseId(knowledgeBaseId)
                .principalId(principalId)
                .conversationId(conversationId)
                .limits(properties.toLimits())
                .build();
        return create(context);
    }

    /** 一次 Run 绑定的一组工具（Step 3 注册 Agent 时使用 {@link #toolObjects()}）。 */
    public static final class AgentToolkit {

        private final PaperToolExecutionContext context;
        private final SearchPaperTool searchPaperTool;
        private final ListPapersTool listPapersTool;
        private final GetPaperStructureTool getPaperStructureTool;
        private final GetOriginalPdfTool getOriginalPdfTool;
        private final ReadPdfPagesTool readPdfPagesTool;
        private final InspectPaperEvidenceTool inspectPaperEvidenceTool;

        AgentToolkit(PaperToolExecutionContext context, DocumentRetrievalService retrievalService,
                PaperCatalogService catalogService, PaperStructureService structureService,
                PdfHandleService pdfHandleService, PdfPageReadingService pdfPageReadingService,
                PaperEvidenceService evidenceService) {
            this.context = context;
            this.searchPaperTool = new SearchPaperTool(context, retrievalService);
            this.listPapersTool = new ListPapersTool(context, catalogService);
            this.getPaperStructureTool = new GetPaperStructureTool(context, structureService);
            this.getOriginalPdfTool = new GetOriginalPdfTool(context, pdfHandleService);
            this.readPdfPagesTool = new ReadPdfPagesTool(context, pdfHandleService, pdfPageReadingService);
            this.inspectPaperEvidenceTool = new InspectPaperEvidenceTool(context, evidenceService);
        }

        public PaperToolExecutionContext context() {
            return context;
        }

        /** 供 LangChain4j 反射生成 ToolSpecification 的工具对象列表（本步不注册 Graph）。 */
        public List<Object> toolObjects() {
            return List.of(searchPaperTool, listPapersTool, getPaperStructureTool, getOriginalPdfTool,
                    readPdfPagesTool, inspectPaperEvidenceTool);
        }

        public SearchPaperTool searchPaperTool() {
            return searchPaperTool;
        }

        public ListPapersTool listPapersTool() {
            return listPapersTool;
        }

        public GetPaperStructureTool getPaperStructureTool() {
            return getPaperStructureTool;
        }

        public GetOriginalPdfTool getOriginalPdfTool() {
            return getOriginalPdfTool;
        }

        public ReadPdfPagesTool readPdfPagesTool() {
            return readPdfPagesTool;
        }

        public InspectPaperEvidenceTool inspectPaperEvidenceTool() {
            return inspectPaperEvidenceTool;
        }
    }
}
