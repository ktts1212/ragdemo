package com.example.ragdemo.document.parser.marker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PageRange;
import com.example.ragdemo.document.domain.PaperMetadata;
import com.example.ragdemo.document.domain.ParserInfo;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;
import com.example.ragdemo.document.image.FigureImageStorage;
import com.example.ragdemo.document.marker.raw.MarkerBlock;
import com.example.ragdemo.document.marker.raw.MarkerBlockType;
import com.example.ragdemo.document.marker.raw.MarkerDocument;
import com.example.ragdemo.document.parser.AcademicDocumentParser;
import com.example.ragdemo.document.parser.marker.exception.MarkerDocumentParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Marker Document Tree → {@link AcademicDocument} 结构忠实映射器（skill Phase B）。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>允许：block→domain 映射、page 追踪、block id 保留、HTML→text、Figure/Table/Formula 构建、
 *       Section skeleton、base64 图片外部化</li>
 *   <li>禁止：References 修复、跨页合并、Front matter 推断、Caption 猜测、Back matter 分类、
 *       复杂 hierarchy 修复（属 Repair / Cleaner）</li>
 * </ul>
 * 硬失败仅限：顶层不是 Document / children 为空 / JSON 无法映射（skill §30）。
 * 线程安全：所有单次解析状态放入 {@link MarkerParseContext}。
 */
@Slf4j
@Component
public class MarkerDocumentParser implements AcademicDocumentParser {

    private static final String STRUCTURE_VERSION = "academic-document-v1";
    private static final String PARSER_TYPE = "MARKER";

    private final ObjectMapper objectMapper;
    private final MarkerBlockMapper blockMapper;
    private final MarkerSectionBuilder sectionBuilder;
    private final FigureImageStorage imageStorage;

    public MarkerDocumentParser(ObjectMapper objectMapper,
            MarkerBlockMapper blockMapper,
            MarkerSectionBuilder sectionBuilder,
            FigureImageStorage imageStorage) {
        this.objectMapper = objectMapper;
        this.blockMapper = blockMapper;
        this.sectionBuilder = sectionBuilder;
        this.imageStorage = imageStorage;
    }

    @Override
    public AcademicDocument parse(String documentId, JsonNode rawDocument) {
        long start = System.currentTimeMillis();
        if (documentId == null || documentId.isBlank()) {
            throw new MarkerDocumentParseException("documentId 不能为空");
        }
        if (rawDocument == null || rawDocument.isNull() || rawDocument.isMissingNode()) {
            throw new MarkerDocumentParseException("Marker rawDocument 不能为空");
        }

        MarkerDocument markerDocument = toMarkerDocument(rawDocument);
        validateDocument(markerDocument);

        MarkerParseContext ctx = new MarkerParseContext(documentId, imageStorage);
        traverse(markerDocument.getChildren(), ctx);

        // 真实语料诊断：Marker 偶尔返回"空文档"（有 Page 但无任何子块，常见于扫描件/无文本层 PDF）。
        // 这不是结构损坏（不硬失败），但必须给出明确信号，便于上游判定质量。
        if (ctx.getSections().isEmpty()) {
            log.error("Marker 返回空文档（所有 page 无内容）: documentId={}，"
                    + "可能是扫描件/无文本层 PDF，请检查 PDF 或 Marker 输出", documentId);
        }

        AcademicDocument result = AcademicDocument.builder()
                .documentId(documentId)
                // metadata / abstract / references 由 Cleaner 阶段填充，Parser 不推断
                .metadata(PaperMetadata.builder().build())
                .abstractText(null)
                .sections(ctx.getSections())
                .references(new ArrayList<>())
                .parserInfo(ParserInfo.builder()
                        .parserType(PARSER_TYPE)
                        .parserVersion(null)
                        .structureVersion(STRUCTURE_VERSION)
                        .parsedAt(Instant.now())
                        .build())
                .build();

        // skill §37：只记录统计信息，不输出完整 JSON / 内容
        log.info("Marker 解析完成: documentId={}, sectionCount={}, paragraphCount={}, "
                        + "figureCount={}, tableCount={}, warningCount={}, durationMs={}ms",
                documentId,
                result.getSections().size(),
                countBlocks(result.getSections(), ParagraphBlock.class),
                countBlocks(result.getSections(), FigureBlock.class),
                countBlocks(result.getSections(), TableBlock.class),
                ctx.getWarnings().size(),
                System.currentTimeMillis() - start);
        if (!ctx.getWarnings().isEmpty()) {
            log.debug("Marker 解析 warnings: documentId={}, warnings={}", documentId, ctx.getWarnings());
        }
        return result;
    }

    // ==================== 遍历 ====================

    private void traverse(List<MarkerBlock> blocks, MarkerParseContext ctx) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        for (MarkerBlock raw : blocks) {
            MarkerBlockType type = MarkerBlockType.from(raw.getBlockType());
            PageRange pageRange = ctx.getPageTracker().currentPageRange();

            switch (type) {
                case PAGE -> {
                    ctx.getPageTracker().enterPage();
                    traverse(raw.getChildren(), ctx);
                }
                case SECTION_HEADER -> sectionBuilder.openSection(raw, ctx);
                case TEXT -> sectionBuilder.addBlock(
                        blockMapper.toParagraph(raw, ctx.nextOrder(), pageRange), ctx);
                case FIGURE -> sectionBuilder.addBlock(
                        blockMapper.toFigure(raw, ctx.nextOrder(), pageRange, ctx), ctx);
                // Picture / Diagram 与 Figure 同类
                case PICTURE, DIAGRAM -> sectionBuilder.addBlock(
                        blockMapper.toFigure(raw, ctx.nextOrder(), pageRange, ctx), ctx);
                case TABLE -> sectionBuilder.addBlock(
                        blockMapper.toTable(raw, ctx.nextOrder(), pageRange), ctx);
                case CAPTION -> sectionBuilder.addBlock(
                        blockMapper.toCaption(raw, ctx.nextOrder(), pageRange), ctx);
                case LIST -> sectionBuilder.addBlock(
                        blockMapper.toList(raw, ctx.nextOrder(), pageRange), ctx);
                // Marker 分组容器：ListGroup 含 ListItem（如 References），映射为 ListBlock
                case LIST_GROUP -> sectionBuilder.addBlock(
                        blockMapper.toList(raw, ctx.nextOrder(), pageRange), ctx);
                // TableGroup / FigureGroup 是 Caption + Table/Figure 的包装，递归处理子块即可
                case TABLE_GROUP, FIGURE_GROUP -> traverse(raw.getChildren(), ctx);
                case EQUATION -> sectionBuilder.addBlock(
                        blockMapper.toFormula(raw, ctx.nextOrder(), pageRange), ctx);
                case FOOTNOTE -> sectionBuilder.addBlock(
                        blockMapper.toFootnote(raw, ctx.nextOrder(), pageRange), ctx);
                case PAGE_HEADER -> sectionBuilder.addBlock(
                        blockMapper.toNoise(raw, ctx.nextOrder(), pageRange, "PAGE_HEADER"), ctx);
                case PAGE_FOOTER -> sectionBuilder.addBlock(
                        blockMapper.toNoise(raw, ctx.nextOrder(), pageRange, "PAGE_FOOTER"), ctx);
                case LIST_ITEM -> sectionBuilder.addBlock(
                        blockMapper.toParagraph(raw, ctx.nextOrder(), pageRange), ctx);
                // Reference 条目块：作为段落保留，后续由 ReferenceExtractor 归入 references
                case REFERENCE -> sectionBuilder.addBlock(
                        blockMapper.toParagraph(raw, ctx.nextOrder(), pageRange), ctx);
                case DOCUMENT -> traverse(raw.getChildren(), ctx);
                case UNKNOWN -> {
                    // 未知类型不中断解析（skill §9/§30）；但仍递归子块，避免丢失内容
                    ctx.addWarning("未知 block type: " + raw.getBlockType() + " (id=" + raw.getId() + ")");
                    log.debug("未知 Marker block，递归其子块: type={}, id={}", raw.getBlockType(), raw.getId());
                    traverse(raw.getChildren(), ctx);
                }
            }
        }
    }

    // ==================== 校验 / 反序列化 ====================

    private MarkerDocument toMarkerDocument(JsonNode rawDocument) {
        try {
            return objectMapper.treeToValue(rawDocument, MarkerDocument.class);
        } catch (Exception e) {
            throw new MarkerDocumentParseException(
                    "Marker Document 反序列化失败: " + e.getMessage(), e);
        }
    }

    private void validateDocument(MarkerDocument doc) {
        if (doc == null) {
            throw new MarkerDocumentParseException("Marker Document 为 null");
        }
        if (doc.getBlockType() == null || !"Document".equalsIgnoreCase(doc.getBlockType())) {
            throw new MarkerDocumentParseException(
                    "顶层 block_type 不是 Document: " + doc.getBlockType());
        }
        if (doc.getChildren() == null || doc.getChildren().isEmpty()) {
            throw new MarkerDocumentParseException("Marker Document children 为空");
        }
    }

    // ==================== 统计（日志用） ====================

    private int countBlocks(List<Section> sections, Class<?> type) {
        int count = 0;
        if (sections == null) {
            return 0;
        }
        for (Section section : sections) {
            if (section.getBlocks() != null) {
                for (DocumentBlock block : section.getBlocks()) {
                    if (type.isInstance(block)) {
                        count++;
                    }
                }
            }
            count += countBlocks(section.getChildren(), type);
        }
        return count;
    }
}
