package com.example.ragdemo.agent.runtime;

import java.util.ArrayList;
import java.util.List;

import com.example.ragdemo.agent.model.AgentSource;
import com.example.ragdemo.agent.model.AgentSource.LocatorView;
import com.example.ragdemo.agent.tools.model.EvidenceRef;
import com.example.ragdemo.agent.tools.model.ExtractionMethod;
import com.example.ragdemo.agent.tools.model.Locator;
import com.example.ragdemo.agent.tools.model.SourceType;
import com.example.ragdemo.agent.tools.model.ToolResult;
import com.example.ragdemo.paper.PaperCatalogService;
import com.example.ragdemo.paper.PaperEvidenceService;
import com.example.ragdemo.paper.PaperStructureService;
import com.example.ragdemo.paper.PdfHandleService;
import com.example.ragdemo.paper.PdfPageReadingService;

/**
 * 从工具结果中提取可审计来源与句柄（Step 3 §6）。
 * <p>
 * 只搬运工具真实返回值：页码保持 null 不臆造、{@code verifiedAgainstOriginal} 原样复制、
 * 表格 TSV 的 warnings 一并保留；PDF 文本层来源额外标注"文本读取 ≠ 视觉核验"。
 */
public final class EvidenceExtractor {

    /** 提取结果：来源列表 + 可继续使用的受控句柄（PDF 句柄 / 页面图像句柄）。 */
    public record Extraction(List<AgentSource> sources, List<String> handles) {
        public Extraction {
            sources = sources == null ? List.of() : List.copyOf(sources);
            handles = handles == null ? List.of() : List.copyOf(handles);
        }

        public static Extraction empty() {
            return new Extraction(List.of(), List.of());
        }
    }

    private EvidenceExtractor() {
    }

    public static Extraction extract(ToolResult<?> result, int excerptChars) {
        return extract(result, excerptChars, null);
    }

    /**
     * @param fallbackDocumentId 当工具结果本身不含 documentId（如 readSection 的返回）时，
     *                           用本次调用的受信参数补齐，保证来源可审计；补齐不到则保持 null，不臆造。
     */
    public static Extraction extract(ToolResult<?> result, int excerptChars, Long fallbackDocumentId) {
        if (result == null || !result.ok() || result.data() == null) {
            return Extraction.empty();
        }
        Object payload = result.data();
        if (payload instanceof com.example.ragdemo.agent.tools.SearchPaperTool.SearchView view) {
            return fromSearch(view, excerptChars);
        }
        if (payload instanceof PaperEvidenceService.EvidenceView view) {
            return fromEvidence(view, excerptChars, fallbackDocumentId);
        }
        if (payload instanceof PaperStructureService.SectionContentView view) {
            return fromSection(view, excerptChars, fallbackDocumentId);
        }
        if (payload instanceof PdfPageReadingService.PdfPagesView view) {
            return fromPdfPages(view, excerptChars);
        }
        if (payload instanceof PaperCatalogService.PaperPage page) {
            return fromCatalog(page);
        }
        if (payload instanceof PdfHandleService.IssuedHandle handle) {
            return new Extraction(List.of(), List.of(handle.handle()));
        }
        return Extraction.empty();
    }

    private static Extraction fromSearch(com.example.ragdemo.agent.tools.SearchPaperTool.SearchView view,
            int excerptChars) {
        List<AgentSource> sources = new ArrayList<>();
        for (var hit : view.hits()) {
            sources.add(new AgentSource(hit.documentId(), hit.documentName(), null, hit.page(),
                    SourceType.CHUNK.name(),
                    new LocatorView("CHUNK", null, null, null, List.of(), null, hit.chunkIndex(), null),
                    ExtractionMethod.INDEXED_CHUNK.name(), false, hit.truncated(),
                    List.of("CHUNK_RETRIEVAL: 向量检索命中，页码来自索引，未与原文核验"),
                    cap(hit.contentPreview(), excerptChars)));
        }
        return new Extraction(sources, List.of());
    }

    private static Extraction fromEvidence(PaperEvidenceService.EvidenceView view, int excerptChars,
            Long fallbackDocumentId) {
        List<AgentSource> sources = new ArrayList<>();
        for (EvidenceRef ref : view.items()) {
            Locator locator = ref.locator();
            Long documentId = ref.documentId() == null ? fallbackDocumentId : ref.documentId();
            sources.add(new AgentSource(documentId, null, ref.documentVersion(), ref.pageNumber(),
                    ref.sourceType() == null ? null : ref.sourceType().name(),
                    locator == null ? null : new LocatorView(locator.type(), locator.label(), locator.blockId(),
                            locator.sectionId(), locator.sectionPath(), locator.chunkKey(), locator.chunkIndex(),
                            locator.order()),
                    ref.extractionMethod() == null ? null : ref.extractionMethod().name(),
                    ref.verifiedAgainstOriginal(), ref.truncated(), ref.warnings(),
                    cap(ref.content(), excerptChars)));
        }
        return new Extraction(sources, List.of());
    }

    private static Extraction fromSection(PaperStructureService.SectionContentView view, int excerptChars,
            Long fallbackDocumentId) {
        AgentSource source = new AgentSource(fallbackDocumentId, null, null, view.pageStart(),
                SourceType.CANONICAL.name(),
                new LocatorView("SECTION", null, null, view.sectionId(), view.path(), null, null, view.level()),
                ExtractionMethod.CANONICAL_PARSE.name(), false, view.truncated(),
                concat(List.of("CANONICAL_SECTION: 章节正文来自 Canonical 解析产物，未与原始 PDF 逐字核验"),
                        view.warnings()),
                cap(view.content(), excerptChars));
        return new Extraction(List.of(source), List.of());
    }

    private static Extraction fromPdfPages(PdfPageReadingService.PdfPagesView view, int excerptChars) {
        List<AgentSource> sources = new ArrayList<>();
        for (PdfPageReadingService.PdfPageView page : view.pages()) {
            sources.add(new AgentSource(view.documentId(), view.fileName(), view.documentVersion(),
                    page.pageNumber(), SourceType.ORIGINAL_PDF.name(),
                    new LocatorView("PAGE", null, null, null, List.of(), null, null, null),
                    ExtractionMethod.PDF_TEXT.name(),
                    // 文本层就是原文，但"读到文本"不等于"视觉核验表格"
                    true, page.truncated(),
                    concat(List.of("NOT_VISUAL_VERIFICATION: 文本层读取不等于表格/图像视觉核验"), page.warnings()),
                    cap(page.text(), excerptChars)));
        }
        List<String> handles = new ArrayList<>();
        if (view.pageImage() != null && view.pageImage().imageHandle() != null) {
            handles.add(view.pageImage().imageHandle());
        }
        return new Extraction(sources, handles);
    }

    private static Extraction fromCatalog(PaperCatalogService.PaperPage page) {
        List<AgentSource> sources = new ArrayList<>();
        for (PaperCatalogService.PaperSummary item : page.items()) {
            sources.add(new AgentSource(item.documentId(), item.fileName(), item.version(), null,
                    SourceType.CATALOG.name(),
                    new LocatorView("CATALOG", item.docKind(), null, null, List.of(), null, null, null),
                    ExtractionMethod.CATALOG_QUERY.name(), false, false,
                    List.of("CATALOG_ENTRY: 目录元数据，非正文证据"),
                    item.fileName() + " [" + item.docKind() + ", " + item.status() + "]"));
        }
        return new Extraction(sources, List.of());
    }

    private static String cap(String text, int excerptChars) {
        if (text == null) {
            return null;
        }
        return text.length() > excerptChars ? text.substring(0, excerptChars) : text;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>(first);
        if (second != null) {
            merged.addAll(second);
        }
        return merged;
    }
}
