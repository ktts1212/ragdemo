# Skill：Marker Cleaner 最后一轮收尾改造

## 1. 目标

你是一个负责 Java 学术文献 RAG 清洗流水线的工程 Agent。

当前主链路：

```text
PDF
→ Marker
→ MarkerDocumentParser
→ ReadingOrderRepair
→ PaperCleaner
→ Canonical AcademicDocument
→ CanonicalDocumentValidator
```

当前 Cleaner 已经能够完成：

- title 提取
- authors 提取
- keywords 提取
- DOI 提取
- abstract 恢复
- Figure/Table 基础恢复
- References 抽取
- Back Matter 基础处理

但真实论文 QA 仍存在 3 类高收益问题：

1. `FrontMatterCleaner`
2. `SectionHierarchyNormalizer`
3. `CaptionRecovery`

本轮目标不是追求“完美还原论文”，而是：

> 在不引入复杂语义猜测、不使用 LLM 的前提下，把 CanonicalDocument 清洗到足够稳定、足够干净，适合后续 SectionAwareChunker、Embedding 和 RAG。

---

# 2. 核心原则

必须继续遵守：

```text
Parser 忠实
Repair 保守
Cleaner 语义化
Validator 可观测
```

本轮额外原则：

```text
高置信问题才修
不确定时宁可保留
不发明内容
不修 Ghostscript 字符错误
不做无限规则堆叠
```

不要处理：

```text
e → The
oen → often
classication → classification
Ƥne → fine
eƥcient → efficient
```

这类字符问题属于上游 PDF/字体/Marker 文本 fidelity 问题，不属于 Cleaner。

---

# 3. 本轮只改 3 个组件

```text
1. FrontMatterCleaner
2. SectionHierarchyNormalizer
3. CaptionRecovery
```

除非现有代码结构确实需要极小辅助类，否则不要扩展成新的大规模 Cleaner 体系。

---

# 4. FrontMatterCleaner 改造

## 4.1 当前问题

当前 metadata 已经可以提取：

```text
title
authors
affiliations
keywords
doi
abstract
```

但仍存在：

```text
author line 仍留在正文
Keywords 行仍留在正文
affiliation footnote 仍留在正文
affiliation 被重复构造
root Section.title 仍包含 OPEN
root Section.path 仍包含 OPEN
```

因此本轮 FrontMatterCleaner 重点是：

```text
提取后消费
affiliation 正确拆分
metadata 与 root section 同步
```

---

## 4.2 Metadata 成功提取后 consume 原 block

只有在高置信、且 metadata 写入成功后，才从正文删除对应 block。

### Author line

若某 ParagraphBlock 被确认用于构建：

```text
metadata.authors
```

则从 section.blocks 删除。

例如：

```text
Mitchell Chatterjee1, Adrian D. C. Chan2 & Majid Komeili1
```

提取成功后，不能继续作为正文 chunk。

### Keywords

若：

```text
Keywords Cardiac arrhythmia, Deep learning, ...
```

已经成功进入：

```text
metadata.keywords
```

则移除该 ParagraphBlock。

### Affiliation / correspondence

若 FootnoteBlock 已被成功用于构建 affiliation：

```text
1School of Computer Science...
2Department of Systems and Computer Engineering...
email: ...
```

则从正文移除该 FootnoteBlock。

### 重要规则

禁止：

```text
先删 block
后尝试解析
```

必须：

```text
识别
→ 成功构建 metadata
→ 再 consume
```

避免误删。

---

## 4.3 Affiliation 正确拆分

当前真实样本：

```text
1School of Computer Science, Carleton University,
Ottawa K1S 5B6, Canada.
2Department of Systems and Computer Engineering,
Carleton University, Ottawa K1S 5B6, Canada.
email: mitchellchatterjee@cmail.carleton.ca
```

期望至少拆成：

```text
Affiliation 1
institution/raw:
School of Computer Science, Carleton University
Ottawa K1S 5B6, Canada

Affiliation 2
institution/raw:
Department of Systems and Computer Engineering,
Carleton University
Ottawa K1S 5B6, Canada
```

### 推荐规则

识别 affiliation marker：

```regex
(?<!\d)(\d{1,2})(?=[A-Z])
```

或更保守地识别：

```text
1School...
2Department...
```

把一个 Footnote 中连续编号的单位拆开。

### 去重

最终写入 metadata 前做 normalized 去重：

```text
lowercase
trim
collapse whitespace
remove leading marker number
```

避免同一个 affiliation 被添加两次。

### email

email 不应该留在 affiliation institution 字段中。

可：

- 暂时单独去除并忽略
- 或在高置信时绑定到作者

第一版不强制实现 author-email binding。

---

## 4.4 Root title/path 同步

真实情况可能是：

```text
metadata.title
= "... vision transformers"

rootSection.title
= "... vision transformers OPEN"
```

这是 CanonicalDocument 内部不一致。

### 推荐处理

如果：

```text
root section sourceBlockId
```

就是 paper title 对应的 SectionHeader，
并且：

```text
metadata.title 已成功 sanitize
```

则：

```text
rootSection.title = metadata.title
```

同时递归修正 path 中对应旧 root title：

```text
old:
["... transformers OPEN", "Results", ...]

new:
["... transformers", "Results", ...]
```

### 禁止

不要全局 string replace 所有 section title。

只替换：

```text
明确等于旧 root title 的 path segment
```

---

# 5. SectionHierarchyNormalizer 改造

## 5.1 目标

不是重建完整论文语义树。

只解决明显结构断层，例如：

```text
Results
└── Tasks and metrics

Comparative experiments

Comparison of 1-dimensional transformers
├── PTB-XL ...
├── Unified ...
└── STEMI ...
```

其中：

```text
Comparison of 1-dimensional transformers
```

不应该突然成为新的 level 1 root。

---

## 5.2 使用的信号

只允许使用：

```text
当前 section.level
当前 section.path
section 顺序 order
Marker 原始层级
父 section
前一个 section
后一个 section
常见主章节名
相邻 title 结构
```

禁止使用 LLM。

---

## 5.3 主章节 anchor

可以维护一个小型、稳定的主章节集合：

```text
Introduction
Background
Methods
Materials and methods
Results
Discussion
Conclusion
Conclusions
References
```

它们可以作为 hierarchy anchor。

不要因为标题里包含普通词就自动判主章节。

---

## 5.4 明显断层修复规则

### Rule A：level 突然掉到 1

如果某 section：

```text
level == 1
```

但满足：

```text
前面仍处于某个主章节上下文
并且该 section 不属于主章节白名单
并且其后多个 section 原本都是其 level 2 children
```

则将其降为当前主章节下的 subsection。

示例：

```text
Results            level 2/root-child
Comparative experiments
Comparison of 1-dimensional transformers   ← Marker level 1
PTB-XL multi-label classification          level 2
Unified multi-label classification         level 2
STEMI binary classification                level 2
```

可修为：

```text
Results
├── Tasks and metrics
└── Comparative experiments
    └── Comparison of 1-dimensional transformers
        ├── PTB-XL ...
        ├── Unified ...
        └── STEMI ...
```

但必须保守。

---

### Rule B：父子关系连续性

如果：

```text
A
B
C
```

并且 B、C 在原 Marker hierarchy 中明显是递进关系，
但 B 被错误抬高，则允许基于相邻层级恢复。

优先使用原始：

```text
section_hierarchy
path depth
level
```

而不是纯标题语义。

---

## 5.5 不要做的层级修复

禁止：

```text
根据 "我觉得这个标题像子标题" 就重挂
根据全文主题自动构建树
把所有 level 1 都强行挂到最近主章节
让 hierarchy 变漂亮而篡改结构
```

---

## 5.6 Path 必须同步

任何 hierarchy 改动后：

```text
level
parent/children
path
```

必须一起更新。

禁止出现：

```text
level = 3
path.size() = 1
```

这种不一致。

---

## 5.7 新增 warning / audit

建议输出：

```text
HIERARCHY_LEVEL_DROP_REPAIRED
ORPHAN_SUBSECTION
LEVEL_PATH_MISMATCH
ROOT_SECTION_REATTACHED
```

保留：

```text
sectionId
oldLevel
newLevel
oldPath
newPath
reason
```

---

# 6. CaptionRecovery 改造

## 6.1 当前问题

当前已经能处理：

```text
Figure
↓
Caption
```

但真实文档仍可能有：

```text
Caption
↓
Figure
```

或者孤立：

```text
Fig. 3. ...
```

没有挂到 Figure。

---

## 6.2 改成受限双向关联

对于每个 CaptionBlock：

### 第一步：判断类型

```text
Fig.
Figure
→ FIGURE

Table
TABLE
→ TABLE
```

无法判断类型时：

```text
UNKNOWN
```

UNKNOWN 只允许非常近距离关联。

---

### 第二步：优先向前

最多向前扫描 2~3 个 block。

遇到：

```text
Figure/Table
```

且类型匹配，则关联。

遇到：

```text
长 Paragraph
Section boundary
另一个 Caption
不匹配的 Figure/Table
```

停止。

---

### 第三步：向后扫描

如果前向没有找到，再向后扫描 2~3 个 block。

同样要求：

```text
类型匹配
距离近
没有跨长正文
```

---

## 6.3 不跨 section

第一版禁止：

```text
Caption 跨 Section 关联
Caption 跨页远距离关联
```

如果确实无法关联：

```text
保留 CaptionBlock
记录 warning
```

不要删除。

---

## 6.4 关联后动作

保持当前行为：

```text
caption text → Figure/Table.caption
caption.sourceBlockId → sourceBlockIds
移除独立 CaptionBlock
```

若目标已有 caption：

```text
不重复
```

必要时只补缺失部分。

---

# 7. 验收目标

本轮不是追求“0 warning”，而是达到以下状态。

---

## 7.1 FrontMatter

真实论文期望：

```text
metadata.title 不含 OPEN
metadata.authors 非空
metadata.keywords 非空
metadata.doi 非空
metadata.abstractText 非空
metadata.affiliations 不重复
```

同时正文中不再重复：

```text
author line
keywords line
已消费 affiliation footnote
abstract paragraph
```

---

## 7.2 Root consistency

必须满足：

```text
metadata.title == rootSection.title
```

若保留 paper-title root section。

并且：

```text
所有 descendant path 第一项
== metadata.title
```

---

## 7.3 Hierarchy

至少消除明显异常：

```text
Comparison of 1-dimensional transformers
```

不再无原因成为独立 level 1 root。

要求：

```text
level 与 path depth 基本一致
明显 orphan subsection 数减少
主章节树更连续
```

不要求所有论文都 100% 正确。

---

## 7.4 Caption

目标：

```text
明显孤立 Fig./Table Caption 数减少
Figure/Table caption coverage 上升
不发生明显错误跨对象关联
```

---

# 8. Validator 建议增加的检查

如果当前 Validator 已方便修改，可以增加轻量检查：

```text
frontMatterDuplicateCount
orphanCaptionCount
levelPathMismatchCount
duplicateAffiliationCount
rootTitleMismatch
```

这些都只作为：

```text
warning / metric
```

不要轻易升级为 hard error。

---

# 9. 必须新增的测试

## 9.1 FrontMatterCleanerTest

### Case 1

输入：

```text
title = "... OPEN"
```

期望：

```text
metadata.title = "..."
rootSection.title = "..."
path 第一项 = "..."
```

### Case 2

作者提取成功后：

```text
author paragraph 从正文消失
```

### Case 3

keywords 提取成功后：

```text
keywords paragraph 从正文消失
```

### Case 4

一个 Footnote 含两个编号 affiliation：

```text
1School...
2Department...
```

期望得到两个不同 affiliation，
不能得到两个重复对象。

---

## 9.2 SectionHierarchyNormalizerTest

构造：

```text
Results
  Tasks and metrics
Comparative experiments
Comparison of 1-dimensional transformers
  PTB-XL
  Unified
  STEMI
```

验证：

```text
Comparison... 不再是独立 root
path/level 同步
```

同时加入一个不确定案例：

```text
Novel framework
```

没有足够依据时，不允许强行重挂。

---

## 9.3 CaptionRecoveryTest

至少测试：

```text
Figure → Caption
Caption → Figure
Table → Caption
Caption → Table
Caption 与 Figure 中间隔一个短 block
Caption 前后都没有目标
Caption 类型与目标类型不一致
```

最后一种必须保留 Caption，不得误挂。

---

# 10. RealPaperQaTest 验收

真实论文：

```text
Toward robust automated cardiovascular arrhythmia detection
using self-supervised learning and 1-dimensional vision transformers
```

至少断言：

```text
title 不含 OPEN
authors.size == 3
keywords 非空
doi == 10.1038/s41598-026-41549-5
abstractText 非空
affiliations 至少能区分 School / Department
正文不再包含原 author line
正文不再包含 Keywords 行
root title 不含 OPEN
root path 不含 OPEN
orphan caption 数下降
明显 hierarchy root 断层消失
```

不要断言：

```text
Ghostscript 字符错误被修复
```

---

# 11. Agent 执行规则

## 第一步：只读代码

必须先阅读：

```text
FrontMatterCleaner.java
SectionHierarchyNormalizer.java
CaptionRecovery.java
PaperCleaner.java
CanonicalDocumentValidator.java
AcademicDocument.java
Section.java
Author.java
Affiliation.java
DocumentBlock 相关类
现有 cleaner tests
RealPaperQaTest
```

---

## 第二步：只输出设计

先输出：

```text
1. 当前代码问题
2. 准备修改哪些方法
3. 每个方法改什么
4. 是否新增 helper
5. 是否改模型
6. 新增测试
7. 风险点
```

用户确认前：

**禁止修改代码。**

---

## 第三步：分 3 个阶段改

```text
Phase 1
FrontMatterCleaner

Phase 2
SectionHierarchyNormalizer

Phase 3
CaptionRecovery + Validator warning
```

每阶段完成后运行对应 unit tests。

---

## 第四步：跑完整回归

运行：

```text
mvn test
RealPaperQaTest
```

输出改造前后对比：

```text
metadata
front matter duplicate
affiliation count
root title/path
section roots
orphan captions
figure count
table count
reference count
warnings
```

---

# 12. 停止条件

本轮完成后，如果：

```text
Front Matter 不重复
affiliation 不重复
root title/path 一致
明显 hierarchy 断层消失
orphan caption 明显减少
Figure/Table/Reference 无异常下降
```

则停止继续打磨 Cleaner。

不要无限追加规则。

下一阶段进入：

```text
CanonicalDocument
→ SectionAwareChunker
→ Chunk QA
→ Embedding
→ pgvector
```

最终原则：

> Cleaner 的目标不是完美理解论文，而是把 Marker 的结果整理成稳定、可追溯、适合 RAG 的结构化文档。
