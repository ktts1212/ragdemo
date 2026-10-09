package com.example.ragdemo.document.cleaner;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;

import lombok.extern.slf4j.Slf4j;

/**
 * PaperCleaner 总编排（skill §4/§21）。
 * <p>
 * 调整后的管道顺序：
 * <pre>
 * 1. FrontMatterPreScan        (只观察，在删页眉页脚前收集 DOI/标题/作者/单位/关键词候选)
 * 2. HeaderFooterCleaner       (删 NoiseBlock)
 * 3. FrontMatterCleaner        (A.Title含尾部噪声清洗 B.Authors C.Affiliations D.Keywords E.DOI)
 * 4. AbstractRecovery          (标题式 / 内联式 / 位置式)
 * 5. CaptionRecovery           (双向关联 + 类型约束)
 * 6. ReferenceExtractor        (标题式 / 内容特征式 + 合并条目拆分)
 * 7. BackMatterClassifier      (REMOVE/KEEP)
 * 8. SectionHierarchyNormalizer(标题归一 + 层级告警/保守纠偏)
 * 9. SectionHierarchyRepair    (编号同深度的错误父子 → 平级，skill §5.2)
 * 10. MajorAnchorReparenting   (锚点被错嵌在锚点下 → 提为 root 并收编同级，skill §4)
 * 11. FloatBlockSectionReassigner(Figure/Table 浮动归属：优先正文显式引用)
 * 12. EmptyContentCleaner      (空段落/空caption/空list/空section；空锚点 section 保留)
 * 13. CanonicalDocumentValidator(校验 + 质量指标)
 * </pre>
 * 关键：PreScan 必须在 HeaderFooterCleaner 之前，否则位于 PageFooter 的 DOI 会被先删掉。
 */
@Slf4j
@Component
public class PaperCleaner {

    private final FrontMatterPreScan frontMatterPreScan;
    private final HeaderFooterCleaner headerFooterCleaner;
    private final FrontMatterCleaner frontMatterCleaner;
    private final AbstractRecovery abstractRecovery;
    private final CaptionRecovery captionRecovery;
    private final ReferenceExtractor referenceExtractor;
    private final BackMatterClassifier backMatterClassifier;
    private final SectionHierarchyNormalizer sectionHierarchyNormalizer;
    private final SectionHierarchyRepair sectionHierarchyRepair;
    private final MajorAnchorReparenting majorAnchorReparenting;
    private final FloatBlockSectionReassigner floatBlockSectionReassigner;
    private final EmptyContentCleaner emptyContentCleaner;
    private final CanonicalDocumentValidator canonicalDocumentValidator;

    public PaperCleaner(FrontMatterPreScan frontMatterPreScan,
            HeaderFooterCleaner headerFooterCleaner,
            FrontMatterCleaner frontMatterCleaner,
            AbstractRecovery abstractRecovery,
            CaptionRecovery captionRecovery,
            ReferenceExtractor referenceExtractor,
            BackMatterClassifier backMatterClassifier,
            SectionHierarchyNormalizer sectionHierarchyNormalizer,
            SectionHierarchyRepair sectionHierarchyRepair,
            MajorAnchorReparenting majorAnchorReparenting,
            FloatBlockSectionReassigner floatBlockSectionReassigner,
            EmptyContentCleaner emptyContentCleaner,
            CanonicalDocumentValidator canonicalDocumentValidator) {
        this.frontMatterPreScan = frontMatterPreScan;
        this.headerFooterCleaner = headerFooterCleaner;
        this.frontMatterCleaner = frontMatterCleaner;
        this.abstractRecovery = abstractRecovery;
        this.captionRecovery = captionRecovery;
        this.referenceExtractor = referenceExtractor;
        this.backMatterClassifier = backMatterClassifier;
        this.sectionHierarchyNormalizer = sectionHierarchyNormalizer;
        this.sectionHierarchyRepair = sectionHierarchyRepair;
        this.majorAnchorReparenting = majorAnchorReparenting;
        this.floatBlockSectionReassigner = floatBlockSectionReassigner;
        this.emptyContentCleaner = emptyContentCleaner;
        this.canonicalDocumentValidator = canonicalDocumentValidator;
    }

    /**
     * 执行完整清洗管道。
     *
     * @param document    待清洗文档（原地修改）
     * @param repairCount ReadingOrderRepair 的合并次数（用于质量度量）
     * @return 清洗结果（文档 + warnings + 校验/度量）
     */
    public CleaningResult clean(AcademicDocument document, int repairCount) {
        long start = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        // 1. 预扫描（只观察，不删除）
        FrontMatterContext ctx = frontMatterPreScan.scan(document);
        frontMatterPreScan.fillTitleCandidate(document, ctx);

        int removedHeaders = headerFooterCleaner.clean(document);          // 2
        warnings.addAll(frontMatterCleaner.clean(document, ctx));         // 3
        boolean abstractRecovered = abstractRecovery.recover(document);   // 4
        int captions = captionRecovery.recover(document);                 // 5
        int references = referenceExtractor.extract(document);            // 6
        int backMatterRemoved = backMatterClassifier.classify(document);  // 7
        warnings.addAll(sectionHierarchyNormalizer.normalize(document));  // 8
        // 9 (skill §5.2)：编号同深度的错误父子 → 平级（必须先稳定树，再做浮动归属）
        warnings.addAll(sectionHierarchyRepair.repair(document).warnings());
        // 10 (skill §4)：主章节锚点被错嵌在另一主章节之下 → 提升为 root 并收编其后同级（保守）
        warnings.addAll(majorAnchorReparenting.reparent(document).warnings());
        // 11 (skill §6/§7)：Figure/Table 浮动归属修复
        FloatBlockSectionReassigner.Result floatResult = floatBlockSectionReassigner.reassign(document);
        warnings.addAll(floatResult.warnings());
        int emptyRemoved = emptyContentCleaner.clean(document);           // 12
        ValidationResult validation = canonicalDocumentValidator
                .validate(document, repairCount);                          // 13
        warnings.addAll(validation.warnings());

        // skill §37：每篇论文输出可观测统计
        log.info("PaperCleaner 完成: documentId={}, removedHeaderFooter={}, doiPresent={}, "
                        + "authorCount={}, keywordCount={}, abstractRecovered={}, captionsAssociated={}, "
                        + "referencesExtracted={}, backMatterRemoved={}, emptyRemoved={}, valid={}, "
                        + "warningCount={}, durationMs={}ms",
                document != null ? document.getDocumentId() : null,
                removedHeaders, ctx.hasDoi(),
                document != null && document.getMetadata() != null
                        ? document.getMetadata().getAuthors().size() : 0,
                document != null && document.getMetadata() != null
                        ? document.getMetadata().getKeywords().size() : 0,
                abstractRecovered, captions, references, backMatterRemoved, emptyRemoved,
                validation.valid(), warnings.size(),
                System.currentTimeMillis() - start);

        return new CleaningResult(document, warnings, validation, floatResult.audits());
    }

    /**
     * 无 repair 统计的简化入口。
     */
    public CleaningResult clean(AcademicDocument document) {
        return clean(document, 0);
    }
}
