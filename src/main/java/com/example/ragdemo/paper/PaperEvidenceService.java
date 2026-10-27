package com.example.ragdemo.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.example.ragdemo.agent.tools.PaperToolException;
import com.example.ragdemo.agent.tools.PaperToolExecutionContext;
import com.example.ragdemo.agent.tools.model.EvidenceRef;
import com.example.ragdemo.agent.tools.model.ExtractionMethod;
import com.example.ragdemo.agent.tools.model.Locator;
import com.example.ragdemo.agent.tools.model.SourceType;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.FormulaBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;
import com.example.ragdemo.model.entity.KnowledgeDocument;

import lombok.extern.slf4j.Slf4j;

/**
 * 原始证据定位服务（Step 2 §5.6）。
 * <p>
 * <b>只产证据，不下结论</b>：本服务不总结、不比较、不换算、不判断"哪个版本正确"。
 * <ul>
 *   <li>表格数值取自 Marker Canonical 的 TSV（解析产物原文），<b>不猜测、不补齐、不改写</b>；
 *       每一条都带 {@code VERIFY_AGAINST_ORIGINAL_PDF} 提示，因为本步未与原始 PDF 逐字核验；</li>
 *   <li>页码只来自 Canonical block 的 pageRange；取不到即为 null 并标记 {@code UNVERIFIED_ALIGNMENT}；</li>
 *   <li>定位不到返回 {@code EVIDENCE_NOT_FOUND}，绝不编造一条。</li>
 * </ul>
 */
@Slf4j
@Service
public class PaperEvidenceService {

    /** 目标类型（对外契约字符串）。 */
    public enum TargetType {
        TABLE, FIGURE, EQUATION, PARAGRAPH, KEYWORD
    }

    private static final int MAX_TARGET_CHARS = 200;
    private static final String WARN_VERIFY_ORIGINAL =
            "VERIFY_AGAINST_ORIGINAL_PDF: 证据来自 Canonical 解析产物，未与原始 PDF 逐字核验";
    private static final String WARN_TABLE_TSV =
            "TABLE_VALUES_FROM_CANONICAL_TSV: 表格内容为解析产物 TSV，未经核对，不得当作已校正数值";

    private final CanonicalPaperLoader loader;
    private final PdfHandleService pdfHandleService;

    public PaperEvidenceService(CanonicalPaperLoader loader, PdfHandleService pdfHandleService) {
        this.loader = loader;
        this.pdfHandleService = pdfHandleService;
    }

    /** 一次证据查询结果。 */
    public record EvidenceView(String targetType, String target, Integer pageNumber, int totalMatched,
            boolean truncated, boolean alignmentVerified, List<EvidenceRef> items, List<String> warnings) {
        public EvidenceView {
            items = items == null ? List.of() : List.copyOf(items);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * 定位证据。
     *
     * @param ctx          受信上下文
     * @param documentId   文档 ID（与 pdfHandle 二选一；两者都给时以 pdfHandle 校验结果为准）
     * @param pdfHandle    已授权句柄（可选，等价入口）
     * @param targetType   TABLE / FIGURE / EQUATION / PARAGRAPH / KEYWORD
     * @param target       标签或查询串（如 "Table 2"、"ECG-JEPA"）
     * @param pageNumber   可选页码过滤（1-based；仅在页码可确定时命中）
     */
    public EvidenceView inspect(PaperToolExecutionContext ctx, Long documentId, String pdfHandle,
            String targetType, String target, Integer pageNumber) {
        long resolvedDocumentId = resolveDocumentId(ctx, documentId, pdfHandle);
        TargetType type = parseTargetType(targetType);
        String targetEcho = normalizeTarget(target);
        String needle = targetEcho.toLowerCase(Locale.ROOT);
        if (pageNumber != null && pageNumber <= 0) {
            throw PaperToolException.invalidArgument("pageNumber 必须为 1-based 正整数");
        }

        CanonicalPaperLoader.LoadedCanonical loaded = loader.load(ctx, resolvedDocumentId);
        KnowledgeDocument row = loaded.document();
        AcademicDocument canonical = loaded.canonical();

        List<Match> matches = new ArrayList<>();
        collect(canonical.getSections() == null ? List.of() : canonical.getSections(), type, needle,
                pageNumber, matches);

        if (matches.isEmpty()) {
            throw PaperToolException.evidenceNotFound(
                    "未在 canonical 结构中定位到 " + type.name() + " 目标（不编造证据）");
        }

        int maxItems = ctx.limits().evidenceMaxItems();
        int textBudget = ctx.limits().evidenceTextMaxChars();
        List<EvidenceRef> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>(loaded.warnings());
        for (Match match : matches) {
            if (items.size() >= maxItems || textBudget <= 0) {
                break;
            }
            String text = CanonicalBlockRenderer.render(match.block());
            boolean truncated = text.length() > textBudget;
            String content = truncated ? text.substring(0, textBudget) : text;
            textBudget -= content.length();

            List<String> itemWarnings = new ArrayList<>();
            itemWarnings.add(WARN_VERIFY_ORIGINAL);
            if (match.block() instanceof TableBlock) {
                itemWarnings.add(WARN_TABLE_TSV);
            }
            Integer page = CanonicalBlockRenderer.startPage(match.block());
            if (page == null) {
                itemWarnings.add("UNVERIFIED_ALIGNMENT: 该证据无法从解析产物确定页码");
            }

            List<String> sectionPath = match.sectionPath();
            Locator locator = new Locator(
                    CanonicalBlockRenderer.blockType(match.block()),
                    CanonicalBlockRenderer.label(match.block()),
                    match.block().getId(),
                    match.sectionId(),
                    sectionPath,
                    null, null,
                    match.block().getOrder());
            items.add(new EvidenceRef(row.getId(), row.getVersion(), SourceType.CANONICAL, page, locator,
                    ExtractionMethod.CANONICAL_PARSE, content, truncated, false, itemWarnings));
        }

        boolean truncated = matches.size() > items.size();
        if (truncated) {
            warnings.add("EVIDENCE_TRUNCATED: 命中 " + matches.size() + " 条，仅返回前 " + items.size() + " 条");
        }
        boolean alignmentVerified = items.stream().allMatch(item -> item.pageNumber() != null);
        if (!alignmentVerified) {
            warnings.add("UNVERIFIED_ALIGNMENT: 部分证据无法确定页码，未与原始 PDF 对齐");
        }

        log.info("[Evidence] inspect: scope={}, documentId={}, type={}, matched={}, returned={}",
                ctx.describeScope(), resolvedDocumentId, type, matches.size(), items.size());
        return new EvidenceView(type.name(), targetEcho, pageNumber, matches.size(), truncated,
                alignmentVerified, items, warnings);
    }

    // ==================== 内部 ====================

    private record Match(DocumentBlock block, String sectionId, List<String> sectionPath) {
    }

    private void collect(List<Section> sections, TargetType type, String needle, Integer pageNumber,
            List<Match> matches) {
        for (Section section : sections) {
            for (DocumentBlock block : section.getBlocks() == null ? List.<DocumentBlock>of()
                    : section.getBlocks()) {
                if (!matchesType(block, type, needle)) {
                    continue;
                }
                if (pageNumber != null && !CanonicalBlockRenderer.coversPage(block, pageNumber)) {
                    continue;
                }
                matches.add(new Match(block, section.getId(),
                        section.getPath() == null ? List.of() : section.getPath()));
            }
            collect(section.getChildren() == null ? List.of() : section.getChildren(), type, needle,
                    pageNumber, matches);
        }
    }

    private boolean matchesType(DocumentBlock block, TargetType type, String needleLower) {
        return switch (type) {
            case TABLE -> block instanceof TableBlock table
                    && (contains(table.getLabel(), needleLower) || contains(table.getCaption(), needleLower)
                            || contains(table.getContent(), needleLower) || contains(table.getHtml(), needleLower));
            case FIGURE -> block instanceof FigureBlock figure
                    && (contains(figure.getLabel(), needleLower) || contains(figure.getCaption(), needleLower)
                            || contains(figure.getDescriptionText(), needleLower));
            case EQUATION -> block instanceof FormulaBlock formula
                    && (contains(formula.getLabel(), needleLower) || contains(formula.getFormula(), needleLower));
            case PARAGRAPH -> block instanceof ParagraphBlock paragraph
                    && contains(paragraph.getText(), needleLower);
            case KEYWORD -> CanonicalBlockRenderer.isContentBlock(block)
                    && contains(CanonicalBlockRenderer.render(block), needleLower);
        };
    }

    private static boolean contains(String haystack, String needleLower) {
        return haystack != null && !haystack.isEmpty()
                && haystack.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private long resolveDocumentId(PaperToolExecutionContext ctx, Long documentId, String pdfHandle) {
        if (pdfHandle != null && !pdfHandle.isBlank()) {
            PdfHandleService.VerifiedHandle verified = pdfHandleService.verify(ctx, pdfHandle);
            if (!PdfHandleService.KIND_PDF.equals(verified.kind())) {
                throw PaperToolException.invalidArgument("该句柄不是原始 PDF 句柄");
            }
            return verified.documentId();
        }
        if (documentId == null || documentId <= 0) {
            throw PaperToolException.invalidArgument("必须提供 documentId 或 pdfHandle");
        }
        return documentId;
    }

    private TargetType parseTargetType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw PaperToolException.invalidArgument("targetType 必须为 TABLE/FIGURE/EQUATION/PARAGRAPH/KEYWORD");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (TargetType candidate : TargetType.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        throw PaperToolException.invalidArgument("非法 targetType: " + raw
                + "，允许值 TABLE/FIGURE/EQUATION/PARAGRAPH/KEYWORD");
    }

    private String normalizeTarget(String target) {
        if (target == null || target.isBlank()) {
            throw PaperToolException.invalidArgument("target 不能为空");
        }
        String trimmed = target.trim();
        if (trimmed.length() > MAX_TARGET_CHARS) {
            throw PaperToolException.limitExceeded("target 长度", trimmed.length(), MAX_TARGET_CHARS);
        }
        return trimmed;
    }
}
