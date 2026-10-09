# Skill：Marker Cleaner 最终收尾与冻结

## 1. 角色与目标

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

当前 Cleaner 已经完成主要能力：

- title 提取与 OPEN 等尾部噪声清理
- authors 提取
- affiliations 基础拆分
- keywords 提取
- DOI 提取
- abstract 恢复
- Figure/Table 基础恢复
- References 抽取
- Back Matter 基础处理
- root title/path 同步
- section hierarchy 基础纠偏
- front matter consume

真实论文 QA 已经表明整体结果基本可用。

本轮只做最后 3 个小问题收尾：

1. affiliation 尾部残留 `email:`
2. null-title section 的 level/path 不一致
3. 剩余 orphan caption（Fig. 3）

目标不是继续扩展 Cleaner，而是：

> 用最小修改消除高置信、低风险的残余问题，然后冻结 Cleaner，进入 SectionAwareChunker。

---

# 2. 核心原则

必须坚持：

```text
Parser 忠实
Repair 保守
Cleaner 语义化
Validator 可观测
```

本轮额外原则：

```text
只修确定性问题
不增加复杂语义推断
不引入 LLM
不跨页大范围猜关联
不重构现有流水线
不继续扩展规则面
```

不要处理：

```text
e → The
oen → often
classication → classification
Ƥne → fine
eƥcient → efficient
```

这类属于上游 Ghostscript / Marker 文本 fidelity 问题。

---

# 3. 本轮只允许修改的区域

优先只检查和修改：

```text
FrontMatterCleaner.java
SectionHierarchyNormalizer.java
CaptionRecovery.java
CanonicalDocumentValidator.java
相关 unit tests
RealPaperQaTest.java
```

除非现有结构必须，否则不要新增新的大模块。

---

# 4. FrontMatterCleaner：清理 affiliation 尾部残留

## 4.1 当前问题

真实结果中已经正确拆成两个 affiliation：

```text
School of Computer Science, Carleton University

Department of Systems and Computer Engineering, Carleton University
```

但第二个 address 仍可能残留：

```text
... Ottawa K1S 5B6, Canada. email:
```

说明 Footnote 拆分后，对 correspondence/email 段处理不彻底。

---

## 4.2 目标

最终 affiliation address 中不能残留孤立：

```text
email:
e-mail:
correspondence:
corresponding author:
```

如果后面没有真实 email 地址，则直接去掉这些尾部标记。

如果有真实 email：

```text
email: mitchell@...
```

则：

```text
affiliation address 中去掉 email 部分
```

email 可：

```text
A. 暂时忽略
B. 若已有高置信 author binding 逻辑则写入 author.email
```

第一版不要求强制绑定。

---

## 4.3 推荐规则

在 affiliation normalize 阶段执行：

```text
trim
collapse whitespace
remove trailing correspondence label
remove trailing email label
remove full email segment from affiliation address
```

示例：

```text
Department ..., Canada. email:
→ Department ..., Canada.
```

```text
Department ..., Canada. email: abc@x.com
→ Department ..., Canada.
```

### 禁止

不要删除正文中普通出现的 `email` 单词。

只处理已被识别为 affiliation/correspondence 的 front-matter block。

---

# 5. SectionHierarchyNormalizer：处理 null-title level/path mismatch

## 5.1 当前问题

真实结果仍可能出现：

```text
title = null
level = 2
path = ["Discussion"]
```

这是内部结构不一致。

但 null-title section 本身不一定是错误，因为它可能只是承载正文的中间容器。

---

## 5.2 目标

不要为了“树漂亮”强行给 null-title section 起名字。

只保证内部一致性。

---

## 5.3 推荐规则

### 情况 A：title == null 且有 blocks

保留 section。

如果：

```text
path 非空
```

则：

```text
level = path.size()
```

或者根据父节点：

```text
level = parent.level + 1
```

二者优先使用父子真实关系。

### 情况 B：title == null 且无 blocks但有 children

保留容器，但 level/path 必须和父节点一致。

### 情况 C：title == null 且无 blocks且无 children

交给 EmptyContentCleaner 删除。

---

## 5.4 Validator 增加 warning

如果仍存在：

```text
level != path.size()
```

记录：

```text
LEVEL_PATH_MISMATCH
```

如果：

```text
title == null
```

但 section 非空，可记录：

```text
UNNAMED_NONEMPTY_SECTION
```

仅 warning，不 hard fail。

---

# 6. CaptionRecovery：处理最后一个 orphan caption

## 6.1 当前问题

CanonicalDocument 中仍有一个独立 CaptionBlock：

```text
Fig. 3. AUROC score plotted against average label confidence...
```

说明当前局部双向关联仍未命中。

---

## 6.2 第一原则

先查清楚为什么没关联，再决定要不要改。

Agent 必须先定位：

```text
该 Caption 的 section
前 3 个 block
后 3 个 block
同页 Figure/Table
section boundary
order
pageRange
```

禁止直接扩大搜索范围。

---

## 6.3 允许的修复

仅当存在明确局部关系，例如：

```text
Figure
短文本/空 block
Caption
```

或：

```text
Caption
短文本/空 block
Figure
```

则允许扩展局部扫描。

### 推荐扫描窗口

最多：

```text
前后 3~4 个 block
```

### 允许跳过

仅：

```text
空 Paragraph
极短说明性 block
Noise-like block
```

### 停止条件

遇到：

```text
长 Paragraph
另一个 Caption
另一个 Figure/Table
Section boundary
page change 且无明显关联
```

立即停止。

---

## 6.4 类型必须匹配

```text
Fig.
Figure
→ 只能关联 Figure

Table
TABLE
→ 只能关联 Table
```

UNKNOWN caption 不要扩大搜索范围。

---

## 6.5 如果仍无法高置信关联

保留 CaptionBlock。

记录：

```text
ORPHAN_CAPTION
```

不要为了追求 0 orphan caption 而误挂。

---

# 7. Validator 最后增加的轻量指标

建议只增加以下几个：

```text
orphanCaptionCount
levelPathMismatchCount
unnamedNonEmptySectionCount
duplicateAffiliationCount
frontMatterDuplicateCount
rootTitleMismatch
```

全部：

```text
warning / metric
```

不要升级为 hard error。

---

# 8. 必须新增/补充的测试

## 8.1 FrontMatterCleanerTest

### Case 1

输入：

```text
Department ..., Canada. email:
```

期望：

```text
Department ..., Canada.
```

### Case 2

输入：

```text
Department ..., Canada. email: abc@example.com
```

期望：

```text
address 不含 email 段
```

---

## 8.2 SectionHierarchyNormalizerTest

### Case 1

输入：

```text
title = null
level = 2
path = ["Discussion"]
有正文 block
```

期望：

```text
内部一致
不创建新 title
不删除正文
```

### Case 2

输入：

```text
title = null
blocks = []
children = []
```

期望：

```text
最终由 EmptyContentCleaner 删除
```

---

## 8.3 CaptionRecoveryTest

构造与真实 Fig.3 相同的局部结构。

至少测试：

```text
Figure → short block → Caption
Caption → short block → Figure
```

必须正确关联。

同时测试：

```text
Figure → long Paragraph → Caption
```

此时禁止跨长正文关联。

---

# 9. RealPaperQaTest 最终验收

真实论文：

```text
Toward robust automated cardiovascular arrhythmia detection
using self-supervised learning and 1-dimensional vision transformers
```

最终至少满足：

```text
metadata.title 不含 OPEN
authors.size == 3
keywords 非空
doi 正确
abstractText 非空

正文不含：
- author line
- Keywords 行
- affiliation footnote
- abstract 原段

affiliations：
- 至少区分 School / Department
- address 不含孤立 "email:"

root title/path 一致

level/path mismatch == 0
或仅剩明确可接受 warning

orphan caption：
- 若 Fig.3 可低风险关联，则归零
- 若不能低风险关联，则最多保留 1 个 warning

Figure/Table/Reference 数量不异常下降
```

不要断言：

```text
Ghostscript 字符错误被修复
```

---

# 10. Agent 执行步骤

## 第一步：只读检查

检查：

```text
FrontMatterCleaner.java
SectionHierarchyNormalizer.java
CaptionRecovery.java
CanonicalDocumentValidator.java
相关 tests
真实 JSON QA 输出
```

重点定位：

```text
为什么 affiliation 末尾有 email:
为什么 null-title section level/path 不一致
为什么 Fig.3 caption 没有关联
```

---

## 第二步：先给修改方案

只输出：

```text
1. 根因
2. 准备改哪些方法
3. 每个方法怎么改
4. 新增哪些测试
5. 风险
```

用户确认前：

**禁止修改代码。**

---

## 第三步：最小修改

优先顺序：

```text
Phase 1 affiliation cleanup
Phase 2 level/path consistency
Phase 3 orphan caption
```

每阶段都运行对应 unit test。

---

## 第四步：完整回归

运行：

```text
mvn test
RealPaperQaTest
```

并输出：

```text
改前 / 改后
```

对比：

```text
affiliation address
levelPathMismatchCount
orphanCaptionCount
figureCount
tableCount
referenceCount
warnings
```

---

# 11. 冻结条件

如果最终满足：

```text
Front Matter 无重复
affiliation 无明显残留
root title/path 一致
level/path 一致
orphan caption 可接受
Figure/Table/Reference 无异常下降
Validator 无严重 warning
```

则：

> 冻结 Cleaner。

不要再追加新的语义规则。

下一阶段正式进入：

```text
CanonicalDocument
→ SectionAwareChunker
→ Chunk QA
→ Embedding
→ pgvector
```

最终原则：

> Cleaner 的目标不是完美还原论文，而是得到稳定、干净、可追溯、适合 RAG 的 CanonicalDocument。
