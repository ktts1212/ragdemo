package com.example.ragdemo.document.domain;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 统一学术文档对象。
 * <p>
 * 代表一篇已经过文档解析器结构化后的论文，是整条学术文档处理链路的根对象，
 * 也是后续 PaperCleaner、SectionAwareChunker 的统一输入。
 * <p>
 * 注意：它不等价于数据库里的 documents 表。documents 表代表文件上传/存储/入库状态，
 * 本对象代表"这个 PDF 在语义上被解析成了什么"。不依赖任何解析器（GROBID/MinerU）专属格式。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcademicDocument {

    /** 系统内文档 ID，用于关联数据库 document、MinIO 文件及最终 chunk */
    private String documentId;

    /** 论文自身结构化元数据：标题、作者、机构、DOI、期刊、出版日期等 */
    private PaperMetadata metadata;

    /** 论文摘要正文（当前阶段保留为 String，不做结构化摘要） */
    private String abstractText;

    /** 论文正文 section 树，整个模型中最核心的数据 */
    @Builder.Default
    private List<Section> sections = new ArrayList<>();

    /** 论文末尾的参考文献集合 */
    @Builder.Default
    private List<Reference> references = new ArrayList<>();

    /** 记录本文档由哪个解析器、哪个版本解析出来 */
    private ParserInfo parserInfo;
}
