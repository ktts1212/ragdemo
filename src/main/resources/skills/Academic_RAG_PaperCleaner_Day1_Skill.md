# Academic RAG — PaperCleaner 设计与实现 Skill

## 1. Skill 名称

**Academic RAG PaperCleaner Design & Implementation Skill**

---

## 2. 角色

你是一名资深 Java / RAG / 学术文档解析工程师。

你的任务不是“针对某几篇论文打补丁”，而是基于现有 Academic RAG 项目，设计并实现一个**可泛化、可测试、可演进的 PaperCleaner / Normalizer 层**，把 GROBID 解析得到的 `Raw AcademicDocument` 转换为更稳定、适合后续 Section-aware Chunking 和 RAG 检索的 `Canonical AcademicDocument`。

你需要重点关注：

- 学术 PDF 版式差异
- GROBID 结构抽取误差
- section / paragraph / figure / table / formula / citation 的结构稳定性
- 清洗规则的泛化能力
- 不过度修复、不猜测未知信息
- 不破坏已经正确解析出的信息
- 可回归测试
- 可维护性与后续扩展能力

---

# 3. 当前项目背景

这是一个 Java 学术文献 RAG 项目。

已有 V1 链路：

```text
PDF 上传
→ MinIO
→ Kafka
→ PDFBox / POI / Text Parser
→ 固定长度分块（约 800 字符）
→ bge-m3 Embedding
→ pgvector
→ Retrieval
→ LLM Answer + Sources
```

当前正在升级为 Academic RAG V2：

```text
PDF
→ GROBID
→ TEI XML
→ GrobidDocumentParser
→ AcademicDocument
→ PaperCleaner       ← 当前 Day 1 核心任务
→ SectionAwareChunker
→ Embedding
→ pgvector
→ Retrieval
→ Answer + Citation
```

---

# 4. 当前已经完成的模块

## 4.1 GROBID HTTP 层

已有：

```text
infrastructure/grobid/
├── GrobidClient.java
├── GrobidHttpClient.java
├── GrobidProperties.java
├── GrobidResponse.java
├── GrobidHealth.java
└── exception/
```

职责：

```text
PDF bytes
→ GROBID HTTP API
→ TEI XML String
```

真实 GROBID Docker 0.9.0 已测试成功。

---

## 4.2 AcademicDocument 领域模型

已有结构类似：

```text
AcademicDocument
├── documentId
├── metadata
│   ├── title
│   ├── authors
│   ├── affiliations
│   ├── doi
│   ├── journal
│   └── publicationDate
├── abstractText / abstract structure
├── sections
│   ├── title
│   ├── level
│   ├── path
│   ├── blocks
│   └── children
├── references
└── parserInfo
```

Block 类型包括：

```text
ParagraphBlock
FigureBlock
TableBlock
FormulaBlock
ListBlock
```

并已有：

```text
CitationMarker
Reference
PageRange
ParserInfo
...
```

---

## 4.3 GrobidDocumentParser

已有：

```java
AcademicDocument document =
    grobidDocumentParser.parse(documentId, teiXml);
```

职责：

```text
TEI XML
→ AcademicDocument
```

已有单元测试，当前 GROBID + Domain + Parser 总测试数约 39 个，已通过。

---

# 5. 为什么需要 PaperCleaner

GROBID 不是最终标准数据源。

它更适合作为：

> 学术 PDF 的结构抽取器

而不是：

> 100% 正确的 Canonical Document 生成器

因此：

```text
Raw AcademicDocument
        ↓
PaperCleaner
        ↓
Canonical AcademicDocument
```

PaperCleaner 的目标不是“完美还原 PDF”。

真正目标是：

> 对高置信度错误进行修复，对明显噪声进行删除，对结构进行标准化，对无法可靠修复的问题进行标记或降级处理，从而为 Section-aware Chunking 提供稳定输入。

---

# 6. 当前真实测试暴露的问题

目前已经用 3 篇不同版式的真实 ECG / Medical AI 论文进行了测试。

这些样本只能作为“发现共性问题的回归样本”，禁止针对具体论文标题、出版社名称或某个固定字符串写专用补丁。

---

## 6.1 样本 A：IEEE Access

特点：

```text
双栏
编号式章节
I / II / III
A / B / C 子章节
公式较多
Figure
Table
```

观察到：

### Metadata

整体较好：

```text
title ✅
authors ✅
affiliations ✅
DOI ✅
abstract ✅
keywords ✅
```

### Section

GROBID 能识别：

```text
II. RELATED WORK
A. CONTRASTIVE LEARNING BASED APPROACH
B. METHODS BASED ON SIGNAL RECONSTRUCTION
```

但可能解析成平级 div：

```text
RELATED WORK
CONTRASTIVE LEARNING BASED APPROACH
METHODS BASED ON SIGNAL RECONSTRUCTION
```

而真实逻辑应为：

```text
RELATED WORK
├── CONTRASTIVE LEARNING BASED APPROACH
└── METHODS BASED ON SIGNAL RECONSTRUCTION
```

### Formula

公式能识别，但可能：

```text
一个公式被拆成两个 FormulaBlock
数学排版信息损失
编号括号单独成为 formula
```

### Table

可能未形成真正的 `<table>`，而是：

```text
TABLE 1.
Implementation detail...
```

变成普通 section / paragraph。

---

## 6.2 样本 B：Nature Communications

特点：

```text
无编号主章节
复杂作者单位
跨栏 Figure
复杂 Results 结构
部分 Table
```

问题：

### Abstract 边界错误

真实 abstract 已结束，但 GROBID 把 Introduction 前几段继续吞进 abstract。

甚至出现：

```text
Abstract:
"T. Tohyama et al. infer 5-year"

Body:
"incidence of atrial fibrillation ..."
```

即一句话被错误切断。

### Figure caption 混入正文

正文中可能出现：

```text
... sinus
Fig. 1 | Overview of the study...
[整个 caption]
bradycardia ...
```

导致正文语义被 caption 污染。

### Table

部分 Table 能成功结构化，说明：

```text
GROBID table extraction 不稳定
```

不能假设所有表都失败，也不能假设所有表都成功。

### Section

无编号章节，例如：

```text
Results
Overview of this study
Fine-tuning ECG-LFM...
Methods
...
```

因此不能只靠：

```text
I / II / A / B
```

恢复层级。

---

## 6.3 样本 C：NEJM AI

特点：

```text
structured abstract
医学期刊双栏
drop cap
大表格
复杂作者信息
```

问题：

### Author 重复 / 截断

可能出现：

```text
Jonathan B Moody
Jonathan B Moo
```

重复作者，同时一个 surname 被截断。

禁止简单写：

```text
Moo -> Moody
```

必须基于多个证据决定是否合并。

### Structured Abstract

这一篇反而解析很好：

```text
BACKGROUND
METHODS
RESULTS
CONCLUSIONS
```

说明 Cleaner 不能假设 abstract 总是错误。

### Drop Cap

真实：

```text
Nearly 1 in 17...
```

被解析成：

```text
<head>N</head>
<p>early 1 in 17...</p>
```

这是典型高置信度可修复错误。

### Figure caption 混入正文

与 Nature 样本类似，再次证明这不是偶发现象。

### Table

Table 1 / Table 2 被压成普通长段落文本，而不是结构化 Table。

---

# 7. 核心设计原则

必须遵守以下原则。

---

## 原则 1：不要追求 100% PDF 还原

PaperCleaner 的目标是 RAG-friendly canonicalization。

不是 PDF renderer。

---

## 原则 2：只自动修复高置信度错误

例如：

```text
单字母 heading + 下一 paragraph 小写开头
```

可以高置信度判断为 drop cap。

但：

```text
作者 surname 被截断
```

不能凭猜测补全。

---

## 原则 3：无法可靠修复时，标记优于猜测

原则：

```text
repair when confident
mark when uncertain
preserve when unsure
```

---

## 原则 4：禁止针对单篇论文硬编码

禁止：

```java
if (title.contains("ECG-LFM")) ...
```

禁止：

```java
if (journal.equals("Nature Communications")) ...
```

除非是明确、通用、可配置的出版物 boilerplate pattern，并且有合理泛化依据。

---

## 原则 5：优先保护已有结构

尤其不得破坏：

```text
CitationMarker
Reference target
Figure reference
Table reference
Formula
Section order
Paragraph order
documentId
DOI
```

---

## 原则 6：Cleaner 不负责 Chunking

Cleaner：

```text
Raw AcademicDocument
→ Canonical AcademicDocument
```

Chunker：

```text
Canonical AcademicDocument
→ AcademicChunk[]
```

禁止在 Cleaner 中引入：

```text
chunk size
chunk overlap
embedding
vector db
```

---

# 8. 建议架构

推荐整体结构：

```text
document/
└── cleaner/
    ├── PaperCleaner.java
    ├── DefaultPaperCleaner.java
    ├── CleanerContext.java
    ├── CleanerResult.java
    ├── CleanerConfig.java
    │
    ├── rule/
    │   ├── CleaningRule.java
    │   ├── TextNormalizationRule.java
    │   ├── BoilerplateRule.java
    │   ├── DropCapRepairRule.java
    │   ├── AuthorDeduplicationRule.java
    │   ├── TableLikeDetectionRule.java
    │   └── FigureCaptionDetectionRule.java
    │
    └── quality/
        ├── ParseQuality.java
        └── ParseWarning.java
```

不要求严格照此包名实现。

必须先结合现有项目代码风格再决定。

---

# 9. PaperCleaner 接口建议

推荐：

```java
public interface PaperCleaner {

    CleanerResult clean(AcademicDocument document);

}
```

不要直接只返回 `AcademicDocument`。

更推荐：

```java
public record CleanerResult(
    AcademicDocument document,
    ParseQuality quality,
    List<ParseWarning> warnings
) {}
```

理由：

Cleaner 除了生成清洗后的文档，还应该能够：

```text
记录异常
记录规则命中
记录无法修复的问题
输出质量信息
```

---

# 10. CleaningRule 设计

建议规则化，而不是把所有逻辑塞进一个 1000 行 `DefaultPaperCleaner`。

例如：

```java
public interface CleaningRule {

    String name();

    void apply(
        MutableAcademicDocument document,
        CleanerContext context
    );

}
```

如果现有领域模型使用 immutable object，则不要为了方便强行改成 mutable。

可以采用：

```text
Builder
copy constructor
mapper
```

完成新对象构造。

---

# 11. Day 1 第一版必须实现的规则

Day 1 只实现高收益、高置信度规则。

不要试图今天解决所有 PDF 解析问题。

优先级：

```text
P0 TextNormalizer
P0 BoilerplateCleaner
P0 DropCapRepair
P0 AuthorDeduplicator
P0 Table/Figure-like Marker
P1 ParseQuality
P2 Abstract boundary
P2 Complex Section Hierarchy
P2 Formula repair
```

---

# 12. TextNormalizer

## 12.1 目标

统一文本格式，不改变语义。

处理：

```text
重复空格
异常换行
首尾空白
多空白字符
明显字符拼接问题（仅高置信度）
```

例如：

```text
"time-  frequency"
→
"time-frequency"
```

但不要进行大规模 NLP 改写。

---

## 12.2 禁止

禁止：

```text
语法纠错
论文语言润色
句子重写
术语替换
LLM paraphrase
```

Cleaner 不是 rewrite layer。

---

# 13. BoilerplateCleaner

识别明显不属于论文知识正文的内容。

例如：

```text
NEJM AI 3
Nature Communications | (2026) 17:5771
VOLUME 13, 2025
Article https://doi.org/...
© The Author(s)
publisher copyright
download notice
license boilerplate
```

设计为：

```text
pattern-based
configurable
conservative
```

推荐使用：

```text
regex + short-line heuristics
```

但不能误删正文中的：

```text
DOI
journal name
copyright
```

如果它们出现在真正讨论内容里。

因此建议结合：

```text
block length
位置
重复出现频率
文本 pattern
```

判断。

---

# 14. DropCapRepair

这是第一版最典型的结构修复规则。

输入：

```text
Section(head="Introduction")
Section(head="N", paragraph="early 1 in 17 ...")
```

或：

```text
heading = "N"
paragraph starts with lowercase
```

满足：

```text
heading length == 1
heading is alphabetic uppercase
paragraph non-empty
first paragraph character is lowercase
```

高置信度合并：

```text
N + early
→
Nearly
```

结果：

```text
heading N 删除
paragraph:
Nearly 1 in 17...
```

必须写单元测试。

---

# 15. AuthorDeduplicator

不能简单按字符串 equals。

推荐计算 author identity evidence：

```text
full name similarity
given name
middle initial
surname similarity
email
ORCID
affiliation overlap
position proximity
```

高置信度示例：

```text
Author A:
Jonathan B Moody
email = jmoody@...
affiliation = INVIA

Author B:
Jonathan B Moo
ORCID = ...
affiliation = INVIA
```

若同时满足多个条件：

```text
same given name
same middle initial
surname prefix similarity
same affiliation
位置相邻
```

可以标记 duplicate candidate。

是否自动 merge：

第一版建议保守：

```text
ORCID same
OR email same
OR high similarity + same affiliation + adjacent
```

才允许自动 merge。

否则只产生 warning：

```text
SUSPECTED_DUPLICATE_AUTHOR
```

---

# 16. Table-like Detection

由于 GROBID 对表格解析不稳定，必须同时支持：

```text
真正 TableBlock
```

以及：

```text
被压成 ParagraphBlock 的 table-like text
```

检测规则可以包括：

```text
startsWith "Table "
startsWith "TABLE "
startsWith "Tab. "
```

并结合：

```text
短 caption + 大量列式 token
数字密度
连续缩写
```

第一版不要尝试恢复行列结构。

正确行为：

```text
ParagraphBlock
↓
标记 TABLE_LIKE
```

或转换为：

```text
TableLikeBlock
```

但只有在现有模型允许且改动成本合理时再新增 Block 类型。

否则通过 metadata / warning 标记。

保留原始文本。

---

# 17. Figure Caption Detection

目标：

识别被错误塞进正文的 figure caption。

常见模式：

```text
Fig. 1 |
Figure 1.
FIGURE 1.
Fig 2 |
```

但注意：

正文中正常引用：

```text
As shown in Fig. 1...
```

不能误判。

高置信度 caption 特征：

```text
以 Fig./Figure + number 开头
caption-like 长句
位于段落中间
后面正文重新恢复原句
```

Day 1 第一版可以只做到：

```text
detect
mark warning
```

不要求完美剥离。

如果能够高置信度识别完整 caption span，再独立抽成 FigureBlock。

---

# 18. AbstractBoundaryCleaner

Day 1 可先设计、不强制实现。

原因：

Nature 样本显示 abstract 可能吃掉正文。

但自动修复 abstract 边界比 drop cap 风险大。

第一版建议只检测异常：

```text
abstract unusually long
abstract contains obvious Introduction-like multi-paragraph content
abstract tail and body head appear sentence-continuous
body starts lowercase
```

例如：

```text
abstract tail:
"T. Tohyama et al. infer 5-year"

body head:
"incidence of atrial fibrillation..."
```

可生成：

```text
ABSTRACT_BODY_BOUNDARY_SUSPECTED
```

不要第一版直接移动文本，除非判断非常确定。

---

# 19. SectionNormalizer

不要假设 section 一定编号。

必须同时支持：

```text
IEEE:
II. RELATED WORK
A. CONTRASTIVE...

Nature:
Results
Overview of this study

NEJM:
Methods
DATA SOURCES
DEEP LEARNING MODEL
```

第一版允许：

```text
保留 flat section sequence
```

不要求完全恢复树。

但可以记录：

```text
detectedLevel
levelConfidence
```

编号规则只能是 heuristic。

例如：

```text
Roman numeral -> probable level 1
single uppercase letter + "." -> probable level 2
numbered decimal 2.1 -> probable hierarchy
```

无编号标题则保守处理。

---

# 20. FormulaNormalizer

Day 1 不作为必做。

当前只需保证：

```text
FormulaBlock 不丢失
顺序不变
公式编号保留
```

后续可以解决：

```text
")" 单独 FormulaBlock
连续公式合并
```

但不要为了合并公式破坏数学内容。

---

# 21. ParseQuality

建议设计轻量质量模型。

示例：

```java
public record ParseQuality(
    double metadataScore,
    double structureScore,
    double citationScore,
    double figureScore,
    double tableScore,
    double overallScore
) {}
```

也可以第一版更简单：

```text
GOOD
WARNING
POOR
```

配合 warning list。

---

# 22. ParseWarning

推荐至少支持：

```text
SUSPECTED_DUPLICATE_AUTHOR
DROP_CAP_REPAIRED
BOILERPLATE_REMOVED
TABLE_LIKE_PARAGRAPH
FIGURE_CAPTION_IN_PARAGRAPH
ABSTRACT_BODY_BOUNDARY_SUSPECTED
SUSPICIOUS_SINGLE_CHAR_HEADING
FORMULA_FRAGMENT
UNKNOWN_STRUCTURE
```

warning 必须包含：

```text
type
message
section / block location
severity
```

---

# 23. 清洗流水线顺序

推荐：

```text
Raw AcademicDocument
        ↓
Metadata normalization
        ↓
Text normalization
        ↓
Boilerplate removal
        ↓
Drop-cap repair
        ↓
Table/Figure-like detection
        ↓
Author deduplication
        ↓
Structural anomaly detection
        ↓
Quality evaluation
        ↓
Canonical AcademicDocument
```

顺序不要随意。

例如：

```text
先 TextNormalizer
再 DropCapRepair
```

可以避免空白问题干扰判断。

---

# 24. 不可破坏约束

Cleaner 执行前后必须满足：

```text
documentId 不变
DOI 不应被清洗掉
citation target 不变
reference id 不变
figure id 不变
table id 不变
formula id 不变
正文相对顺序原则上不变
```

除非规则明确是在修复解析错误。

---

# 25. 幂等性

必须考虑：

```java
clean(clean(document))
```

尽量等价于：

```java
clean(document)
```

也就是：

> PaperCleaner 应尽可能幂等。

例如 Boilerplate 不应该第二次运行产生额外变化。

---

# 26. 测试策略

必须分成三层。

---

## 26.1 Unit Test

每个规则独立测试。

例如：

```text
DropCapRepairTest
AuthorDeduplicatorTest
BoilerplateCleanerTest
TableLikeDetectionTest
TextNormalizerTest
```

测试必须覆盖：

```text
positive case
negative case
edge case
```

---

## 26.2 Synthetic Integration Test

构造小型 AcademicDocument：

```text
metadata
sections
paragraph
citation
figure
table
formula
```

执行完整 cleaner pipeline。

验证：

```text
规则顺序
信息不丢失
citation 不损坏
幂等性
```

---

## 26.3 Real Regression Test

使用当前 3 篇真实 GROBID TEI fixture。

建议：

```text
src/test/resources/grobid/real/
├── ieee/
│   └── paper.tei.xml
├── nature/
│   └── paper.tei.xml
└── nejm/
    └── paper.tei.xml
```

真实测试不应该 assert 全部文本。

应该 assert 关键行为。

例如：

### IEEE

```text
title exists
DOI correct
section count > threshold
citations preserved
formula blocks preserved
```

### Nature

```text
metadata preserved
Results detected
figure refs preserved
abstract warning can be emitted if boundary suspicious
```

### NEJM

```text
drop-cap N + early -> Nearly
structured abstract preserved
duplicate author warning / merge
table-like paragraph detected
```

---

# 27. Day 1 完成标准

今天必须达到：

```text
1. PaperCleaner 接口存在
2. Cleaner pipeline 可执行
3. TextNormalizer 完成
4. BoilerplateCleaner 完成
5. DropCapRepair 完成
6. AuthorDeduplicator 至少实现高置信度检测
7. Table/Figure-like detection 至少能标记
8. CleanerResult / warning 机制可用
9. 单元测试通过
10. 三篇真实 fixture 至少能跑完整 pipeline
```

不是要求：

```text
所有异常 100% 修复
```

---

# 28. Day 1 禁止扩展内容

今天禁止顺手实现：

```text
SectionAwareChunker
Embedding
pgvector
Hybrid Search
Reranker
Agent
Knowledge Graph
```

这些属于后续 Day。

---

# 29. Agent 工作流程要求

收到该 Skill 后，必须严格按以下顺序工作。

---

## Step 1：阅读现有代码

必须先检查：

```text
AcademicDocument
PaperMetadata
Author
Affiliation
Section
DocumentBlock
ParagraphBlock
FigureBlock
TableBlock
FormulaBlock
CitationMarker
Reference
ParserInfo
GrobidDocumentParser
现有测试代码风格
```

不能根据 Skill 猜现有字段。

---

## Step 2：输出 Current State Analysis

必须先说明：

```text
当前 domain 是否 mutable
现有对象如何构造
哪些字段适合 Cleaner 修改
现有 Parser 是否已做部分 normalization
哪些设计可以复用
```

此阶段禁止修改代码。

---

## Step 3：输出 PaperCleaner Design

必须给出：

```text
package
class/interface
职责
规则顺序
warning model
quality model
immutable/mutable strategy
```

同时说明：

```text
为什么这么设计
有哪些备选方案
为什么没选
```

---

## Step 4：列出拟修改文件

例如：

```text
新增：
document/cleaner/PaperCleaner.java
...

修改：
AcademicDocument.java ?（若确实必要）
...
```

原则：

> 尽量避免大改已有 Domain。

---

## Step 5：等待确认

如果调用者明确要求“只设计，不实现”：

```text
停止在设计阶段
禁止修改任何代码
```

如果调用者明确允许实现：

进入 Step 6。

---

## Step 6：分小步实现

推荐顺序：

```text
1. CleanerResult + warning
2. PaperCleaner interface
3. TextNormalizer
4. BoilerplateCleaner
5. DropCapRepair
6. AuthorDeduplicator
7. Table/Figure marker
8. Pipeline
9. Tests
```

每一步都必须保证项目可编译。

---

## Step 7：测试

必须运行：

```text
原有全部测试
+
新增 Cleaner 测试
+
真实 fixture regression tests
```

禁止只跑新测试。

---

# 30. 代码风格要求

必须：

```text
Java 项目现有风格优先
小类
单一职责
清晰命名
必要注释
避免过度抽象
```

禁止：

```text
God Class
1000 行 Cleaner
几十层继承
为了“设计模式”而设计模式
大量 if(title == ...)
```

---

# 31. 性能要求

Cleaner 是 CPU 轻量步骤。

不允许：

```text
为每个 block 调 LLM
调用远程服务
复杂 embedding
重复解析 PDF
```

目标复杂度：

```text
O(number of authors + sections + blocks)
```

或接近线性。

---

# 32. 是否使用 LLM 清洗

第一版禁止把 LLM 作为核心 Cleaner。

原因：

```text
不稳定
成本高
不可重复
难测试
可能改写论文原文
```

未来可以设计：

```text
Low-confidence repair assistant
```

但必须在 rule-based Cleaner 之后。

---

# 33. 日志

建议每条规则输出 DEBUG 级别：

```text
rule=DropCapRepair
documentId=xxx
action=MERGED
before=N + early
after=Nearly
```

正常生产运行不要大量 INFO spam。

warning 应进入 CleanerResult。

---

# 34. 配置化

以下内容建议可配置：

```text
boilerplate patterns
drop cap enabled
author duplicate threshold
table-like enabled
figure-caption detection enabled
quality warning threshold
```

不要为了第一版设计复杂 YAML。

简单 `CleanerConfig` 即可。

---

# 35. 成功标准

最终成功不是：

> 三篇论文清洗后看起来“完美”。

而是：

> 一个新的、不同出版社的 PDF 进入系统时，Cleaner 不会因为没有见过它而崩溃，也不会大量错误修改原文，同时能清除明显噪声、修复高置信度错误、标记低置信度异常。

---

# 36. 最终输出要求

Agent 完成任务后必须报告：

## A. Architecture

```text
Raw AcademicDocument
→ rules
→ CleanerResult
→ Canonical AcademicDocument
```

## B. Implemented Rules

逐项说明。

## C. Real-paper Test Results

分别报告：

```text
IEEE
Nature
NEJM
```

命中了哪些规则。

## D. Preserved Information

确认：

```text
citation
reference
figure
table
formula
metadata
```

是否保持。

## E. Known Limitations

必须诚实写明暂未解决：

```text
complex table reconstruction
full abstract boundary correction
perfect section hierarchy
formula LaTeX recovery
```

## F. Next Step

只允许写：

```text
Section-aware PaperChunker
```

不要擅自继续实现。

---

# 37. 最重要的决策原则

遇到任何不确定情况时，按：

```text
保留原始信息
>
打 warning
>
高置信度修复
>
猜测式修复
```

其中：

```text
猜测式修复 = 禁止
```

PaperCleaner 的价值在于：

> 建立稳定边界，而不是假装解析器永远正确。
