package com.example.ragdemo.paper;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.example.ragdemo.agent.tools.PaperToolException;
import com.example.ragdemo.agent.tools.PaperToolExecutionContext;
import com.example.ragdemo.mapper.PaperCatalogMapper;
import com.example.ragdemo.model.entity.DocumentStatus;
import com.example.ragdemo.model.entity.KnowledgeDocument;
import com.example.ragdemo.rag.ingest.ParseMode;

import lombok.extern.slf4j.Slf4j;

/**
 * 论文目录服务（Step 2 §5.2）：按<b>受信知识库范围</b>分页列出 papers。
 * <p>
 * 只读、稳定排序（{@code created_at DESC, id DESC}）、真实列筛选。
 * <b>不返回</b> {@code storage_path}（MinIO key），也不返回全文。
 * <p>
 * 注意（与 Skill 假设的差异，报告 A 已记录）：{@code documents} 表没有 year/author 列，
 * 因此不支持按年份/作者筛选；论文标题/作者/年份只能从 Canonical 读取（见
 * {@link PaperStructureService}）。
 */
@Slf4j
@Service
public class PaperCatalogService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final PaperCatalogMapper catalogMapper;

    public PaperCatalogService(PaperCatalogMapper catalogMapper) {
        this.catalogMapper = catalogMapper;
    }

    /** 单个论文摘要（documents 真实字段的子集；无 storage_path）。 */
    public record PaperSummary(Long documentId, String fileName, String docKind, String parseMode,
            String fileExt, String contentType, Integer version, String status, Integer chunkCount,
            Long fileSizeBytes, String createdAt, String updatedAt) {
    }

    /** 分页结果。 */
    public record PaperPage(int pageSize, int offset, long total, List<PaperSummary> items,
            List<String> warnings) {
        public PaperPage {
            items = items == null ? List.of() : List.copyOf(items);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * 列出当前知识库内的论文。
     *
     * @param ctx           受信上下文（提供 knowledgeBaseId 与硬上限）
     * @param titleContains 标题（file_name）模糊过滤，可空；仅参数绑定，不做字符串拼接
     * @param status        状态过滤（uploaded/parsing/chunking/embedding/indexed/failed），可空
     * @param parseMode     解析模式过滤（AUTO/ACADEMIC/GENERIC），可空
     * @param pageSize      每页条数（1..listPageSizeMax），可空取默认
     * @param offset        偏移（0..listOffsetMax），可空取 0
     */
    public PaperPage list(PaperToolExecutionContext ctx, String titleContains, String status,
            String parseMode, Integer pageSize, Integer offset) {
        int size = resolvePageSize(ctx, pageSize);
        int off = resolveOffset(ctx, offset);
        String title = normalizeTitleFilter(ctx, titleContains);
        String statusFilter = normalizeStatus(status);
        String modeFilter = normalizeParseMode(parseMode);

        long total = catalogMapper.countByFilter(ctx.knowledgeBaseId(), title, statusFilter, modeFilter);
        List<KnowledgeDocument> rows = catalogMapper.selectPage(ctx.knowledgeBaseId(), title,
                statusFilter, modeFilter, size, off);

        List<PaperSummary> items = new ArrayList<>(rows.size());
        boolean containsNonIndexed = false;
        for (KnowledgeDocument row : rows) {
            items.add(toSummary(row));
            if (!DocumentStatus.INDEXED.getValue().equals(row.getStatus())) {
                containsNonIndexed = true;
            }
        }

        List<String> warnings = new ArrayList<>();
        if (off + items.size() < total) {
            warnings.add("MORE_RESULTS_AVAILABLE: 还有 " + (total - off - items.size())
                    + " 条未返回，可用 offset 继续分页");
        }
        if (containsNonIndexed) {
            warnings.add("INCLUDE_NON_INDEXED: 结果含未完成入库的文档，其 chunk 不参与检索");
        }
        log.debug("[PaperCatalog] list: scope={}, total={}, returned={}, offset={}",
                ctx.describeScope(), total, items.size(), off);
        return new PaperPage(size, off, total, items, warnings);
    }

    /** 读取单条（同样受知识库范围约束；不存在或跨库统一 NOT_FOUND_OR_FORBIDDEN）。 */
    public PaperSummary requireOne(PaperToolExecutionContext ctx, Long documentId) {
        KnowledgeDocument row = catalogMapper.selectDocumentInScope(ctx.knowledgeBaseId(), documentId);
        if (row == null) {
            throw PaperToolException.notFoundOrForbidden("document");
        }
        return toSummary(row);
    }

    // ==================== 参数校验（上限不可被模型提高） ====================

    private int resolvePageSize(PaperToolExecutionContext ctx, Integer pageSize) {
        int max = ctx.limits().listPageSizeMax();
        if (pageSize == null) {
            return max;
        }
        if (pageSize <= 0) {
            throw PaperToolException.invalidArgument("pageSize 必须为正整数");
        }
        if (pageSize > max) {
            throw PaperToolException.limitExceeded("pageSize", pageSize, max);
        }
        return pageSize;
    }

    private int resolveOffset(PaperToolExecutionContext ctx, Integer offset) {
        int max = ctx.limits().listOffsetMax();
        if (offset == null) {
            return 0;
        }
        if (offset < 0) {
            throw PaperToolException.invalidArgument("offset 不能为负数");
        }
        if (offset > max) {
            throw PaperToolException.limitExceeded("offset", offset, max);
        }
        return offset;
    }

    private String normalizeTitleFilter(PaperToolExecutionContext ctx, String titleContains) {
        if (titleContains == null || titleContains.isBlank()) {
            return null;
        }
        String trimmed = titleContains.trim();
        int max = ctx.limits().titleFilterMaxChars();
        if (trimmed.length() > max) {
            throw PaperToolException.limitExceeded("titleContains 长度", trimmed.length(), max);
        }
        return trimmed;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        for (DocumentStatus candidate : DocumentStatus.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate.getValue();
            }
        }
        throw PaperToolException.invalidArgument("status 非法，允许值: uploaded/parsing/chunking/embedding/indexed/failed");
    }

    private String normalizeParseMode(String parseMode) {
        if (parseMode == null || parseMode.isBlank()) {
            return null;
        }
        String normalized = parseMode.trim().toUpperCase(Locale.ROOT);
        for (ParseMode candidate : ParseMode.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate.name();
            }
        }
        throw PaperToolException.invalidArgument("parseMode 非法，允许值: AUTO/ACADEMIC/GENERIC");
    }

    // ==================== 映射（不含 storage_path） ====================

    static PaperSummary toSummary(KnowledgeDocument row) {
        return new PaperSummary(
                row.getId(),
                row.getFileName(),
                docKind(row.getParseMode(), row.getFileExt()),
                row.getParseMode(),
                row.getFileExt(),
                row.getContentType(),
                row.getVersion(),
                row.getStatus(),
                row.getChunkCount(),
                row.getFileSize(),
                row.getCreatedAt() == null ? null : row.getCreatedAt().format(TIME_FORMAT),
                row.getUpdatedAt() == null ? null : row.getUpdatedAt().format(TIME_FORMAT));
    }

    /** 明确文档类型，不把非 PDF / GENERIC 文档称作"学术 PDF"（Step 2 §5.2）。 */
    static String docKind(String parseMode, String fileExt) {
        boolean pdf = isPdfExtension(fileExt);
        if (ParseMode.ACADEMIC.name().equalsIgnoreCase(parseMode)) {
            return pdf ? "ACADEMIC_PDF" : "ACADEMIC_NON_PDF";
        }
        return pdf ? "PDF" : "GENERIC_DOCUMENT";
    }

    static boolean isPdfExtension(String fileExt) {
        return fileExt != null && ".pdf".equalsIgnoreCase(fileExt.trim());
    }
}
