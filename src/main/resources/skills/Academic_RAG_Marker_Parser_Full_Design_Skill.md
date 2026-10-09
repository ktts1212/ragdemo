# Skill：Java Academic RAG — Marker 解析器完整重构设计

## 0. Skill 目标

本 Skill 用于指导 Agent 在现有 Spring Boot + LangChain4j + PostgreSQL/pgvector Academic RAG 项目中：

> **停止继续扩展 GROBID 路线，改为以 Marker API 为唯一 PDF 学术解析后端，并完整设计/实现 Java 侧 Marker 解析链路。**

目标不是做一个“能把 JSON 反序列化”的最小 Demo，而是一次把长期可维护的结构设计清楚：

```text
PDF
↓
Marker API
↓
Raw Marker Document JSON
↓
MarkerDocumentParser
↓
ReadingOrderRepair
↓
PaperCleaner
↓
Canonical AcademicDocument
↓
SectionAwareChunker
↓
Embedding
↓
pgvector
```

其中本 Skill 的核心范围是：

```text
Marker API Client
+ Marker Raw Model
+ MarkerDocumentParser
+ ReadingOrderRepair
+ PaperCleaner
+ CanonicalDocumentValidator
```

本阶段先把“PDF → AcademicDocument”这一整条解析链路设计完整并测试通过。

---

# 1. 项目当前状态

现有项目定位：

```text
Spring Boot
+ LangChain4j
+ PostgreSQL / pgvector
+ MinIO
+ Kafka
+ bge-m3
```

V1 已完整可跑：

```text
用户上传 PDF/DOCX/TXT/MD
↓
DocumentController
↓
DocumentService
↓
MinIO + documents
↓
Kafka
↓
IngestionServiceImpl
↓
PDFBox / POI / Text
↓
固定 800 字符递归分块
↓
Embedding
↓
pgvector
↓
RAG QA
```

现有学术文献路线已经建立了一套 GROBID 相关代码：

```text
GrobidClient
GrobidDocumentParser
AcademicDocument
```

但当前决定：

> **GROBID 不再作为主解析器使用。**

原因：

- layout 恢复质量不满足后续要求
- figure/table 周边文本混入正文
- reading order 不稳定
- figure caption 容易重复
- 多栏、跨页文本恢复不理想
- Parser 后面需要大量补救

新的解析后端改为：

```text
Marker
```

Marker API 已独立部署成功。

---

# 2. 当前 Marker API

服务接口：

```text
GET /health

POST /api/v1/parse
Content-Type: multipart/form-data
field: file
```

成功响应：

```json
{
  "success": true,
  "filename": "paper.pdf",
  "elapsedSeconds": 61.2,
  "data": {
    "block_type": "Document",
    "children": []
  }
}
```

失败响应：

```json
{
  "success": false,
  "errorCode": "MARKER_PARSE_FAILED",
  "message": "..."
}
```

当前 Windows Java 项目通过 SSH Tunnel 访问：

```text
Windows
127.0.0.1:8001
↓
SSH Tunnel
↓
Linux
127.0.0.1:8001
↓
marker-api
```

因此 Java 配置默认：

```yaml
rag:
  marker:
    base-url: http://127.0.0.1:8001
```

不要在 Java 中写服务器公网地址。

---

# 3. Marker JSON 已知特征

Marker 主 JSON 不是简单纯文本，而是结构化 Document Tree。

大体结构：

```text
Document
└── Page
    ├── PageHeader
    ├── SectionHeader
    ├── Text
    ├── Figure
    ├── Caption
    ├── Table
    ├── List
    ├── ListItem
    ├── Equation
    ├── Footnote
    └── PageFooter
```

常见字段：

```text
id
block_type
html
polygon
bbox
children
section_hierarchy
images
```

注意：

Marker 原始输出质量已经足以作为 Parser backend，

但：

> Marker JSON 不能直接等价为最终 AcademicDocument。

已知需要 Java 层处理的问题包括：

### 3.1 Section hierarchy 不完全可靠

例如可能出现：

```text
"Data scaling
Pretraining benefits"
```

被合并成一个 SectionHeader。

或者：

```text
"Downstream tasks
UHN-ECG interpretation"
```

层级不稳定。

### 3.2 跨页 / 多栏文本存在 continuation

例如：

```text
Page N:
... patients admitted to

Page N+1:
hospital between ...
```

不能简单按 block 切断。

### 3.3 Figure Caption 有时分块

可能出现：

```text
Caption
Paragraph
Paragraph
```

实际上属于同一个 Figure caption。

### 3.4 References 可能被 List/ListItem 吞入

例如：

```text
References
├── ListItem 1
├── ...
└── ListItem 13
     └── 14~30 全部嵌入同一个 block
```

所以 References 不能只依赖 Marker block type。

### 3.5 Back matter 不一定被正确区分

可能包含：

```text
Author contributions
Supplementary material
Funding
Conflicts of interest
Additional information
Correspondence
Peer review
Publisher note
License
```

这些不能全部当普通正文。

### 3.6 PageHeader / PageFooter 仍然存在

必须 Cleaner 处理。

### 3.7 图片可能以 base64 存在

Raw Marker JSON 中：

```text
images
```

可能包含很长的 base64。

禁止在长期 Java Domain Model 中保存 base64。

后续必须：

```text
base64
↓
decode
↓
FigureImageStorage
↓
MinIO / local test storage
↓
imageKey
```

---

# 4. 总体架构

最终 Java 端必须按“分层职责”设计：

```text
                    Marker API
                       ↓
               MarkerApiClient
                       ↓
               Marker API Response
                       ↓
              Marker Raw Document
                       ↓
              MarkerDocumentParser
                       ↓
             Parsed AcademicDocument
                       ↓
              ReadingOrderRepair
                       ↓
                 PaperCleaner
                       ↓
          CanonicalDocumentValidator
                       ↓
          Canonical AcademicDocument
```

必须明确：

```text
HTTP Client != Parser
Parser != Repair
Repair != Cleaner
Cleaner != Chunker
```

禁止把所有逻辑堆在一个 1000+ 行 Parser 里。

---

# 5. 包结构设计

建议新建：

```text
src/main/java/com/example/ragdemo/
├── infrastructure/
│   └── marker/
│       ├── MarkerClient.java
│       ├── MarkerHttpClient.java
│       ├── MarkerProperties.java
│       ├── MarkerResponse.java
│       ├── MarkerHealth.java
│       └── exception/
│           ├── MarkerException.java
│           ├── MarkerUnavailableException.java
│           ├── MarkerTimeoutException.java
│           ├── MarkerProcessingException.java
│           └── MarkerInvalidResponseException.java
│
├── document/
│   ├── parser/
│   │   ├── AcademicDocumentParser.java
│   │   └── marker/
│   │       ├── MarkerDocumentParser.java
│   │       ├── MarkerParseContext.java
│   │       ├── MarkerBlockMapper.java
│   │       ├── MarkerSectionBuilder.java
│   │       ├── MarkerPageTracker.java
│   │       └── exception/
│   │           └── MarkerDocumentParseException.java
│   │
│   ├── marker/
│   │   └── raw/
│   │       ├── MarkerDocument.java
│   │       ├── MarkerBlock.java
│   │       ├── MarkerBoundingBox.java
│   │       └── MarkerBlockType.java
│   │
│   ├── repair/
│   │   ├── ReadingOrderRepair.java
│   │   ├── ReadingOrderRepairConfig.java
│   │   ├── ReadingOrderRepairAudit.java
│   │   ├── FigureAssociatedTextDetector.java
│   │   └── FigureAssociationResult.java
│   │
│   ├── cleaner/
│   │   ├── PaperCleaner.java
│   │   ├── FrontMatterCleaner.java
│   │   ├── AbstractRecovery.java
│   │   ├── CaptionRecovery.java
│   │   ├── ReferenceExtractor.java
│   │   ├── BackMatterClassifier.java
│   │   ├── HeaderFooterCleaner.java
│   │   ├── SectionHierarchyNormalizer.java
│   │   └── CanonicalDocumentValidator.java
│   │
│   └── image/
│       ├── FigureImageStorage.java
│       ├── LocalFigureImageStorage.java
│       └── MinioFigureImageStorage.java
```

包名应按现有项目实际根包调整。

---

# 6. 第一层：AcademicDocumentParser 抽象

不要让业务层依赖：

```text
GrobidDocumentParser
MarkerDocumentParser
```

定义统一入口：

```java
public interface AcademicDocumentParser {
    AcademicDocument parse(String documentId, JsonNode rawDocument);
}
```

如果未来需要不同格式，也可以定义：

```java
public interface AcademicDocumentParser<T> {
    AcademicDocument parse(String documentId, T source);
}
```

当前 Marker implementation：

```text
MarkerDocumentParser
implements AcademicDocumentParser
```

原 GROBID parser 可以暂时保留源码，但不再被 Ingestion 主链路调用。

---

# 7. 第二层：Marker HTTP Client

## 7.1 MarkerClient

建议：

```java
public interface MarkerClient {
    MarkerResponse parse(Path pdfPath);
    MarkerHealth health();
}
```

## 7.2 MarkerResponse

不要一开始直接把 `data` 反序列化为 AcademicDocument。

```java
public record MarkerResponse(
        boolean success,
        String filename,
        Double elapsedSeconds,
        JsonNode data,
        String errorCode,
        String message
) {}
```

原因：

```text
Marker API Schema
和
Domain Schema

必须解耦。
```

## 7.3 HTTP 技术选择

Spring Boot 项目优先：

```text
RestClient
```

或者项目已有 WebClient，则使用 WebClient。

要求支持：

```text
multipart/form-data
file upload
长 timeout
HTTP status validation
JSON response
```

## 7.4 Timeout

Marker 解析可能：

```text
几十秒 ~ 数分钟
```

建议配置：

```yaml
rag:
  marker:
    base-url: http://127.0.0.1:8001
    connect-timeout: 5s
    read-timeout: 900s
```

---

# 8. 第三层：Raw Marker Model

建议不要直接为 Marker 每个 block_type 建几十个 Java class。

第一版 Raw Model 使用“通用树节点”：

```java
public class MarkerBlock {
    private String id;
    private String blockType;
    private String html;
    private List<List<Double>> polygon;
    private List<Double> bbox;
    private List<MarkerBlock> children;
    private Map<String, Object> sectionHierarchy;
    private Map<String, String> images;
}
```

顶层：

```java
public class MarkerDocument {
    private String id;
    private String blockType;
    private List<MarkerBlock> children;
}
```

原因：

```text
Raw 层应宽容、忠实、少做假设。
```

不要因为出现未知 block_type 就整个解析失败。

---

# 9. MarkerBlockType

可以定义 enum：

```text
DOCUMENT
PAGE
PAGE_HEADER
PAGE_FOOTER
SECTION_HEADER
TEXT
FIGURE
CAPTION
TABLE
LIST
LIST_ITEM
EQUATION
FOOTNOTE
UNKNOWN
```

映射时：

```text
未知类型 → UNKNOWN
```

不要直接抛异常。

---

# 10. MarkerDocumentParser 的职责

MarkerDocumentParser 只负责：

> **结构忠实映射。**

允许做：

```text
Marker block → AcademicDocument / Section / DocumentBlock
Page number tracking
block id preservation
HTML → normalized text
Figure/Table/Formula block creation
SectionHeader → Section skeleton
base64 image externalization
```

禁止在 Parser 中做：

```text
References 正则修复
跨页句子合并
Front matter 推断
作者贡献删除
Caption 猜测
Back matter 分类
复杂 Section hierarchy 修复
```

这些属于 Repair / Cleaner。

---

# 11. Block ID 必须保留

Academic Document Block 建议增加：

```text
sourceBlockId
```

例如：

```text
/page/3/Text/12
```

原因：

```text
debug
trace
parser QA
chunk → raw block trace
```

Chunk 最后也应保存 `blockIds`。

---

# 12. Page 信息必须保留

每个 block 至少能追踪：

```text
pageStart
pageEnd
```

普通 block：

```text
pageStart == pageEnd
```

Repair 合并跨页之后：

```text
pageStart != pageEnd
```

---

# 13. AcademicDocument Domain 需要扩展

## 13.1 ParagraphBlock

至少：

```text
text
sourceBlockId
pageStart
pageEnd
```

## 13.2 FigureBlock

建议：

```text
figureId
caption
descriptionText
imageKey
sourceBlockIds
pageStart
pageEnd
```

不要保存 base64。

## 13.3 TableBlock

建议：

```text
html
plainText
caption
sourceBlockIds
pageStart
pageEnd
```

Table 不要 flatten 成 Paragraph。

## 13.4 FormulaBlock

建议：

```text
latex / text
sourceBlockId
pageStart
pageEnd
```

## 13.5 Footnote / Noise

如果现有 sealed hierarchy 不支持，建议增加：

```text
FootnoteBlock
NoiseBlock
```

---

# 14. HTML → Text Renderer

Marker 的很多 block 都是 HTML。

不能简单：

```java
html.replaceAll("<.*?>", "")
```

建议使用：

```text
Jsoup
```

定义：

```text
MarkerTextExtractor
```

职责：

```text
HTML → normalized plain text
保留必要换行
HTML entity decode
删除空节点
```

Table HTML 不要强行转成普通 Paragraph。

---

# 15. Figure Image Storage

定义：

```java
public interface FigureImageStorage {
    String store(
        String documentId,
        String blockId,
        byte[] imageBytes,
        String contentType
    );
}
```

测试：

```text
LocalFigureImageStorage
```

生产：

```text
MinioFigureImageStorage
```

Parser 遇到 base64：

```text
base64
↓
decode
↓
storage.store(...)
↓
imageKey
↓
FigureBlock
```

禁止：

```text
base64 → AcademicDocument → JSON → DB
```

---

# 16. Section 构建策略

Marker SectionHeader 不可信到可以直接当最终 Section tree。

Parser 阶段只构建：

```text
Section skeleton
```

优先依据：

```text
SectionHeader 顺序
section_hierarchy
```

但保存原始信息：

```text
rawTitle
rawHierarchy
sourceBlockId
```

真正 hierarchy normalization 放 Cleaner。

---

# 17. ReadingOrderRepair

Parser 后第一层语义修复。

职责：

> 修复明显的版面阅读顺序断裂，但不做论文语义清洗。

重点解决：

```text
Paragraph A
↓
Figure / Table / Header / Footer / Footnote
↓
Paragraph B
```

实际上 A+B 是一句话。

扫描窗口建议：

```yaml
rag:
  repair:
    max-skip-blocks: 8
    transparent-noise-enabled: true
    figure-association-enabled: true
```

---

# 18. Continuation 判断

A 满足：

```text
长度 >= 20
且结尾不是：
.
!
?
:
;
```

B 满足：

```text
首字符小写
非 SectionHeader
非明显新段
```

才允许：

```text
A + B
```

不要只因为两个 Paragraph 相邻就合并。

---

# 19. Figure-associated text

Figure 附近可能存在：

```text
From left to right...
Top row...
Bottom panel...
Panel A...
```

这些不应错误插入 A+B 中。

定义：

```text
FigureAssociatedTextDetector
```

输出：

```text
FigureAssociationResult
```

要求使用明确 phrase，不要只匹配 `From` / `Figure` 单个关键词。

---

# 20. ReadingOrderRepairAudit

每次 merge 必须可审计：

```java
public record ReadingOrderRepairAudit(
    String leftBlockId,
    String rightBlockId,
    List<String> skippedBlockIds,
    String reason
) {}
```

---

# 21. PaperCleaner 总职责

Repair 之后才进入 Cleaner。

PaperCleaner 负责：

> 把 Marker/Repair 输出变为真正稳定的 Canonical AcademicDocument。

Pipeline：

```text
1. HeaderFooterCleaner
2. FrontMatterCleaner
3. AbstractRecovery
4. CaptionRecovery
5. ReferenceExtractor
6. BackMatterClassifier
7. SectionHierarchyNormalizer
8. EmptySectionCleaner
9. CanonicalDocumentValidator
```

---

# 22. FrontMatterCleaner

需要识别：

```text
Title
Authors
Affiliations
Emails
Correspondence
Received
Accepted
Published
Check for updates
DOI
Journal header
```

能结构化的写入 `PaperMetadata`，不能可靠结构化的保守标记，不要编造。

---

# 23. AbstractRecovery

Marker 有时：

```text
Abstract SectionHeader
+ Paragraph
```

有时 Abstract 内容直接在正文前。

Cleaner 应恢复：

```text
AcademicDocument.abstractText
```

并避免 Abstract 在 metadata 与正文重复。

---

# 24. CaptionRecovery

Figure/Table Caption 可能：

```text
Caption block
Paragraph continuation
```

Cleaner 需要关联到最近合法 Figure/Table。

要求：

```text
不跨 Section 随意关联
不跨过另一个 Figure/Table
保持 sourceBlockIds
```

---

# 25. ReferenceExtractor

不能只看：

```text
block_type == Reference
```

需要结合：

```text
Section title == References / Bibliography
List / ListItem
编号模式
DOI
作者-年份模式
连续参考文献结构
```

目标：

```text
正文 Sections 中不再包含 References 全文
AcademicDocument.references 被填充
```

---

# 26. BackMatterClassifier

至少识别：

```text
Acknowledgements
Author contributions
Funding
Competing interests
Conflict of interest
Data availability
Code availability
Ethics
Supplementary material
Additional information
Correspondence
Peer review
Publisher note
License
```

分类策略：

```text
保留有检索价值：
Data availability
Code availability
部分 Supplementary 信息

从主 scientific body 中移除：
Publisher note
License
Reprints
纯版权文本
```

---

# 27. SectionHierarchyNormalizer

职责：

```text
纠正明显合并标题
清理空标题
清理 unnamed section
避免 title 被当 section
确保父子层级稳定
```

不要自己“理解论文”后发明 section。

---

# 28. CanonicalDocumentValidator

最终 AcademicDocument 必须经过 validator。

检查：

```text
documentId != null
title 非空（允许 warning）
sections 非空
section title 不应全部空
block page 合法
blockIds 不重复
Figure imageKey 不应包含 base64
References 不应全部滞留正文
Header/Footer 不应大量残留
```

输出：

```text
errors
warnings
quality metrics
```

---

# 29. Quality Metrics

建议生成：

```text
sectionCount
paragraphCount
figureCount
tableCount
referenceCount
headerFooterRemaining
unnamedSectionCount
emptySectionCount
base64RemainingCount
crossPageMergeCount
repairCount
```

不要只返回 `parse success`。

---

# 30. Parser 失败原则

硬失败：

```text
顶层不是 Document
children 为空
JSON 无法解析
关键结构损坏
```

不应硬失败：

```text
未知 block type
没有 Abstract
没有 References
没有 Figure
section hierarchy 不完整
```

这些应 warning。

---

# 31. 测试设计

测试分四层。

## Layer 1 — Marker Client Test

覆盖：

```text
health
multipart upload
200 response
400 invalid file
500 Marker parse error
timeout
invalid JSON
```

## Layer 2 — Marker Parser Fixture Test

保存真实 Marker JSON fixture：

```text
src/test/resources/marker/
├── simple-paper.json
├── figures-paper.json
├── tables-paper.json
├── references-paper.json
└── multi-column-paper.json
```

测试：

```text
Document → AcademicDocument
page tracking
section build
figure
table
formula
list
unknown block
```

## Layer 3 — Repair / Cleaner Unit Test

单独测试：

```text
跨页 sentence continuation
Figure interruption
PageHeader interruption
Caption continuation
References extraction
Abstract recovery
Back matter
empty section
section hierarchy
```

## Layer 4 — Real Paper Integration Test

至少使用：

```text
5~10 篇不同期刊论文
```

包含：

```text
双栏
大量 Figure
大量 Table
长 References
医学论文
计算机论文
综述
有 Supplementary / Back matter
```

---

# 32. 第一篇真实验收论文

继续使用已经用于 Marker API 测试的 29 页 PDF。

记录：

```text
parse time
page count
section count
paragraph count
figure count
table count
reference count
warning count
```

将 Marker raw JSON 和 Canonical AcademicDocument 做人工对照。

---

# 33. 不要立即改 IngestionServiceImpl

非常重要：

> Parser/Repair/Cleaner 没有通过真实论文验收之前，不要先接 Kafka 正式入库。

开发顺序：

```text
Marker Client
↓
Raw Model
↓
MarkerDocumentParser
↓
Repair
↓
Cleaner
↓
Validator
↓
真实论文 QA
↓
再接 IngestionServiceImpl
```

---

# 34. Parser 成熟后再接入 Ingestion

未来主链路：

```text
IngestionServiceImpl
↓
if PDF
↓
MarkerClient.parse(pdf)
↓
MarkerDocumentParser
↓
ReadingOrderRepair
↓
PaperCleaner
↓
CanonicalDocumentValidator
↓
SectionAwareChunker
↓
Embedding
↓
pgvector
```

非 PDF 暂时继续原 V1 parser。

---

# 35. GROBID 代码处理

本阶段不要立即删除：

```text
GrobidClient
GrobidDocumentParser
TEI fixture
```

建议：

```text
保留源码
标 deprecated / legacy
不再被主链路引用
```

等 Marker pipeline 稳定后再决定删除或保留为 benchmark parser。

---

# 36. 配置建议

```yaml
rag:
  parser:
    backend: marker

  marker:
    base-url: http://127.0.0.1:8001
    connect-timeout: 5s
    read-timeout: 900s

  repair:
    max-skip-blocks: 8
    transparent-noise-enabled: true
    figure-association-enabled: true
```

不要把 `127.0.0.1:8001` 硬编码在 Java 类里。

---

# 37. 日志要求

每篇论文至少输出：

```text
documentId
filename
Marker elapsed
Marker page count
Parser elapsed
Repair count
Cleaner warnings
section count
figure count
table count
reference count
validation result
```

不要输出完整 PDF 内容、完整 base64、完整 Marker JSON。

---

# 38. 第一阶段 Agent 实施边界

Agent 可以：

```text
创建代码
修改 domain model
新增测试
新增 fixture
新增配置
实现 Marker Client
实现 Parser
实现 Repair
实现 Cleaner
实现 Validator
```

Agent 暂时禁止：

```text
修改正式 IngestionServiceImpl
修改 Kafka flow
修改 embedding
修改 pgvector schema
修改 retrieval
修改 chat
删除 GROBID
大规模重构现有 V1
```

---

# 39. 实施阶段建议

不要一次全部写完。

## Phase A — Marker Client

完成：

```text
MarkerClient
MarkerHttpClient
MarkerProperties
MarkerResponse
exception
```

验收：

```text
真实 Java → SSH Tunnel → Marker API
```

输出：

```text
MARKER_CLIENT_READY = YES
```

## Phase B — Raw Model + Structural Parser

完成：

```text
Marker Raw Model
MarkerDocumentParser
MarkerTextExtractor
Page tracking
Section skeleton
Figure/Table/Formula/List
image storage abstraction
```

输出：

```text
MARKER_STRUCTURAL_PARSER_READY = YES
```

## Phase C — ReadingOrderRepair

完成：

```text
cross page repair
transparent interruption
figure-associated text
audit
```

输出：

```text
READING_ORDER_REPAIR_READY = YES
```

## Phase D — PaperCleaner

完成：

```text
Front matter
Abstract
Caption
References
Back matter
Section hierarchy
Header/Footer
```

输出：

```text
PAPER_CLEANER_READY = YES
```

## Phase E — Canonical Validation

完成：

```text
Validator
quality metrics
5~10 real paper QA
```

输出：

```text
READY_FOR_INGESTION_INTEGRATION = YES
```

---

# 40. 每个 Phase 的要求

每完成一个 Phase，Agent 必须：

```text
1. 列出新增/修改文件
2. 说明核心设计
3. 列出测试
4. 执行测试
5. 报告测试数量
6. 报告已知问题
7. 给出 READY 状态
```

禁止代码没测就说完成。

---

# 41. 设计原则总结

### Principle 1

```text
Marker 负责识别版面
Java 负责规范化学术语义
```

### Principle 2

```text
Parser 忠实
Repair 保守
Cleaner 语义化
Chunker 不救 Parser
```

### Principle 3

```text
Raw JSON != Canonical AcademicDocument
```

### Principle 4

```text
所有修复都尽量可追踪
```

保留：

```text
sourceBlockId
page
repair audit
```

### Principle 5

```text
先可解释、可测试、可迭代，再追求覆盖所有论文。
```

---

# 42. 最终验收条件

只有以下全部成立：

```text
Java MarkerClient 调真实 Marker API 成功
真实 29 页论文成功转换
结构化 Section 可用
Figure/Table 不被 flatten
Page trace 可用
base64 不进入 canonical domain
ReadingOrderRepair 有 audit
References 能隔离
Header/Footer 大量去除
Abstract 可恢复
Back matter 可分类
Canonical Validator 通过
至少 5 篇真实论文人工 QA
```

才允许：

```text
READY_FOR_INGESTION_INTEGRATION = YES
```

在此之前：

```text
禁止把新 Marker pipeline 接进正式 Kafka ingestion。
```

---

# 43. 最终目标

```text
PDF
↓
Marker API
↓
Marker Raw JSON
↓
Java MarkerDocumentParser
↓
ReadingOrderRepair
↓
PaperCleaner
↓
Canonical AcademicDocument
↓
SectionAwareChunker
↓
Embedding
↓
pgvector
↓
Academic RAG
```

这条链路将取代原：

```text
PDFBox → 固定 800 字符
```

成为 Academic RAG 的正式 PDF ingest pipeline。

同时：

```text
GROBID
```

降级为 legacy / benchmark parser，不再作为主路线。
