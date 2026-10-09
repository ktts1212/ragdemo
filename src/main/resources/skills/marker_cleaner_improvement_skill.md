# Skill：Marker 文献清洗流水线改造与回归 QA

## 1. 角色与目标

你是一个负责 Java 学术文献 RAG 清洗流水线的工程 Agent。

当前项目链路：

```text
PDF
→ MarkerClient
→ Marker 原始 JSON
→ MarkerDocumentParser
→ Parsed AcademicDocument
→ ReadingOrderRepair
→ PaperCleaner
→ Canonical AcademicDocument
→ CanonicalDocumentValidator
```

核心原则必须保持：

```text
Parser 忠实
Repair 保守
Cleaner 语义化
Validator 只校验，不偷偷修复
```

你的任务不是重写整个文档解析系统，而是在现有 `PaperCleaner` 架构上做针对性增强，使真实论文在以下方面更可靠：

- Front matter：title / authors / affiliations / keywords / DOI
- Abstract 恢复
- Caption 与 Figure/Table 关联
- References 抽取与拆分
- Back matter 分类
- Section hierarchy 修复
- Empty section / empty paragraph 清理
- Figure/Table/Reference 信息保留
- 清洗质量指标与回归测试

---

## 2. 已知真实问题

当前真实样本已经暴露以下问题。

### 2.1 Title 污染

Marker 可能输出：

```text
Toward robust automated cardiovascular arrhythmia detection
using self-supervised learning and 1-dimensional vision transformers OPEN
```

其中 `OPEN` 是期刊页面噪声，不属于标题。

当前 `FrontMatterCleaner` 只负责“找标题”，缺少标题尾部噪声清洗。

---

### 2.2 作者、单位、关键词没有真正抽取

Marker 第一页通常已经存在：

```text
Text       → 作者行
Text       → 摘要
Text       → Keywords ...
Footnote   → affiliation + correspondence email
```

但当前 `metadata.authors / affiliations / keywords` 可能仍为空。

说明当前 FrontMatterCleaner 功能不完整。

---

### 2.3 DOI 提取时机不合理

当前顺序：

```text
HeaderFooterCleaner
→ FrontMatterCleaner
```

但 DOI 经常出现在：

```text
PageFooter
Scientific Reports | ... | https://doi.org/10.xxxx/...
```

如果 Parser 把它映射为 NoiseBlock，而 HeaderFooterCleaner 先删除 NoiseBlock，
那么 FrontMatterCleaner 后面根本看不到 DOI。

因此 DOI 提取不能只依赖“Header/Footer 删除后的正文段落”。

---

### 2.4 AbstractRecovery 覆盖不足

当前只支持：

```text
Section title == Abstract / Summary
```

或：

```text
Abstract: ...
Abstract—...
```

但很多 Nature / Scientific Reports 文献第一页是：

```text
Title
Authors
一大段粗体摘要正文
Keywords ...
Introduction 正文
```

摘要段本身并不包含 `Abstract` 关键字。

所以当前规则会漏掉真实摘要。

---

### 2.5 CaptionRecovery 只向前扫描

当前只支持：

```text
Figure/Table
↓
Caption
```

但真实论文还可能出现：

```text
TABLE II:
caption
↓
Table
```

即 caption 在目标之前。

因此需要双向、受限的邻近关联。

---

### 2.6 ReferenceExtractor 对合并条目处理不足

Marker 可能把：

```text
1. ...
2. ...
3. ...
```

错误合并成一个 ListItem / Paragraph。

当前不按编号再次拆分，会导致 reference 数量偏少或 rawText 过长。

---

### 2.7 SectionHierarchyNormalizer 太保守

当前只做：

```text
空白归一
level 下限修复
path null 修复
```

但真实样本可能出现：

```text
Results
  Tasks and metrics

Comparative experiments
Comparison of 1-dimensional transformers
```

或：

```text
Methods
Datasets
Pre-processing
Patch segmentation strategy
Voltage standardization
Outlier handling
```

Marker 给出的层级可能不稳定。

当前原则“不发明 section”是对的，但仍可以做“基于已有标题序列的保守层级修复”。

---

### 2.8 空段落与 null-title section

Canonical 文档中可能仍出现：

```text
ParagraphBlock.text == ""
Section.title == null
```

其中部分 null-title section 实际只是承载正文的容器，部分是真正应删除的空壳。

需要区分：

```text
空 section
无标题但有正文 section
无标题但只有噪声 section
```

---

### 2.9 Table preservation 需要专门 QA

Marker 中存在 TableGroup/Table，
但 Canonical 输出的 TABLE 数量可能下降。

不能直接假设是 Cleaner 丢表，必须逐个检查：

```text
Marker TableGroup
→ Parser TableBlock
→ CaptionRecovery
→ Cleaner
→ Canonical TableBlock
```

并建立 table preservation 指标。

---

## 3. 改造原则

### 3.1 不修改 Parser 的语义职责

`MarkerDocumentParser` 继续忠实映射。

禁止：

- 在 Parser 中识别作者
- 在 Parser 中猜摘要
- 在 Parser 中修正论文标题
- 在 Parser 中重建章节层级

这些属于 Cleaner。

---

### 3.2 不用 LLM 做第一版清洗

第一版必须使用：

- block_type
- sourceBlockId
- pageRange
- order
- section title
- block text
- regex
- 位置关系
- 邻近关系
- 已知论文排版模式

原因：

```text
可解释
可测试
低成本
稳定
可回归
```

LLM 只作为未来可选增强，不放入当前主链路。

---

### 3.3 保守优先于“猜对”

当规则不能高置信判断时：

```text
宁可保留原内容
不要删除
不要虚构 metadata
不要强行改层级
```

任何恢复动作都必须可追溯。

---

## 4. 建议调整后的清洗顺序

不建议继续机械保持原 9 步顺序。

建议改为：

```text
1. FrontMatterPreScan
2. HeaderFooterCleaner
3. FrontMatterCleaner
4. AbstractRecovery
5. CaptionRecovery
6. ReferenceExtractor
7. BackMatterClassifier
8. SectionHierarchyNormalizer
9. EmptyContentCleaner
10. CanonicalDocumentValidator
```

其中新增：

```text
FrontMatterPreScan
EmptyContentCleaner
```

### 为什么需要 FrontMatterPreScan

它只“观察”，不做大规模删除。

在 Header/Footer 被删除之前先从前 1~2 页收集：

```text
DOI
journal
publication date
publisher
candidate title
candidate author line
candidate affiliation line
candidate keywords
```

保存到临时上下文：

```java
FrontMatterContext
```

后续 FrontMatterCleaner 再决定是否写入 metadata。

这样不会因为先删 PageFooter 而丢 DOI。

---

## 5. 组件级修改方案

## 5.1 FrontMatterPreScan（新增）

### 输入范围

只扫描：

```text
前 2 页
order 靠前 block
NoiseBlock / ParagraphBlock / FootnoteBlock / Section title
```

### 只做候选提取

例如：

```text
doiCandidates
titleCandidates
authorLineCandidates
affiliationCandidates
keywordCandidates
journalCandidates
publicationDateCandidates
```

不要在本阶段直接删除正文。

### DOI

支持：

```text
10.xxxx/...
https://doi.org/10.xxxx/...
doi:10.xxxx/...
```

需要去掉尾部：

```text
.
,
;
)
```

但不能破坏 DOI 中合法字符。

---

## 5.2 HeaderFooterCleaner

保持核心职责：

```text
删除 NoiseBlock
```

但新增统计：

```text
removedNoiseCount
removedNoiseTextChars
```

如 PreScan 已拿到 DOI，则删除 footer 不影响 metadata 恢复。

---

## 5.3 FrontMatterCleaner

建议拆成 5 个内部步骤。

### A. Title

先选 paper title candidate，再做 title sanitize。

新增：

```java
sanitizePaperTitle()
```

处理高置信噪声后缀：

```text
OPEN
ARTICLE
RESEARCH ARTICLE
ORIGINAL ARTICLE
```

只允许在：

```text
标题末尾
独立 token
且符合白名单
```

时删除。

禁止对正文任意删除 `OPEN`。

---

### B. Authors

只扫描 title 后、abstract/keywords 前的短段落。

作者行常见特征：

```text
2~20 个姓名 token
包含逗号 / & / and
包含上标编号
字符数通常 < 300
不以完整句号结尾
```

第一版建议：

```text
先保存 displayName/raw author text
不强制拆 firstName/lastName
```

例如：

```json
[
  {"fullName":"Mitchell Chatterjee"},
  {"fullName":"Adrian D. C. Chan"},
  {"fullName":"Majid Komeili"}
]
```

如果 Author 模型不方便改，至少把 raw author line 存入可追踪字段。

---

### C. Affiliations

优先从：

```text
FootnoteBlock
title 后的短 Paragraph
```

识别：

```text
1School of ...
2Department of ...
University
Institute
Hospital
Laboratory
```

第一版重点是“不丢”，不要求完美绑定 author ↔ affiliation。

---

### D. Keywords

支持：

```text
Keywords ...
Keywords: ...
Key words ...
Index terms ...
```

按：

```text
逗号
分号
```

拆分。

删除前缀：

```text
Keywords
Keywords:
```

并 trim / 去空。

---

### E. DOI

优先使用 FrontMatterPreScan 的候选。

如果 metadata 已有 DOI，不覆盖。

---

## 5.4 AbstractRecovery

保留原两条规则，并增加第三条：

### 路径 3：Front-matter positional abstract

只在第一页或前两页执行。

候选结构：

```text
paper title
↓
author line
↓
1~3 个长 Paragraph
↓
Keywords
```

或：

```text
paper title
↓
author line
↓
长 Paragraph
↓
正文第一段
```

高置信条件建议：

- 位于 title 后
- 位于 Keywords 前，若 Keywords 存在
- 文本长度 >= 200
- 不像 affiliation
- 不像作者行
- 不像 DOI/footer
- 不像正文 section title
- 通常为单段或少量段落

如果命中：

```text
写 abstractText
从 section.blocks 中删除摘要 block
```

必须保留 `sourceBlockId` 到 audit 或 metadata provenance。

禁止仅凭“第一篇长段落”无条件判断摘要。

---

## 5.5 CaptionRecovery

从“只向前”升级为“局部双向”。

### 优先级

```text
1. 紧邻前一个 Figure/Table
2. 紧邻后一个 Figure/Table
```

### 搜索窗口

建议最多跨：

```text
2~3 个 block
```

### 停止条件

遇到：

```text
Section boundary
长 Paragraph
另一个 Caption
另一个 Figure/Table
```

停止。

### 类型约束

如果 caption 文本以：

```text
Fig.
Figure
```

开头，只关联 Figure。

如果：

```text
Table
TABLE
```

只关联 Table。

这样能避免“最近但类型错”。

---

## 5.6 ReferenceExtractor

保留现有标题式 + 内容特征式。

新增：

```text
ReferenceItemSplitter
```

只对“疑似包含多条 reference 的单个 item”执行。

例如检测：

```text
1. ...
2. ...
3. ...
```

或：

```text
[1] ...
[2] ...
```

只有同时满足：

```text
至少发现 2 个连续编号
每段长度达到最低值
```

才拆分。

禁止根据年份随意切 reference。

### Reference metadata

当前保留：

```text
rawText
doi
year
```

是合理的。

第一版不要为了填 title/authors 强行解析复杂 citation grammar。

---

## 5.7 BackMatterClassifier

当前规则总体合理。

建议新增分类结果：

```text
REMOVE
KEEP
UNKNOWN
```

并在 audit 中记录：

```text
sectionId
title
decision
matchedRule
```

对于：

```text
Data availability
Code availability
Acknowledgements
Funding
Competing interests
Author contributions
```

保留。

对于 publisher/legal 页面继续删除。

---

## 5.8 SectionHierarchyNormalizer

这是本次改造中仅次于 FrontMatter 的重点。

### 第一阶段：先修标题文本

继续：

```text
trim
collapse whitespace
newline → space
```

并增加：

```text
去纯编号尾部异常
去明显重复 title
```

但不要改真实标题含义。

### 第二阶段：层级只做“保守纠偏”

允许的信号：

```text
Marker 原 level
Marker path
标题编号
相邻 section 顺序
常见主章节名
```

主章节白名单只用于“level anchor”，例如：

```text
Introduction
Results
Discussion
Methods
Conclusion / Conclusions
References
```

可以将这些稳定地约束为较高层级。

子标题如果连续出现在主章节之后：

```text
Methods
  Datasets
  Pre-processing
  Patch segmentation strategy
```

允许基于原始 level + 相邻结构进行纠偏。

### 禁止

- 根据标题语义自由发明父子关系
- 调用 LLM 猜层级
- 为了树好看强制所有 section 有父节点

### 新增异常检测

输出 warning：

```text
LEVEL_JUMP
NULL_TITLE_WITH_CHILDREN
SECTION_PATH_CONFLICT
DUPLICATE_SECTION_TITLE_ADJACENT
ORPHAN_SUBSECTION
```

---

## 5.9 EmptyContentCleaner（替代单纯 EmptySectionCleaner）

执行顺序：

```text
1. 删除 text blank 的 ParagraphBlock
2. 删除 caption blank
3. 清理空 ListItem
4. List 为空则删除 ListBlock
5. 递归删除真正空 section
```

但：

```text
title == null && blocks 非空
```

不能直接删除。

它可能是合法的正文容器。

---

## 5.10 CanonicalDocumentValidator

保留现有 hard error / warning。

建议新增指标：

```text
metadataCompleteness
abstractRecovered
authorCount
keywordCount
doiPresent
figureCaptionCoverage
tableCaptionCoverage
markerFigureCount
canonicalFigureCount
markerTableCount
canonicalTableCount
referenceSplitCount
sectionHierarchyWarningCount
blankParagraphCount
```

### Preservation metrics

尤其增加：

```text
figurePreservationRatio
tablePreservationRatio
```

例如：

```text
canonicalTableCount / parsedTableCount
```

注意：

这个指标只作为 warning / QA signal，
不能因为不是 1.0 就自动判 FAILED。

因为某些 Table 可能是 Marker 误识别或被合法合并。

---

## 6. 不建议现在做的事情

当前版本禁止：

- 引入 LLM 参与 Cleaner 主链路
- 根据全文语义重写标题
- 自动修复 Ghostscript 导致的 `e / oen / classication / Ƥne` 字符错误
- 猜 reference title / authors
- 复杂跨栏版面推理
- 自己生成缺失 section
- 修改 Marker API 的 parse quality 逻辑
- 修改 surya-vllm
- 重构整个 AcademicDocument 模型

Ghostscript 字符异常属于：

```text
上游文本 fidelity 问题
```

不要用 Cleaner 做词典式“猜字修复”。

---

## 7. 推荐开发优先级

### P0：必须先修

```text
1. DOI 在 HeaderFooterCleaner 前丢失
2. title 尾部 OPEN 污染
3. abstract positional recovery
4. keywords 提取
5. authors / affiliations 基础恢复
```

### P1：紧接着修

```text
6. Caption 双向关联
7. Reference 多条合并拆分
8. 空 Paragraph 清理
9. Section hierarchy warning + 保守纠偏
```

### P2：QA 增强

```text
10. Figure/Table preservation metrics
11. BackMatter audit
12. metadata completeness metrics
```

---

## 8. 回归测试要求

必须保留当前真实论文作为 fixture。

至少建立 3 类测试。

### A. Unit test

每个 Cleaner 独立测试。

例如：

```text
title + OPEN → OPEN 被删
DOI 在 NoiseBlock → 仍可恢复
Keywords Cardiac..., Deep... → 解析成 keyword list
Abstract 无 "Abstract" 标记 → positional recovery
TABLE II caption 在 Table 前 → 正确关联
2 条 reference 合在 1 个 item → 正确拆成 2 条
```

---

### B. Pipeline test

输入 Parsed AcademicDocument，
运行完整 PaperCleaner，
断言：

```text
title
authors
keywords
doi
abstractText
sectionCount
figureCount
tableCount
referenceCount
```

---

### C. RealPaperQaTest

真实 PDF：

```text
Toward robust automated cardiovascular arrhythmia detection
using self-supervised learning and 1-dimensional vision transformers
```

至少检查：

```text
title 不含 OPEN
authors 非空
abstractText 非空
keywords 非空
doi == 10.1038/s41598-026-41549-5
referenceCount 合理
figureCount 不异常下降
tableCount 不异常下降
无 blank paragraph
无明显空 section
```

不要在测试里要求 Ghostscript 字符错误被 Cleaner 修复。

---

## 9. Agent 执行方式

在修改代码前，必须先完成代码审查。

### 第一步：只读检查

检查：

```text
document/cleaner/PaperCleaner.java
HeaderFooterCleaner.java
FrontMatterCleaner.java
AbstractRecovery.java
CaptionRecovery.java
ReferenceExtractor.java
BackMatterClassifier.java
SectionHierarchyNormalizer.java
EmptySectionCleaner.java
CanonicalDocumentValidator.java
AcademicDocument / Section / block model
现有 cleaner tests
RealPaperQaTest
```

### 第二步：输出修改方案

先给用户：

```text
1. 当前每个问题的代码原因
2. 计划新增/修改哪些文件
3. 每个文件改什么
4. 哪些规则保持不变
5. 新增哪些测试
6. 是否涉及数据模型变化
```

在用户确认前：

**不要修改代码。**

### 第三步：分阶段实施

建议：

```text
Phase 1 FrontMatter + Abstract
Phase 2 Caption + Reference
Phase 3 Hierarchy + EmptyContent
Phase 4 Validator + QA metrics
```

每阶段完成后运行：

```text
mvn test
指定 cleaner unit tests
RealPaperQaTest
```

### 第四步：输出回归结果

必须给出：

```text
改造前
改造后
```

对比：

```text
title
authors
abstract
keywords
doi
sections
figures
tables
references
warnings
```

---

## 10. 验收标准

第一阶段完成后，至少满足：

```text
Title：
不含确定性的 publisher badge / OPEN 噪声

Authors：
真实论文不再为空

Keywords：
真实论文不再为空

DOI：
即使 DOI 原始位于 PageFooter，也能恢复

Abstract：
支持无 "Abstract" 标题的 front-matter 摘要

Caption：
支持 caption 在对象前或后的局部关联

References：
支持高置信的多编号 item 拆分

Hierarchy：
不发明 section，但减少明显的 level/path 冲突

Content：
blank Paragraph 不进入最终 Canonical 文档

Preservation：
Figure/Table 数量变化可监控、可解释
```

最终仍坚持：

```text
Parser 忠实
Repair 保守
Cleaner 语义化
Validator 可观测
规则高置信优先
不确定时宁可保留
```
