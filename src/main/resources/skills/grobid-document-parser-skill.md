# Skill: Implement GrobidDocumentParser

## 1. 任务目标

请在现有 Java RAG 项目中实现 `GrobidDocumentParser`，负责将 GROBID `/api/processFulltextDocument` 返回的 **TEI XML 字符串**转换成项目内部已经存在的统一学术文档领域模型 `AcademicDocument`。

职责边界必须明确：

```text
GrobidClient
PDF bytes
   ↓
HTTP 调用 GROBID
   ↓
TEI XML String

GrobidDocumentParser
TEI XML String
   ↓
AcademicDocument
```

后续链路：

```text
TEI XML
   ↓
GrobidDocumentParser
   ↓
AcademicDocument
   ↓
ParseQualityValidator
   ↓
PaperCleaner
   ↓
SectionHierarchyResolver（如需要）
   ↓
SectionAwareChunker
```

本 Skill 只实现 **TEI XML → AcademicDocument 的忠实、可测试、可扩展映射**。

---

## 2. 当前项目已有领域模型

项目已有：

```text
document/domain/
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
└── block/
    ├── DocumentBlock.java
    ├── ParagraphBlock.java
    ├── FigureBlock.java
    ├── TableBlock.java
    ├── FormulaBlock.java
    └── ListBlock.java
```

本次实现必须优先适配这些现有类，不要重新生成 `GrobidPaper`、`TeiSection`、`TeiParagraph` 等重复领域模型。

---

## 3. 推荐包结构

```text
src/main/java/com/example/ragdemo/document/parser/grobid/
├── GrobidDocumentParser.java
├── GrobidTeiConstants.java               # 可选
├── TeiNamespaceContext.java              # 使用 XPath 时可选
└── exception/
    └── GrobidDocumentParseException.java
```

`infrastructure/grobid/` 仍然只负责 HTTP Client、配置、响应和 HTTP 异常，不要把 `GrobidDocumentParser` 放入 Client 层。

---

## 4. 推荐公开接口

优先：

```java
@Component
public class GrobidDocumentParser {

    public AcademicDocument parse(String documentId, String teiXml) {
        ...
    }
}
```

- `documentId`：系统现有 `documents.id`
- `teiXml`：`GrobidClient` 返回的完整 TEI XML String

不要尝试从 TEI 中生成系统 documentId。

---

## 5. 核心设计原则

### 5.1 Parser 只负责忠实映射

即使 GROBID 存在摘要/正文边界切分错误，也不要在 `GrobidDocumentParser` 中自动修复。

禁止：

```text
❌ 用 LLM 判断摘要边界
❌ 根据语义移动段落
❌ 猜 Introduction
❌ 自动修复 Section hierarchy
❌ 改写正文
```

这些属于 `ParseQualityValidator`、`PaperCleaner`、`SectionHierarchyResolver`。

### 5.2 缺字段不等于整篇失败

以下字段缺失应允许：

```text
doi
journal
publisher
abstract
references
pageRange
ORCID
publicationDate
```

只有 TEI 为空、XML 非法、根结构无法识别等情况才抛解析异常。

### 5.3 不要把 TEI 标签机械映射成同名 Java 类

例如：

```xml
<figure type="table">
```

必须映射为 `TableBlock`，不是 `FigureBlock`。

---

## 6. XML 解析要求

### 6.1 Namespace-aware

GROBID TEI 使用默认 namespace：

```xml
xmlns="http://www.tei-c.org/ns/1.0"
```

所以必须：

```java
DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
factory.setNamespaceAware(true);
```

如果使用 XPath，为 TEI namespace 建立 `tei` prefix。

推荐 XPath：

```text
/tei:TEI/tei:teiHeader
/tei:TEI/tei:text/tei:body
```

### 6.2 防止 XXE

至少考虑：

```java
factory.setFeature(
    "http://apache.org/xml/features/disallow-doctype-decl",
    true
);
factory.setFeature(
    "http://xml.org/sax/features/external-general-entities",
    false
);
factory.setFeature(
    "http://xml.org/sax/features/external-parameter-entities",
    false
);
factory.setXIncludeAware(false);
factory.setExpandEntityReferences(false);
```

按项目 JDK / XML Provider 采用等价安全配置。

### 6.3 推荐 DOM + XPath

当前场景优先使用 DOM + XPath + 小型 helper。

不要用 JAXB 全量生成 TEI Schema 类。

---

## 7. parse() 顶层流程

不要写成一个几百行巨型方法。

推荐：

```java
public AcademicDocument parse(String documentId, String teiXml) {
    Document tei = parseXml(teiXml);

    PaperMetadata metadata = parseMetadata(tei);
    String abstractText = parseAbstract(tei);
    List<Section> sections = parseBodySections(tei);
    List<Reference> references = parseReferences(tei);
    ParserInfo parserInfo = parseParserInfo(tei);

    return buildAcademicDocument(
        documentId,
        metadata,
        abstractText,
        sections,
        references,
        parserInfo
    );
}
```

建议拆分：

```text
parseMetadata()
parseAuthors()
parseAffiliations()
parsePublicationDate()
parseAbstract()
parseBodySections()
parseSection()
parseParagraph()
parseCitationMarkers()
parseFigure()
parseTable()
parseFormula()
parseList()
parseReferences()
parseReferenceAuthors()
parseParserInfo()
```

---

## 8. PaperMetadata 映射

### title

优先从当前论文 Header 的主标题读取：

```text
teiHeader/fileDesc/sourceDesc/biblStruct/analytic/title[@level='a'][@type='main']
```

如不存在，再 fallback 到：

```text
teiHeader/fileDesc/titleStmt/title[@type='main']
```

不要全局找第一个 `<title>`，references 中也有大量 title。

### DOI

从 Header 当前论文 `biblStruct` 的：

```xml
<idno type="DOI">...</idno>
```

提取。不要把 reference DOI 当成当前论文 DOI。

### arXiv / journal / publisher / keywords

只根据 Header 中明确存在的对应节点映射；不存在则 null / empty list，不要推断。

---

## 9. Author 解析

只从：

```text
teiHeader
→ sourceDesc
→ 当前论文 biblStruct
→ analytic
→ author
```

解析。

不要使用全局 `//author`，否则会把参考文献作者混入论文作者。

映射：

```text
Author.fullName
Author.firstName
Author.middleName
Author.lastName
Author.orcid
Author.email
Author.affiliationIds
```

`fullName` 应根据结构化姓名字段拼接，不要对整个 `<author>` 调 `getTextContent()`，否则 affiliation 文本也会混进去。

---

## 10. Affiliation 解析

同一个 `key="aff0"` 可能被多个作者重复出现。

`PaperMetadata.affiliations` 必须去重，推荐：

```java
LinkedHashMap<String, Affiliation>
```

映射：

```text
id          ← affiliation@key
department  ← orgName[@type='department']
institution ← orgName[@type='institution']
address     ← address 的规范化文本
country     ← country
```

如果一个 affiliation 中有多个 institution，而实体只有一个 String，则使用统一、稳定、可测试的拼接规则，例如 `; ` 分隔，不要静默覆盖。

---

## 11. PartialDate

日期可能只有：

```text
2024
March 2024
2024-03-15
```

因此：

```text
year/month/day
```

允许 null，`rawText` 尽量保留。

不要强制转换成 `LocalDate`。

---

## 12. Abstract 解析

优先读取：

```text
teiHeader/profileDesc/abstract
```

下所有 `<p>`，按 XML 顺序合并，例如：

```text
paragraph1 + "\n\n" + paragraph2
```

如没有 `<p>`，可 fallback 到 abstract 的规范化 text content。

注意：即使摘要疑似混入 Introduction，也要忠实映射 TEI，不要在 Parser 修边界。

---

## 13. 正文 Section 解析

入口：

```text
/TEI/text/body
```

### 13.1 保持原始顺序

Section、Paragraph、Figure、Table、Formula 的 XML 顺序必须保留。

### 13.2 真正嵌套 div

如果 XML 是：

```xml
<div>
    <head>Methods</head>
    <p>...</p>
    <div>
        <head>Dataset</head>
        <p>...</p>
    </div>
</div>
```

才自然映射为：

```text
Methods
└── Dataset
```

并设置 children / level / path。

### 13.3 平铺 sibling 不强猜层级

真实 GROBID 输出可能是：

```xml
<div><head>Methods</head></div>
<div><head>Ethics</head><p>...</p></div>
<div><head>Data sources</head><p>...</p></div>
```

即使语义上 `Ethics`、`Data sources` 可能属于 Methods，Parser 也不得强猜。

应忠实映射为 ordered sibling sections。

后续 `SectionHierarchyResolver` 再恢复语义层级。

### 13.4 无标题 div 不丢正文

如果：

```xml
<div><p>...</p></div>
```

没有 `<head>`，也必须生成 Section 或等价容器，`title = null`，保留正文。

禁止自动命名为 `Introduction`。

---

## 14. Section / Block order

必须基于 XML 文档顺序。

禁止：

```text
先收集全部 p
再收集全部 figure
再收集全部 formula
```

这样会破坏原始顺序。

Section 中应遍历直接 child node：

```text
p       → ParagraphBlock
figure  → FigureBlock / TableBlock
formula → FormulaBlock
list    → ListBlock
div     → child Section
```

未知元素不要默认让整篇解析失败；按明确规则忽略或 debug log。

---

## 15. ParagraphBlock + mixed content

TEI `<p>` 是 mixed content，例如：

```xml
<p>
  HeartBEiT
  <ref type="bibr" target="#b8">8</ref>
  leverages ...
</p>
```

必须同时生成：

```text
ParagraphBlock.text
ParagraphBlock.citations
```

### 可见文本

遍历 child nodes：

```text
TEXT_NODE → append
ref       → append ref 可见文本，同时解析 citation（仅 bibr）
其他 inline element → 递归读取 visible text
```

只做轻度 whitespace normalize。

### CitationMarker

仅对：

```xml
<ref type="bibr" target="#b8">8</ref>
```

创建：

```text
text = "8"
targetReferenceIds = ["b8"]
```

`#b8` 统一规范化为 `b8`。

`type=figure/table/formula/url` 当前不要错误映射成 CitationMarker，但必须保留其可见文本。

---

## 16. FigureBlock

对于普通 figure：

```xml
<figure xml:id="fig_0">
  <head>Fig. 2 |</head>
  <label>2</label>
  <figDesc>...</figDesc>
  <graphic .../>
</figure>
```

映射：

```text
id       ← xml:id
label    ← label
caption  ← figDesc / head
order
pageRange
assetPath
```

有 `xml:id` 时不要随机生成 UUID。

`assetPath` 只有真实完成图片抽取并上传 MinIO 后才填；不要把 coords 或虚构路径写入。

---

## 17. TableBlock

当：

```xml
<figure type="table" xml:id="tab_0">
```

必须映射成 `TableBlock`。

```text
id       ← xml:id
label    ← label
caption  ← figDesc / head
content  ← table 的稳定文本表示
order
pageRange
```

`TableBlock.content` 建议用 TSV：

```text
Model\tPTB-XL\tChapman
ECG-LFM\t0.951\t0.993
```

规则：

- row 之间 `\n`
- cell 之间 `\t`
- 保持 row/cell 顺序
- 不要直接 `table.getTextContent()` 把 cell 黏成一团
- 暂不使用 Markdown 表格

---

## 18. FormulaBlock

例如：

```xml
<formula xml:id="formula_0">
    ...formula body...
    <label>(1)</label>
</formula>
```

映射：

```text
id      ← xml:id
label   ← label
formula ← 除 label 外的公式可见文本
order
pageRange
```

避免 `formula` 和 `label` 重复保存编号。

---

## 19. ListBlock

如果：

```xml
<list>
    <item>...</item>
    <item>...</item>
</list>
```

映射到 `ListBlock.items`，保持 item 顺序。

---

## 20. PageRange

当前领域模型支持 PageRange，但不能伪造页码。

只有 TEI 元素存在 `coords="page,x,y,w,h"` 等坐标信息时才解析。

```text
有 coords  → startPage/endPage
无 coords  → pageRange = null
```

如果一个 block 有多个 coords：

```text
startPage = min(page)
endPage = max(page)
```

注意：具体 page 编号语义应以当前 GROBID 版本输出为准，并写测试确认。

---

## 21. Reference 解析

只从：

```text
text/back
→ div[@type='references']
→ listBibl
→ biblStruct
```

解析。

不要全局搜索 `biblStruct`，Header 自己也有当前论文的 biblStruct。

映射：

```text
Reference.id
Reference.rawText
Reference.title
Reference.authors
Reference.journal
Reference.publicationDate
Reference.volume
Reference.issue
Reference.pages
Reference.doi
Reference.pmid
Reference.pmcId
Reference.arxivId
Reference.url
```

### ID

优先使用 `xml:id`，例如 `b1`、`b8`，必须与 `CitationMarker.targetReferenceIds` 对齐。

### title

优先：

```text
analytic/title[@level='a']
```

如不存在，再根据 TEI 结构 fallback 到合适的 monogr/title；不要把 journal title误当论文 title。

### authors

只读取当前 biblStruct 内作者。

### journal

通常取：

```text
monogr/title[@level='j']
```

### DOI / PMID / PMC / arXiv

读取当前 reference 内对应 `idno@type`。

### URL

读取 `ptr@target` 或明确 URL ref。

### rawText

如果请求 `includeRawCitations=false`，TEI 不一定保留逐字原始 citation。

处理策略：

1. 有 raw citation 节点 → 优先使用；
2. 没有 → 用当前 biblStruct 的规范化可见文本作为 fallback；
3. 代码注释说明 fallback 不是原 PDF 逐字 citation；
4. 不要因此丢掉整条 Reference。

---

## 22. ParserInfo

从：

```text
teiHeader/encodingDesc/appInfo/application
```

读取：

```xml
<application version="0.9.0" ident="GROBID" when="...">
```

映射：

```text
parserType    ← ident
parserVersion ← version
parsedAt      ← when
```

`structureVersion` 是项目内部模型版本，例如：

```text
academic-document-v1
```

不要从 GROBID 参数中伪造 structureVersion。

---

## 23. 暂不映射区域

真实 TEI 可能还有：

```text
div type="conflict"
div type="contribution"
div type="annex"
acknowledgement
availability
footnote
headnote
```

当前实体没有统一 DocumentNote / Annex 模型，因此：

- 不要硬塞进 PaperMetadata；
- 不要临时新增大量实体；
- body 中正常 section 结构按现有 Section 保留；
- back 中非 references 的附加结构按明确规则忽略并 debug log。

后续业务需要再扩展。

---

## 24. 文本规范化

Parser 只做轻度 normalize，例如：

```text
连续空格
tab
纯格式换行
```

不要在 Parser 做：

```text
❌ 拼写修正
❌ 智能断词恢复
❌ 语义改写
❌ Abstract/Introduction 边界修复
❌ References 清洗
```

这些属于 `PaperCleaner`。

---

## 25. XML ID helper

TEI `xml:id` 属于 XML namespace：

```text
http://www.w3.org/XML/1998/namespace
```

建议统一：

```java
getXmlId(Element element)
```

内部使用：

```java
getAttributeNS(XMLConstants.XML_NS_URI, "id")
```

---

## 26. target ID helper

统一实现：

```java
normalizeTargetId(String target)
```

把：

```text
#b8 → b8
```

集中处理 null、空串和多个 target，不要在多个方法散落 `substring(1)`。

---

## 27. 异常设计

新增：

```java
GrobidDocumentParseException
```

仅用于：

```text
TEI为空
XML不是well-formed
根节点不是可识别TEI
XML解析器初始化失败
关键XML结构完全非法
```

不要因为 title / DOI / abstract / reference / pageRange 缺失就整篇抛错。

---

## 28. 日志

不要记录完整 TEI 或完整论文文本。

建议记录：

```text
documentId
titlePresent
authorCount
sectionCount
referenceCount
parserVersion
durationMs
```

不要：

```java
log.info("teiXml={}", teiXml);
```

---

## 29. 真实测试 fixture

把当前真实 GROBID 输出保存为：

```text
src/test/resources/grobid/ecg-lfm.tei.xml
```

另外补：

```text
minimal.tei.xml
nested-sections.tei.xml
missing-metadata.tei.xml
invalid.tei.xml
```

测试必须主要基于真实 TEI，而不是只用几行人工 XML。

---

## 30. 当前 ECG-LFM 真实样例必须覆盖的测试点

### metadata

验证：

```text
title
DOI = 10.1038/s41467-026-72436-2
parserType = GROBID
parserVersion = 0.9.0
```

### author

至少验证第一个作者：

```text
firstName = Siying
lastName = Lin
ORCID = 0000-0002-5677-2128
```

并验证 affiliation 文本没有混入 fullName。

### affiliation

相同 affiliation key 被多位作者使用时，`PaperMetadata.affiliations` 应去重。

### abstract

忠实提取 TEI abstract，不自动修复当前样例存在的可疑 abstract/body 边界。

### 无标题 body div

body 开头存在无 `<head>` 的 `<div>`，必须保留正文，不能丢失。

### section 顺序

保持：

```text
Results
Overview of this study
...
Discussion
Methods
Ethics
Data sources
...
```

等 TEI 原始顺序。

不要测试“Methods 自动成为 Data sources 父节点”，因为当前真实 TEI 并未提供这种嵌套。

### citation

`<ref type="bibr" target="#b8">8</ref>`：

```text
保留 8 到 paragraph text
并生成 CitationMarker → b8
```

### figure

至少正确解析一个 FigureBlock，并保留 xml:id。

### table

当前样例 `type="table" xml:id="tab_0"` 必须解析成 TableBlock，并验证 label / caption / TSV content。

### formula

当前样例 `xml:id="formula_0"` 必须解析成 FormulaBlock，公式正文和 label 不重复。

### reference

至少验证 `Reference.id = b1`，并验证某个 CitationMarker target（如 b8）可对应 `Reference.id = b8`。

### pageRange

当前样例未开启 TEI coordinates，因此允许 pageRange 为 null，禁止伪造页码。

---

## 31. 单元测试建议

```text
shouldParseMetadata()
shouldParseAuthorsAndAffiliations()
shouldParseAbstract()
shouldPreserveUnlabeledBodyContent()
shouldPreserveSectionOrder()
shouldParseParagraphCitationMarkers()
shouldParseFigure()
shouldParseTable()
shouldParseFormula()
shouldParseReferences()
shouldParseParserInfo()
shouldAllowMissingOptionalFields()
shouldRejectMalformedXml()
```

---

## 32. Section hierarchy 专项测试

手写一个小型：

```text
nested-sections.tei.xml
```

包含真正 XML 嵌套：

```xml
<body>
    <div>
        <head>Methods</head>
        <p>A</p>
        <div>
            <head>Dataset</head>
            <p>B</p>
        </div>
    </div>
</body>
```

验证：

```text
Methods.children 包含 Dataset
level/path 正确
```

同时真实 ECG-LFM fixture 验证：

> 平铺 sibling 不做语义猜层级。

---

## 33. Parser 与 Cleaner 的职责边界测试

当前 ECG-LFM 样例中的 abstract/body 边界疑似异常。

测试必须明确：

```text
GrobidDocumentParser 按 TEI 忠实映射
```

不要要求 Parser 得到“人工认为正确”的 Abstract。

后续 `ParseQualityValidator`、`PaperCleaner` 单独处理。

---

## 34. 推荐内部 helper

可以集中在 `GrobidDocumentParser` private methods：

```text
parseXml()
evaluateNode()
evaluateNodes()
directChildElements()
normalizeWhitespace()
getXmlId()
attribute()
normalizeTargetId()
parseCoordsPageRange()
parseVisibleMixedText()
parsePartialDate()
```

不要为每个 helper 创建一个 util 类。

---

## 35. 性能与线程安全

Parser 是纯 XML → Java 对象转换。

不要在内部：

```text
parallelStream
线程池
异步任务
```

Kafka 层已经负责文档级并发。

如果 `GrobidDocumentParser` 是 Spring singleton Bean，必须保持 stateless / thread-safe：

- 不要把当前 Document 存为实例字段；
- 不要把 currentSection / currentOrder / currentDocumentId 存为实例字段；
- 所有单次解析状态放局部变量或 private ParseContext。

---

## 36. 本任务禁止顺手实现

```text
❌ GrobidClient
❌ HTTP
❌ Kafka
❌ MinIO
❌ document.json 持久化
❌ ParseQualityValidator
❌ Abstract 边界修复
❌ PaperCleaner
❌ SectionHierarchyResolver 的语义推断
❌ SectionAwareChunker
❌ Embedding
❌ pgvector
❌ LLM
```

---

## 37. 最终验收

实现后：

```java
AcademicDocument document =
    grobidDocumentParser.parse(documentId, teiXml);
```

能够稳定得到：

```text
AcademicDocument
│
├── PaperMetadata
│   ├── Author
│   ├── Affiliation
│   └── PartialDate
│
├── abstractText
│
├── Section[]
│   ├── ParagraphBlock + CitationMarker
│   ├── FigureBlock
│   ├── TableBlock
│   ├── FormulaBlock
│   ├── ListBlock
│   └── children（仅真实 XML 嵌套）
│
├── Reference[]
│
└── ParserInfo
```

并满足：

```text
✓ namespace-aware
✓ 防 XXE
✓ 不依赖 GROBID HTTP 服务
✓ 不需要 Docker 即可单测
✓ 保留原始 section / block 顺序
✓ 不丢无标题正文
✓ Figure / Table 正确区分
✓ mixed content 正确处理
✓ CitationMarker 可关联 Reference.id
✓ 保留 TEI xml:id
✓ 无 coords 不伪造页码
✓ 不在 Parser 中修复语义错误
✓ 不强猜平铺 Section 的层级
✓ 可用真实 TEI fixture 回归测试
```

---

## 38. 实现前必须检查现有代码

AI 编程助手执行前先阅读：

1. `AcademicDocument`
2. `PaperMetadata`
3. `Author`
4. `Affiliation`
5. `PartialDate`
6. `Section`
7. `DocumentBlock` 与所有 Block
8. `Reference`
9. `CitationMarker`
10. `PageRange`
11. `ParserInfo`
12. 项目 Java 版本
13. Lombok 风格
14. Jackson 配置
15. 当前 parser package
16. 当前统一异常体系

如果现有字段和本 Skill 略有冲突，以现有源码 + 本 Skill 的领域含义为准做最小适配，不要擅自重构整个领域模型。

完成后运行项目实际测试命令（如 `mvn test`），修复本任务引入的编译和测试问题。
