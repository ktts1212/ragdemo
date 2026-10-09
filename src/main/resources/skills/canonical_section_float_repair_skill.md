# Skill：Canonical Section Hierarchy 与 Figure/Table 归属通用修复

## 1. 角色与目标

你是一个负责 Java 学术文献 RAG 清洗流水线中 **Section 层级纠偏与浮动 Figure/Table 归属修复** 的工程 Agent。

当前主链路：

```text
PDF
→ Marker API
→ MarkerDocumentParser
→ ReadingOrderRepair
→ PaperCleaner
→ Canonical AcademicDocument
→ CanonicalDocumentValidator
→ SectionAwareChunker
```

当前问题不是某一篇论文的特例，而是学术 PDF 中常见的两类结构异常：

```text
A. Heading hierarchy 错位
B. Figure/Table 因 float 排版被归到错误 Section
```

目标：

> 用确定性、保守、可解释、可测试的规则修复具有普适性的 Section hierarchy 和 Figure/Table 归属问题，而不是为某一篇论文写硬编码。

---

## 2. 核心原则

必须遵守：

```text
Parser 忠实
Repair 保守
Cleaner 语义化
Chunker 不修上游结构
```

本轮额外原则：

```text
只修高置信结构错误
不针对单篇论文标题写死规则
不使用 LLM
不依赖出版社模板
不依赖具体页码
不按视觉距离简单绑定 Figure/Table
```

允许使用：

```text
Section.title / level / path / order
parent / children
原 Marker hierarchy
相邻 Section
Heading 编号模式
常见主章节 anchor
Figure/Table label/caption
正文 "Fig. N" / "Figure N" / "Table N" 引用
block order / pageRange
```

---

## 3. 建议组件

推荐在 Cleaner 后半段明确增加：

```text
SectionHierarchyRepair
FloatBlockSectionReassigner
```

顺序：

```text
...
BackMatterClassifier
SectionHierarchyNormalizer
SectionHierarchyRepair
FloatBlockSectionReassigner
EmptyContentCleaner
CanonicalDocumentValidator
```

职责分开：

```text
SectionHierarchyRepair
→ 修 Section tree / level / path

FloatBlockSectionReassigner
→ 修 Figure/Table 所属 Section
```

---

# 4. 顶级 Heading reset

## 4.1 问题

可能出现：

```text
Discussion
└── Methods
    └── Datasets
```

实际上 `Methods` 应开启新的顶级分支。

## 4.2 通用做法

维护小型 canonical major-heading 集：

```text
Introduction
Background
Related Work
Methods
Materials and Methods
Methodology
Experimental Setup
Experiments
Results
Discussion
Conclusion
Conclusions
Limitations
References
Supplementary Material
```

做 normalized exact match：

```text
lowercase
trim
collapse whitespace
remove trailing colon
remove numeric prefix
```

例如：

```text
"3. Methods" → "methods"
"METHODS:" → "methods"
```

不要因为标题里“包含 methods/results/discussion”就判定主章节。

## 4.3 Root reset 条件

当 Section 满足以下高置信条件时，可提升为新的 root-level branch：

```text
1. title 命中 major-heading anchor
2. 当前 parent 不是文档 root
3. 当前 parent 也是正文 Section
4. 原 Marker level/path 与当前嵌套关系冲突，或当前 level 明显过深
```

可使用简单打分：

```text
+2 major anchor exact match
+1 原 Marker level <= 2
+1 当前 parent 也是 major heading
+1 相邻存在其他 root-level major heading
```

达到阈值才修。

## 4.4 修复动作

```text
old:
Discussion
└── Methods
    └── Datasets

new:
Discussion
Methods
└── Datasets
```

必须同步：

```text
parent
children
level
path
所有 descendant path
```

---

# 5. 同级 Heading 纠偏

## 5.1 问题

Marker 可能把本应同级的标题变成父子：

```text
A
└── B
```

实际应该：

```text
A
B
```

例如当前论文中的：

```text
Scientific contributions
Clinical relevance
```

以及：

```text
Pre-processing
Baseline models
```

只是回归样例，代码中禁止写死这些标题。

## 5.2 编号标题：优先使用

支持：

```regex
^\d+(\.\d+)*[\.\)]?\s+
^[A-Z][\.\)]\s+
^[IVXLC]+[\.\)]\s+
```

解析 heading depth：

```text
2       → depth 1
2.1     → depth 2
2.1.1   → depth 3
```

如果连续 heading 编号 depth 相同，则强烈支持 sibling。

## 5.3 无编号标题

只在结构证据充分时修：

```text
1. B 当前被挂在 A 下
2. A/B 原 Marker depth 相同或接近
3. A/B 都有独立正文内容
4. A 的正文在 B 出现前已自然结束
5. B 后续出现 C，且 B/C 结构特征相似
6. A/B 不像明显“主章节 → 子章节”
```

建议至少满足 3~4 个结构证据。

## 5.4 Sibling promotion

```text
old:
Parent
└── A
    └── B

new:
Parent
├── A
└── B
```

同步：

```text
B.parent
A.children
Parent.children
B.level
B.path
B descendants path
```

禁止依据标题长度、词义相似、LLM 直觉来判断同级。

---

# 6. Figure/Table float 归属修复

## 6.1 问题本质

Figure/Table 常为浮动元素：

```text
Section A 正文引用 Table 3
↓
Section B 标题已经出现
↓
Table 3 排版在页底
```

若只按最近 SectionHeader 或物理位置归属，就容易错。

因此应优先利用正文显式引用。

## 6.2 新增 FloatBlockSectionReassigner

只处理：

```text
FIGURE
TABLE
```

## 6.3 解析编号

从 label/caption 提取标号：

```regex
(?i)\b(?:fig(?:ure)?\.?)\s*(S?\d+[A-Za-z]?)\b
(?i)\btable\s*(S?\d+[A-Za-z]?)\b
```

得到：

```text
FIGURE:4
TABLE:3
```

支持：

```text
Fig. 1
Figure 1
Fig 1
Table 1
Fig. S2
Table S3
```

## 6.4 扫描正文引用

在 Paragraph/List 中查：

```text
Fig. 4
Figure 4
Table 3
```

记录：

```text
type
number
sectionId
sectionPath
blockId
order
pageRange
text
```

构建索引：

```text
TABLE:3 → references[]
FIGURE:4 → references[]
```

## 6.5 高置信 reassignment

只在以下条件成立时移动：

```text
1. 类型 + 编号完全匹配
2. 存在正文显式引用
3. 引用 Section 与当前 Section 不一致
4. 引用与 float block 的 page/order 距离合理
5. 引用具有较强定义性
```

定义性引用优先：

```text
shown in Fig. N
illustrated in Fig. N
available in Table N
results in Table N
shown in Table N
see Table N
```

其次选择：

```text
float block 之前最近的匹配引用
```

## 6.6 多次引用

若同一 Figure/Table 被多个 Section 引用：

```text
优先首次定义性引用
→ 再看前向最近引用
→ 仍不确定则不自动移动
```

禁止为了归零 mismatch 而强行选一个 Section。

## 6.7 范围限制

建议配置：

```text
maxPageDistance <= 2~3
或
maxBlockOrderDistance <= 合理阈值
```

超出只 warning，不自动修。

## 6.8 修复动作

只移动 block 所属 section：

```text
oldSection.blocks.remove(block)
targetSection.blocks.add(block)
按 order 重排
```

不改：

```text
caption
content
sourceBlockIds
pageRange
```

Chunker 后续自然继承正确 sectionPath。

---

# 7. 修复顺序

必须：

```text
1. SectionHierarchyRepair
2. FloatBlockSectionReassigner
3. EmptyContentCleaner
4. CanonicalDocumentValidator
```

先稳定 Section tree，再做 Figure/Table 归属。

---

# 8. Audit

所有自动修复都必须可追踪。

Section audit：

```text
sectionId
title
oldParentId
newParentId
oldLevel
newLevel
oldPath
newPath
reason
confidenceSignals
```

Float audit：

```text
blockId
blockType
identifier
oldSectionId
newSectionId
referenceBlockId
referenceText
pageDistance
orderDistance
reason
```

---

# 9. Validator 指标

建议增加：

```text
majorHeadingNestedUnderMajorHeadingCount
sameDepthHeadingParentChildConflictCount
floatReferenceSectionMismatchCount
unresolvedFloatReferenceCount
sectionPathMismatchCount
```

都先做 warning/metric，不轻易 hard fail。

---

# 10. 单测：Root reset

### Case 1

```text
Discussion
  paragraph
  Methods
    Datasets
```

期望：

```text
Discussion
Methods
  Datasets
```

### Case 2

```text
Discussion of methods
```

不能误判为顶级 Methods。

### Case 3

```text
3. Methods
```

应识别为 major heading。

---

# 11. 单测：Sibling repair

### 编号案例

```text
2.1 Pre-processing
2.2 Baseline models
```

如果错误 tree 为：

```text
Pre-processing
└── Baseline models
```

应修成 siblings。

### 无编号高置信案例

两个相邻 heading：
- 原 Marker depth 相同
- 各自有独立正文
- 当前却错误父子

允许 promotion。

### 真实父子案例

```text
Methods
└── Pre-processing
```

不得误修。

---

# 12. 单测：Float reassignment

### Table

```text
Section A
Paragraph: "Results are shown in Table 3."

Section B
Table 3 ...
```

期望：

```text
Table 3 → Section A
```

### Figure

```text
Section A
Paragraph: "... illustrated in Fig. 4."

Section B
Figure 4 ...
```

期望：

```text
Figure 4 → Section A
```

### 多引用

同一 Fig.4 多次出现：
- 优先首次定义性引用
- 不确定时保留原归属 + warning

### 无引用

Figure 5 没正文引用：

```text
不得自动移动
```

---

# 13. 多论文真实 QA

不得只验证当前目标论文。

至少 5 篇不同结构论文，统计：

```text
root reset count
sibling repair count
float reassignment count
修复前后 sectionPath diff
误移动检查
```

当前目标论文只作为 regression case，期望：

```text
Methods 不再继承 Discussion
Pre-processing / Baseline models 不再错误父子
Scientific contributions / Clinical relevance 同级
Table 3 归 Unified multi-label classification
Fig. 4 归 Baseline models
```

但代码中禁止出现这些具体字符串和编号的特判。

---

# 14. Agent 执行步骤

## 第一步：只读

先检查：

```text
SectionHierarchyNormalizer
Section
AcademicDocument
FigureBlock
TableBlock
ParagraphBlock
ListBlock
PaperCleaner
CanonicalDocumentValidator
现有 QA tests
```

确认：

```text
Section parent/children 如何维护
path 如何重算
block 如何跨 Section 移动
order 是否全局稳定
```

## 第二步：先输出设计

修改代码前只输出：

```text
1. hierarchy 根因
2. float 归属根因
3. 准备新增/修改哪些类
4. root reset 规则
5. sibling repair 规则
6. reference index 规则
7. reassignment confidence 条件
8. audit
9. validator 指标
10. 单测和多论文回归方案
```

用户确认前：

**禁止修改业务代码。**

## 第三步：分阶段

```text
Phase 1
SectionHierarchyRepair
- major root reset
- numbered sibling repair
- level/path rebuild

Phase 2
unnumbered sibling conservative repair
audit
validator warning

Phase 3
FloatBlockSectionReassigner
- identifier parser
- reference index
- high-confidence reassignment

Phase 4
multi-paper regression QA
chunk sample QA
```

---

# 15. 停止条件

满足：

```text
顶级章节不再明显错误嵌套
高置信 sibling 不再错误父子
Figure/Table 高置信引用归属一致
无明显误移动
SectionAwareChunker crossSection 仍为 0
provenance 不丢
```

就停止继续扩规则。

---

# 16. 明确禁止

禁止：

```text
if title == "Clinical relevance"
if table == "Table 3"
if figure == "Fig. 4"

依赖页码 6 / 13
依赖 Scientific Reports 模板
依赖固定字体
依赖 LLM 判断 parent
```

任何规则都必须能回答：

> 这一类错误为什么在不同学术 PDF 中普遍存在？为什么当前规则能泛化？

---

# 17. 最终原则

目标不是：

> 为某篇论文修出漂亮目录树。

而是：

> 修复学术 PDF 中可稳定识别、可重复验证的结构性错误，避免 CanonicalDocument 给 embedding 注入错误的章节语义。

优先级：

```text
正确归属
>
保守不修
>
错误重排
```
