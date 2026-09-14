# Skill: Academic Document Domain Model Generation

## 1. 任务目标

请在现有 Java RAG 项目中新增一套**统一的学术文档领域模型**，用于承接 GROBID 解析后的 TEI XML，并作为后续 `PaperCleaner`、`SectionAwareChunker`、检索元数据构建等模块的统一输入。

这套模型的核心目的不是“把 TEI XML 一比一映射成 Java 类”，而是：

1. 屏蔽 GROBID、MinerU、Docling 等不同文档解析器的输出差异；
2. 保留学术论文真正重要的语义结构；
3. 为后续按 section / paragraph / table / figure 等结构感知分块提供稳定输入；
4. 为页码定位、引用追踪、知识图谱、解析器替换等后续扩展预留能力；
5. 避免 Chunker、Cleaner 等业务模块直接依赖 GROBID 的 TEI 标签。

整体数据流应为：

```text
GROBID
  ↓
TEI XML
  ↓
GrobidDocumentParser
  ↓
AcademicDocument
  ↓
PaperCleaner
  ↓
SectionAwareChunker
  ↓
PaperChunk
```

本 Skill **只负责领域实体类的设计与生成**，暂时不要实现：

- `GrobidDocumentParser`
- XML 解析逻辑
- PaperCleaner
- SectionAwareChunker
- 数据库存储
- MinIO 存储
- Embedding
- Retrieval
- Controller / Service

---

# 2. 设计原则

## 2.1 不要直接复制 TEI XML Schema

不要根据 GROBID TEI 中的：

```text
teiHeader
fileDesc
sourceDesc
analytic
monogr
biblStruct
```

机械地生成一整套 Java 类。

这些属于 GROBID / TEI 的外部数据格式，不应该污染项目内部领域模型。

例如不要设计：

```java
class TeiHeader {}
class FileDesc {}
class SourceDesc {}
class Analytic {}
class Monogr {}
```

本项目需要的是面向 RAG 的内部标准模型。

## 2.2 模型围绕“学术语义”设计

重点保留：

```text
论文身份
论文元数据
摘要
章节层级
正文块
图片
表格
公式
列表
正文引用关系
参考文献
页码范围
解析器信息
```

## 2.3 核心领域类中不要出现 GROBID 专属命名

不要出现：

```text
teiHead
grobidDiv
biblStruct
teiFigure
```

应该使用通用名称：

```text
Section
ParagraphBlock
FigureBlock
Reference
```

未来即使换成 MinerU：

```text
MinerU JSON
   ↓
MinerUDocumentParser
   ↓
AcademicDocument
```

后面的业务代码仍然不需要修改。

## 2.4 所有实体都应尽量保持纯领域对象

除非当前项目已有统一规范，否则：

- 不要在这些类里写数据库操作；
- 不要写 HTTP 调用；
- 不要写 XML 解析；
- 不要写 Chunking；
- 不要直接继承 JPA Entity 基类；
- 不要把领域对象和数据库表强绑定。

如果项目使用 Lombok，可以遵循项目现有 Lombok 风格；如果没有，则生成正常 Java POJO。

---

# 3. 推荐包结构

请优先根据现有项目 package 风格调整。

推荐结构：

```text
src/main/java/.../document/domain/
│
├── AcademicDocument.java
├── PaperMetadata.java
├── Author.java
├── Affiliation.java
├── PartialDate.java
├── Section.java
├── Reference.java
├── CitationMarker.java
├── PageRange.java
├── ParserInfo.java
│
└── block/
    ├── DocumentBlock.java
    ├── ParagraphBlock.java
    ├── FigureBlock.java
    ├── TableBlock.java
    ├── FormulaBlock.java
    └── ListBlock.java
```

如果项目已有 `domain`、`model`、`entity`、`document` 等目录，请保持现有工程风格，不要为了本 Skill 大规模重构目录。

---

# 4. 实体类设计

## 4.1 AcademicDocument

### 含义

代表一篇已经经过文档解析器完成结构化后的**统一学术文档对象**。

它是整个学术文档处理链路的根对象，也是后续 `PaperCleaner` 和 `SectionAwareChunker` 的输入。

它不等价于数据库里的 `documents` 表。

`documents` 表主要代表文件上传、存储和入库状态；`AcademicDocument` 代表：

> “这个 PDF 在语义上被解析成了什么。”

### 建议字段

```java
public class AcademicDocument {
    private String documentId;
    private PaperMetadata metadata;
    private String abstractText;
    private List<Section> sections;
    private List<Reference> references;
    private ParserInfo parserInfo;
}
```

### 字段含义

- `documentId`：当前系统内文档 ID，用于关联数据库中的 document、MinIO 文件以及最终 chunk。
- `metadata`：论文自身的结构化元数据，如标题、作者、机构、DOI、期刊、出版日期。
- `abstractText`：论文摘要正文。当前阶段先保留为 `String`，不要为了结构化摘要过度设计。
- `sections`：论文正文 section 树，这是整个模型中最重要的数据之一。
- `references`：论文末尾的参考文献集合。
- `parserInfo`：记录当前文档由哪个 Parser、哪个版本解析出来。

---

## 4.2 PaperMetadata

### 含义

表示论文自身的身份与出版元数据，主要来源于 GROBID Header 模型的解析结果。

### 建议字段

```java
public class PaperMetadata {
    private String title;
    private List<Author> authors;
    private List<Affiliation> affiliations;
    private List<String> keywords;
    private String doi;
    private String arxivId;
    private String journal;
    private String publisher;
    private PartialDate publicationDate;
}
```

### 注意

不要因为 GROBID Header 可以识别很多字段，就全部加入领域模型。

暂时不要加入电话、传真、编辑姓名、copyright 原始信息、submission note 等低价值字段，除非现有业务明确需要。

---

## 4.3 Author

### 含义

表示论文作者。

作者不建议简单保存成 `List<String>`，因为后续可能需要作者过滤、作者检索、作者与论文关系、ORCID、机构关联和 Knowledge Graph。

### 建议字段

```java
public class Author {
    private String fullName;
    private String firstName;
    private String middleName;
    private String lastName;
    private String email;
    private String orcid;
    private List<String> affiliationIds;
}
```

### 说明

- `fullName`：用于直接展示。
- `firstName / middleName / lastName`：用于结构化查询。
- `affiliationIds`：用于关联本论文中的机构对象。

---

## 4.4 Affiliation

### 含义

表示作者所属机构。

例如：

```text
Department of Computer Science,
University of XXX,
Tokyo, Japan
```

### 建议字段

```java
public class Affiliation {
    private String id;
    private String institution;
    private String department;
    private String address;
    private String country;
}
```

### 原则

第一版不要为了完整表达 TEI affiliation schema 设计几十个字段，保留后续 RAG、检索、作者机构关系可能需要的主要字段即可。

---

## 4.5 PartialDate

### 含义

表示可能不完整的学术日期。

论文中的日期不一定都有 `year-month-day`，可能只有 `2024` 或 `March 2024`，因此直接强制使用 `LocalDate` 容易丢信息。

### 建议字段

```java
public class PartialDate {
    private Integer year;
    private Integer month;
    private Integer day;
    private String rawText;
}
```

### rawText

保留 GROBID 原始识别文本，例如 `March 2024`，避免标准化失败后信息完全丢失。

---

## 4.6 Section

### 含义

表示论文中的一个章节或子章节。

这是后续 section-aware chunking 最重要的领域对象。

例如：

```text
3 Methods
  3.1 Self-supervised Learning
    3.1.1 Contrastive Objective
```

应该形成树结构。

### 建议字段

```java
public class Section {
    private String id;
    private String title;
    private Integer level;
    private List<String> path;
    private Integer order;
    private List<DocumentBlock> blocks;
    private List<Section> children;
}
```

### 字段含义

- `id`：Section 在当前文档中的内部 ID。若 TEI 中存在可稳定复用的 ID，可以在 Parser 层映射，否则解析阶段生成。
- `title`：章节标题，如 `Methods`、`Contrastive Learning`。
- `level`：章节层级，如一级章节、二级章节、三级章节。
- `path`：完整章节路径，例如 `["Methods", "Self-supervised Learning", "Contrastive Objective"]`。不要只保存拼接后的字符串，字符串可后续按需生成。
- `order`：该 section 在论文中的原始顺序，用于恢复论文顺序、Chunk 排序、ContextBuilder 合并相邻 Chunk。
- `blocks`：直属当前 Section 的正文内容。
- `children`：子章节。

---

## 4.7 DocumentBlock

### 含义

`DocumentBlock` 是 Section 内不同类型内容的统一抽象。

不要把正文、图片、表格、公式、列表全部压成一个 String。

### 推荐设计

如果项目 Java 版本和整体风格适合，可使用 sealed interface：

```java
public sealed interface DocumentBlock
        permits ParagraphBlock,
                FigureBlock,
                TableBlock,
                FormulaBlock,
                ListBlock {

    String getId();

    Integer getOrder();

    PageRange getPageRange();
}
```

如果 sealed interface 与现有项目风格冲突，则使用普通 interface。

---

## 4.8 ParagraphBlock

### 含义

表示论文正文中的一个自然段。

这是后续 Chunker 最主要的数据输入单位。

### 建议字段

```java
public class ParagraphBlock implements DocumentBlock {
    private String id;
    private String text;
    private Integer order;
    private PageRange pageRange;
    private List<CitationMarker> citations;
}
```

### 设计原因

SectionAwareChunker 后续应优先按：

```text
Section
→ Paragraph
→ Sentence
→ Token
```

切分，因此 Paragraph 必须作为独立结构保留。

---

## 4.9 FigureBlock

### 含义

表示论文中的普通图片或图。

例如：

```text
Figure 2. Overall architecture of the proposed framework.
```

### 建议字段

```java
public class FigureBlock implements DocumentBlock {
    private String id;
    private String label;
    private String caption;
    private Integer order;
    private PageRange pageRange;
    private String assetPath;
}
```

### assetPath

当前可以为空。

后续如果从 PDF 中提取 Figure，可以保存对应 MinIO object key，例如：

```text
documents/{documentId}/assets/figures/figure-2.png
```

不要保存本地绝对路径。

---

## 4.10 TableBlock

### 含义

表示论文中的表格。

虽然 GROBID TEI 中表格可能通过 `<figure type="table">` 表达，但在内部领域模型中 `Figure != Table`，因此必须拆成独立对象。

### 建议字段

```java
public class TableBlock implements DocumentBlock {
    private String id;
    private String label;
    private String caption;
    private String content;
    private Integer order;
    private PageRange pageRange;
}
```

### content

保存 GROBID 能提供的表格文本表达。以后可以升级成二维表格结构，但当前不要求。

---

## 4.11 FormulaBlock

### 含义

表示论文中的独立公式。

### 建议字段

```java
public class FormulaBlock implements DocumentBlock {
    private String id;
    private String label;
    private String formula;
    private Integer order;
    private PageRange pageRange;
}
```

### 注意

当前只负责存储解析结果，不要在实体类里做 LaTeX 转换、MathML 解析、Embedding 或公式理解。

---

## 4.12 ListBlock

### 含义

表示论文中的列表内容。

例如：

```text
The framework contains:
1. Encoder
2. Projection head
3. Classifier
```

### 建议字段

```java
public class ListBlock implements DocumentBlock {
    private String id;
    private List<String> items;
    private Integer order;
    private PageRange pageRange;
}
```

### 设计原因

列表如果直接打平成长字符串，会损失结构，因此作为独立 Block 保留。

---

## 4.13 CitationMarker

### 含义

表示正文中的文献引用标记。

例如：

```text
Previous studies [12, 15] showed...
```

其中 `[12, 15]` 就是正文 citation marker。

### 建议字段

```java
public class CitationMarker {
    private String text;
    private List<String> targetReferenceIds;
}
```

例如：

```text
text = "[12]"
targetReferenceIds = ["ref-12"]
```

### 为什么现在值得保留

即使当前 RAG 暂时不用，它对以后非常有价值：

```text
正文 Chunk
→ CitationMarker
→ Reference
→ 被引用论文
```

这是未来构建论文引用关系和 Knowledge Graph 的重要基础。

---

## 4.14 Reference

### 含义

表示论文参考文献列表中的一条结构化文献。

它不是正文里的 `[12]`。

正文里的 `[12]` 属于 `CitationMarker`；参考文献末尾真正对应的文献信息属于 `Reference`。

### 建议字段

```java
public class Reference {
    private String id;
    private String rawText;
    private String title;
    private List<String> authors;
    private String journal;
    private PartialDate publicationDate;
    private String volume;
    private String issue;
    private String pages;
    private String doi;
    private String pmid;
    private String pmcId;
    private String arxivId;
    private String url;
}
```

### rawText 必须保留

即使 GROBID 对某条 reference 的字段拆分不完整，原始参考文献文本仍然有价值，因此不要只保存结构化字段。

---

## 4.15 PageRange

### 含义

统一表示某一内容块在原 PDF 中对应的页码范围。

### 建议字段

```java
public class PageRange {
    private Integer startPage;
    private Integer endPage;
}
```

### 使用对象

后续可以被 ParagraphBlock、FigureBlock、TableBlock、FormulaBlock、ListBlock、PaperChunk 复用。

如果 GROBID 当前无法稳定得到页码，则允许 `null`，不要为了填字段而伪造页码。

---

## 4.16 ParserInfo

### 含义

记录该 `AcademicDocument` 是由什么解析器生成的。

用于 Debug、Parser Benchmark、Parser 替换和数据版本追踪。

### 建议字段

```java
public class ParserInfo {
    private String parserType;
    private String parserVersion;
    private String structureVersion;
    private Instant parsedAt;
}
```

例如：

```text
parserType = GROBID
parserVersion = 具体运行版本
structureVersion = academic-document-v1
```

未来 `parserType = MINERU` 时无需修改 `AcademicDocument`。

---

# 5. 最终模型关系

```text
AcademicDocument
│
├── PaperMetadata
│   ├── Author[]
│   ├── Affiliation[]
│   └── PartialDate
│
├── abstractText
│
├── Section[]
│   │
│   ├── title
│   ├── level
│   ├── path
│   ├── order
│   │
│   ├── DocumentBlock[]
│   │   ├── ParagraphBlock
│   │   │   └── CitationMarker[]
│   │   ├── FigureBlock
│   │   ├── TableBlock
│   │   ├── FormulaBlock
│   │   └── ListBlock
│   │
│   └── Section[] children
│
├── Reference[]
│
└── ParserInfo
```

---

# 6. 与 GROBID TEI 的关系

这些类不是凭空设计，而是把 GROBID 不同模型输出的学术语义统一映射进来。

```text
GROBID Segmentation
        ↓
AcademicDocument 整体区域

GROBID Header
        ↓
PaperMetadata
Author
Affiliation
abstractText

GROBID Fulltext
        ↓
Section
ParagraphBlock
FigureBlock
TableBlock
FormulaBlock
ListBlock
CitationMarker

GROBID Bibliographical References
        ↓
Reference

GROBID Date
        ↓
PartialDate
```

后续 `GrobidDocumentParser` 再负责具体的：

```text
TEI XML tag
→ Java domain object
```

映射。

---

# 7. 对象可空性要求

解析器输出的数据天然可能缺失，因此不要把“论文理论上应该有”误认为“解析结果一定存在”。

以下字段应允许缺失：

```text
doi
arxivId
journal
publisher
publicationDate
email
orcid
pageRange
assetPath
caption
```

集合字段建议初始化为空集合，而不是 null，例如：

```java
private List<Author> authors = new ArrayList<>();
```

或使用项目当前统一的不可变空集合风格。

---

# 8. ID 设计原则

不要在这些实体类内部自动生成 UUID。

ID 应该由 `GrobidDocumentParser` 或上层 Document Processing Service 在构建对象时确定。

建议：

```text
AcademicDocument.documentId
→ 使用系统现有 documents.id

Section.id
→ 优先使用可稳定映射的源 ID，否则解析阶段生成

DocumentBlock.id
→ 解析阶段生成

Reference.id
→ 优先保持和 citation target 可关联
```

尤其必须保证：

```text
CitationMarker.targetReferenceIds
```

可以对应：

```text
Reference.id
```

---

# 9. equals/hashCode/toString 注意事项

如果使用 Lombok，不要无脑给所有递归对象使用：

```java
@Data
```

尤其 `Section` 包含 `children`，未来还可能出现 parent reference。

应避免因为 `equals`、`hashCode`、`toString` 递归导致 StackOverflow 或打印巨大对象。

推荐根据项目习惯使用：

```text
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
```

并谨慎生成 `equals/hashCode/toString`。

---

# 10. JSON 序列化要求

这些模型后续会序列化成：

```text
document.json
```

保存到 MinIO。

因此生成实体后必须验证：

```text
AcademicDocument
→ Jackson serialize
→ JSON
→ Jackson deserialize
→ AcademicDocument
```

可以正常往返。

特别注意 `DocumentBlock interface` 存在多态类型。

如果当前直接对接口字段做 Jackson 序列化，需要采用项目合适的多态序列化方案，例如：

```java
@JsonTypeInfo
@JsonSubTypes
```

或者使用统一 `blockType` 字段。

推荐 JSON 中明确存在：

```json
{
  "blockType": "PARAGRAPH"
}
```

或者：

```json
{
  "blockType": "FIGURE"
}
```

以便恢复真实 Block 类型。

---

# 11. 测试要求

## 11.1 AcademicDocument 构建测试

构造：

```text
Paper
└── Methods
    └── Encoder
        ├── Paragraph
        ├── Figure
        └── Formula
```

验证 Section children、Section path、Block 类型、Reference、CitationMarker 都可以正常表达。

## 11.2 Jackson 往返测试

构造完整 `AcademicDocument`：

```text
serialize
↓
JSON
↓
deserialize
```

验证重要字段不丢失，尤其验证 `DocumentBlock` 的具体子类型能够恢复。

## 11.3 Citation 关联测试

构造：

```text
ParagraphBlock
    ↓
CitationMarker
    ↓
targetReferenceIds = ref-12
```

以及：

```text
Reference.id = ref-12
```

确保模型能够表达这种关联。

当前阶段不要求实现业务校验器。

---

# 12. 不要做的事情

本任务中禁止顺手实现：

```text
❌ GrobidDocumentParser
❌ XML XPath / DOM / SAX 解析
❌ GROBID HTTP 调用
❌ PaperCleaner
❌ SectionAwareChunker
❌ PaperChunk
❌ Embedding
❌ PostgreSQL Entity
❌ Mapper
❌ Repository
❌ Controller
❌ Service
❌ MinIO
❌ Kafka
❌ parse_quality
```

本次任务只把：

> **统一学术文档领域模型**

设计正确、生成完整并保证可序列化。

---

# 13. 最终验收标准

完成后项目中应存在可以表达以下论文的数据模型：

```text
Paper
│
├── metadata
│   ├── title
│   ├── authors
│   ├── affiliations
│   ├── DOI
│   └── publication date
│
├── abstract
│
├── Introduction
│   └── paragraphs
│
├── Methods
│   ├── Dataset
│   │   ├── paragraphs
│   │   └── table
│   │
│   └── Model
│       ├── paragraphs
│       ├── figure
│       └── formula
│
├── Results
│
└── references
```

并满足：

```text
✓ 不依赖 GROBID TEI 类
✓ Section 支持递归层级
✓ 不同内容类型保留 Block 语义
✓ 可以记录页码
✓ 可以表达正文 citation → reference 关系
✓ 可以记录 Parser 信息
✓ 可以 JSON 序列化和反序列化
✓ 后续 Chunker 只依赖 AcademicDocument
✓ 后续更换 MinerU 不需要修改 Chunker 的输入模型
```

---

# 14. 实现前检查

生成代码前先检查现有项目：

1. Java 版本；
2. Lombok 是否已使用；
3. Jackson 当前版本和配置；
4. package 命名规范；
5. 当前是否已有 `PageRange`、`Author`、`Reference` 等同名类；
6. 是否已有统一 Base Model；
7. JSON 字段是 camelCase 还是其他规范。

如已有相同概念，优先复用或调整，而不是生成重复领域类。

不要为了执行本 Skill 修改无关代码。
