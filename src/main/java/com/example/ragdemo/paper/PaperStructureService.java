package com.example.ragdemo.paper;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.ragdemo.agent.tools.PaperToolException;
import com.example.ragdemo.agent.tools.PaperToolExecutionContext;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Author;
import com.example.ragdemo.document.domain.PaperMetadata;
import com.example.ragdemo.document.domain.PartialDate;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.model.entity.KnowledgeDocument;

import lombok.extern.slf4j.Slf4j;

/**
 * 论文结构服务（Step 2 §5.3）：从 canonical.json 提供元数据 / 摘要 / 章节树 / 受限正文。
 * <p>
 * 边界：
 * <ul>
 *   <li><b>默认不返回全文</b>：正文必须显式指定 sectionId（或等价稳定锚点）才返回，且受字符上限；</li>
 *   <li>章节树深度受 {@code structureDepthMax} 限制，截断时明确标记；</li>
 *   <li>页码取 Canonical block 的 pageRange（解析器真实值），取不到一律 null，<b>不推断</b>；</li>
 *   <li>{@code sectionId} 缺失时不伪造 id，返回 null 并给出警告；此时可用 {@code pathKey} 作为等价锚点。</li>
 * </ul>
 */
@Slf4j
@Service
public class PaperStructureService {

    private static final int MAX_LOCATOR_BLOCKS = 100;

    private final CanonicalPaperLoader loader;

    public PaperStructureService(CanonicalPaperLoader loader) {
        this.loader = loader;
    }

    // ==================== 对外结果 ====================

    /** 章节树节点。 */
    public record SectionNode(String sectionId, String pathKey, String title, Integer level,
            List<String> path, Integer pageStart, Integer pageEnd, boolean hasContent, int blockCount,
            int childCount, List<SectionNode> children) {
        public SectionNode {
            path = path == null ? List.of() : List.copyOf(path);
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    /** 正文块定位（用于追溯，不含全文）。 */
    public record SectionContentBlock(String blockId, String blockType, String label, Integer pageStart,
            Integer pageEnd, int charCount) {
    }

    /** 结构总览（默认不含全文）。 */
    public record PaperStructureView(long documentId, Integer documentVersion, String fileName,
            String docKind, String title, List<String> authors, Integer publicationYear, String doi,
            String arxivId, String journal, String parserType, String parserBackend,
            Integer canonicalWorkflowVersion, boolean abstractPresent, String abstractPreview,
            boolean abstractTruncated, int sectionCount, boolean treeTruncated, List<SectionNode> sections,
            List<String> warnings) {
        public PaperStructureView {
            authors = authors == null ? List.of() : List.copyOf(authors);
            sections = sections == null ? List.of() : List.copyOf(sections);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** 受限章节正文。 */
    public record SectionContentView(String sectionId, String pathKey, String title, List<String> path,
            Integer level, Integer pageStart, Integer pageEnd, String content, int charCount,
            boolean truncated, List<SectionContentBlock> blocks, List<String> warnings) {
        public SectionContentView {
            path = path == null ? List.of() : List.copyOf(path);
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    // ==================== 结构总览 ====================

    public PaperStructureView getStructure(PaperToolExecutionContext ctx, Long documentId) {
        if (documentId == null || documentId <= 0) {
            throw PaperToolException.invalidArgument("documentId 必须为正整数");
        }
        CanonicalPaperLoader.LoadedCanonical loaded = loader.load(ctx, documentId);
        KnowledgeDocument row = loaded.document();
        AcademicDocument canonical = loaded.canonical();
        List<String> warnings = new ArrayList<>(loaded.warnings());

        int maxDepth = ctx.limits().structureDepthMax();
        boolean[] truncatedFlag = {false};
        List<SectionNode> nodes = new ArrayList<>();
        List<Section> roots = canonical.getSections() == null ? List.of() : canonical.getSections();
        for (Section section : roots) {
            nodes.add(toNode(section, 1, maxDepth, truncatedFlag));
        }
        if (truncatedFlag[0]) {
            warnings.add("TREE_DEPTH_TRUNCATED: 章节树深度超过 " + maxDepth + " 层，已截断");
        }
        if (nodes.stream().anyMatch(node -> node.sectionId() == null)) {
            warnings.add("SECTION_ID_MISSING: 部分章节缺少稳定 id，请改用 pathKey 作为锚点");
        }

        PaperMetadata metadata = canonical.getMetadata();
        List<String> authors = new ArrayList<>();
        Integer year = null;
        if (metadata != null) {
            if (metadata.getAuthors() != null) {
                for (Author author : metadata.getAuthors()) {
                    if (author != null && author.getFullName() != null && !author.getFullName().isBlank()) {
                        authors.add(author.getFullName());
                    }
                }
            }
            year = yearOf(metadata.getPublicationDate());
        }

        String abstractText = canonical.getAbstractText();
        boolean abstractPresent = abstractText != null && !abstractText.isBlank();
        int abstractCap = ctx.limits().structureAbstractMaxChars();
        String abstractPreview = null;
        boolean abstractTruncated = false;
        if (abstractPresent) {
            abstractPreview = abstractText.length() > abstractCap
                    ? abstractText.substring(0, abstractCap)
                    : abstractText;
            abstractTruncated = abstractText.length() > abstractCap;
            if (abstractTruncated) {
                warnings.add("ABSTRACT_TRUNCATED: 摘要超过 " + abstractCap + " 字符，已截断");
            }
        }

        return new PaperStructureView(
                row.getId(), row.getVersion(), row.getFileName(),
                PaperCatalogService.docKind(row.getParseMode(), row.getFileExt()),
                metadata == null ? null : metadata.getTitle(),
                authors, year,
                metadata == null ? null : metadata.getDoi(),
                metadata == null ? null : metadata.getArxivId(),
                metadata == null ? null : metadata.getJournal(),
                canonical.getParserInfo() == null ? null : canonical.getParserInfo().getParserType(),
                loaded.parserBackend(), loaded.workflowVersion(),
                abstractPresent, abstractPreview, abstractTruncated,
                countNodes(nodes), truncatedFlag[0], nodes, warnings);
    }

    // ==================== 受限正文 ====================

    public SectionContentView readSection(PaperToolExecutionContext ctx, Long documentId, String sectionId,
            Integer maxChars) {
        if (documentId == null || documentId <= 0) {
            throw PaperToolException.invalidArgument("documentId 必须为正整数");
        }
        if (sectionId == null || sectionId.isBlank()) {
            throw PaperToolException.invalidArgument("必须指定 sectionId（或 pathKey 锚点）");
        }
        int cap = ctx.limits().structureTextMaxChars();
        if (maxChars != null) {
            if (maxChars <= 0) {
                throw PaperToolException.invalidArgument("maxChars 必须为正整数");
            }
            if (maxChars > cap) {
                throw PaperToolException.limitExceeded("maxChars", maxChars, cap);
            }
            cap = maxChars;
        }

        CanonicalPaperLoader.LoadedCanonical loaded = loader.load(ctx, documentId);
        AcademicDocument canonical = loaded.canonical();
        Section target = findSection(canonical.getSections() == null ? List.of() : canonical.getSections(),
                sectionId.trim());
        if (target == null) {
            // 与"不存在文档"一致：不暴露该 documentId 下有哪些 section
            throw PaperToolException.notFoundOrForbidden("section");
        }

        List<String> warnings = new ArrayList<>(loaded.warnings());
        StringBuilder content = new StringBuilder();
        List<SectionContentBlock> blocks = new ArrayList<>();
        boolean truncated = false;
        List<DocumentBlock> sectionBlocks = target.getBlocks() == null ? List.of() : target.getBlocks();
        for (DocumentBlock block : sectionBlocks) {
            if (!CanonicalBlockRenderer.isContentBlock(block)) {
                continue;
            }
            String text = CanonicalBlockRenderer.render(block);
            if (text.isBlank()) {
                continue;
            }
            if (blocks.size() >= MAX_LOCATOR_BLOCKS) {
                truncated = true;
                warnings.add("BLOCK_LIST_TRUNCATED: 定位块列表超过 " + MAX_LOCATOR_BLOCKS + " 条，已截断");
                break;
            }
            String piece = (content.length() == 0 ? "" : "\n\n") + text;
            if (content.length() + piece.length() > cap) {
                int remaining = cap - content.length();
                if (remaining > 0) {
                    content.append(piece, 0, remaining);
                }
                truncated = true;
                warnings.add("TEXT_TRUNCATED: 已达单次章节正文字符上限 " + cap);
                break;
            }
            content.append(piece);
            blocks.add(new SectionContentBlock(block.getId(), CanonicalBlockRenderer.blockType(block),
                    CanonicalBlockRenderer.label(block), CanonicalBlockRenderer.startPage(block),
                    CanonicalBlockRenderer.endPage(block), text.length()));
        }

        List<String> path = target.getPath() == null ? List.of() : target.getPath();
        return new SectionContentView(target.getId(), CanonicalBlockRenderer.pathKey(path),
                target.getTitle(), path, target.getLevel(),
                minPageStart(sectionBlocks), maxPageEnd(sectionBlocks),
                content.toString(), content.length(), truncated, blocks, warnings);
    }

    // ==================== 内部 ====================

    private SectionNode toNode(Section section, int depth, int maxDepth, boolean[] truncated) {
        List<SectionNode> children = new ArrayList<>();
        List<Section> rawChildren = section.getChildren() == null ? List.of() : section.getChildren();
        if (depth >= maxDepth) {
            if (!rawChildren.isEmpty()) {
                truncated[0] = true;
            }
        } else {
            for (Section child : rawChildren) {
                children.add(toNode(child, depth + 1, maxDepth, truncated));
            }
        }

        List<DocumentBlock> blocks = section.getBlocks() == null ? List.of() : section.getBlocks();
        int blockCount = 0;
        for (DocumentBlock block : blocks) {
            if (CanonicalBlockRenderer.isContentBlock(block)) {
                blockCount++;
            }
        }
        boolean hasContent = blockCount > 0 || children.stream().anyMatch(SectionNode::hasContent);

        Integer pageStart = minPageStart(blocks);
        Integer pageEnd = maxPageEnd(blocks);
        for (SectionNode child : children) {
            if (child.pageStart() != null && (pageStart == null || child.pageStart() < pageStart)) {
                pageStart = child.pageStart();
            }
            if (child.pageEnd() != null && (pageEnd == null || child.pageEnd() > pageEnd)) {
                pageEnd = child.pageEnd();
            }
        }

        List<String> path = section.getPath() == null ? List.of() : section.getPath();
        return new SectionNode(section.getId(), CanonicalBlockRenderer.pathKey(path), section.getTitle(),
                section.getLevel(), path, pageStart, pageEnd, hasContent, blockCount, children.size(), children);
    }

    private Integer minPageStart(List<DocumentBlock> blocks) {
        Integer min = null;
        for (DocumentBlock block : blocks) {
            Integer page = CanonicalBlockRenderer.startPage(block);
            if (page != null && (min == null || page < min)) {
                min = page;
            }
        }
        return min;
    }

    private Integer maxPageEnd(List<DocumentBlock> blocks) {
        Integer max = null;
        for (DocumentBlock block : blocks) {
            Integer page = CanonicalBlockRenderer.endPage(block);
            if (page != null && (max == null || page > max)) {
                max = page;
            }
        }
        return max;
    }

    /** 按 sectionId，其次按 pathKey 精确匹配（两者都必须来自 canonical 自身，不做模糊猜测）。 */
    private Section findSection(List<Section> sections, String anchor) {
        for (Section section : sections) {
            if (anchor.equals(section.getId())) {
                return section;
            }
            List<String> path = section.getPath() == null ? List.of() : section.getPath();
            if (anchor.equals(CanonicalBlockRenderer.pathKey(path))) {
                return section;
            }
            Section nested = findSection(section.getChildren() == null ? List.of() : section.getChildren(),
                    anchor);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static int countNodes(List<SectionNode> nodes) {
        int total = 0;
        for (SectionNode node : nodes) {
            total += 1 + countNodes(node.children());
        }
        return total;
    }

    private static Integer yearOf(PartialDate date) {
        return date == null ? null : date.getYear();
    }
}
