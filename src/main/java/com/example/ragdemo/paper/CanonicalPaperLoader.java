package com.example.ragdemo.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.ragdemo.agent.tools.PaperToolException;
import com.example.ragdemo.agent.tools.PaperToolExecutionContext;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.mapper.PaperCatalogMapper;
import com.example.ragdemo.model.entity.KnowledgeDocument;
import com.example.ragdemo.rag.ingest.ParseMode;
import com.example.ragdemo.workflow.ingestion.artifact.IngestionArtifactService;

import lombok.extern.slf4j.Slf4j;

/**
 * Canonical 产物统一加载器（Step 2 §5.3/§5.6 共用）。
 * <p>
 * <b>复用而非重写</b>：canonical.json 的 key、存在性探测、反序列化、provenance sidecar
 * 全部复用入库链路已有的 {@link IngestionArtifactService}，不另起一套读法。
 * <p>
 * <b>授权顺序</b>：先在持久层确认文档属于受信知识库（否则 NOT_FOUND_OR_FORBIDDEN），
 * 再看类型/产物；GENERIC 文档或缺少 canonical 一律 STRUCTURE_NOT_AVAILABLE，
 * <b>绝不伪造</b>章节树。
 * <p>
 * <b>provenance 降级</b>：若 canonical 存在但 meta 校验不匹配（例如清洗逻辑已变更），
 * 不静默误用 —— 读取内容但附加 {@code CANONICAL_PROVENANCE_UNVERIFIED} 警告。
 */
@Slf4j
@Component
public class CanonicalPaperLoader {

    private final PaperCatalogMapper catalogMapper;
    private final IngestionArtifactService artifacts;

    public CanonicalPaperLoader(PaperCatalogMapper catalogMapper, IngestionArtifactService artifacts) {
        this.catalogMapper = catalogMapper;
        this.artifacts = artifacts;
    }

    /** 加载结果：文档行 + canonical 结构 + provenance 信息 + 警告。 */
    public record LoadedCanonical(KnowledgeDocument document, AcademicDocument canonical,
            String parserBackend, Integer workflowVersion, List<String> warnings) {
        public LoadedCanonical {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public LoadedCanonical load(PaperToolExecutionContext ctx, long documentId) {
        KnowledgeDocument row = catalogMapper.selectDocumentInScope(ctx.knowledgeBaseId(), documentId);
        if (row == null) {
            throw PaperToolException.notFoundOrForbidden("document");
        }
        int version = row.getVersion() == null ? 1 : row.getVersion();

        if (!ParseMode.ACADEMIC.name().equalsIgnoreCase(row.getParseMode())) {
            throw PaperToolException.structureNotAvailable(
                    "该文档不是学术链路（parseMode=" + row.getParseMode() + "），没有章节结构");
        }
        String canonicalKey = artifacts.canonicalKey(documentId, version);
        if (!artifacts.exists(canonicalKey)) {
            throw PaperToolException.structureNotAvailable("该文档尚无 canonical 产物（可能还未完成解析入库）");
        }

        List<String> warnings = new ArrayList<>();
        String parserBackend = null;
        Integer workflowVersion = null;
        boolean reusable = false;
        try {
            Optional<IngestionArtifactService.CanonicalMeta> meta =
                    artifacts.readCanonicalMeta(documentId, version);
            if (meta.isPresent()) {
                parserBackend = meta.get().parserBackend();
                workflowVersion = meta.get().workflowVersion();
            }
            reusable = artifacts.isCanonicalReusable(documentId, version, row.getChecksum(), canonicalKey);
        } catch (RuntimeException e) {
            warnings.add("CANONICAL_META_UNREADABLE");
        }
        if (!reusable) {
            warnings.add("CANONICAL_PROVENANCE_UNVERIFIED: canonical 存在但 provenance 校验未通过，"
                    + "内容按「未核验」处理");
        }

        AcademicDocument canonical;
        try {
            canonical = artifacts.readCanonical(canonicalKey);
        } catch (RuntimeException e) {
            throw PaperToolException.structureNotAvailable("canonical 结构无法反序列化，已安全拒绝");
        }
        if (canonical == null) {
            throw PaperToolException.structureNotAvailable("canonical 为空");
        }
        log.debug("[CanonicalLoader] loaded: scope={}, documentId={}, version={}, reusable={}",
                ctx.describeScope(), documentId, version, reusable);
        return new LoadedCanonical(row, canonical, parserBackend, workflowVersion, warnings);
    }
}
