# Skill：SectionAwareChunker 设计与实现规划

## 1. 角色

你是一个负责 Java 学术文献 RAG 项目中 **SectionAwareChunker** 设计与实现规划的工程 Agent。

当前上游流水线已经完成：

```text
PDF
→ Marker API
→ Marker raw JSON
→ MarkerDocumentParser
→ ReadingOrderRepair
→ PaperCleaner
→ Canonical AcademicDocument
→ CanonicalDocumentValidator
```

当前目标：

```text
Canonical AcademicDocument
→ SectionAwareChunker
→ Chunk QA
→ Embedding
→ pgvector
→ Retrieval QA
```

本 Skill 只负责：

> 设计一个尊重 Section 结构和 Canonical BlockType 的分块器。

本阶段不要直接处理 embedding、pgvector、reranker、query rewrite 等后续模块。

---

# 2. 当前 CanonicalDocument 结构

当前根对象：

```text
AcademicDocument
├── documentId
├── metadata
├── abstractText
├── sections[]
├── references[]
└── parserInfo
```

Section：

```text
Section
├── id
├── sourceBlockId
├── title
├── level
├── path
├── order
├── blocks[]
└── children[]
```

Canonical DocumentBlock 主要类型：

```text
PARAGRAPH
LIST
FIGURE
TABLE
FORMULA
FOOTNOTE
CAPTION
NOISE
```

说明：

- `PARAGRAPH`：普通自然段；跨页连续段可能已经由 ReadingOrderRepair 合并
- `LIST`：由 Marker ListGroup / ListItem 归一而来
- `FIGURE`：Figure/Picture/Diagram + Caption 通常已合并
- `TABLE`：Table + Caption 通常已合并
- `FORMULA`：公式
- `FOOTNOTE`：脚注
- `CAPTION`：正常情况下较少，多为未能高置信绑定的 orphan caption
- `NOISE`：理论上 Cleaner 后应极少或不存在

References 已经从正文中抽取到：

```text
references[]
```

不要再把 Reference 当正文 Section 处理。

---

# 3. 核心设计原则

必须坚持：

```text
Section 决定语义边界
BlockType 决定块内处理策略
Paragraph 决定自然文本边界
Token 预算决定何时切分
```

总体优先级：

```text
1. 不跨 Section 乱切
2. 不拆自然段
3. 不拆 Figure/Table/短 List
4. 尽量达到合理 token 长度
5. 保留完整 provenance
```

禁止：

```text
纯固定长度切块
纯字符滑窗
先把整篇论文 flatten 成 string 再切
忽略 Section.path
把 Figure/Table 当普通 Paragraph
把 references[] 混入正文 Section chunk
```

---

# 4. Chunk 输出模型设计

建议新增：

```java
AcademicChunk
```

字段建议：

```text
chunkId
documentId
chunkType
sectionId
sectionTitle
sectionPath
sectionLevel
content
blockTypes
sourceBlockIds
startPage
endPage
tokenCount
charCount
order
```

可选字段：

```text
titleContext
metadataContext
referenceIds
isOversized
isMergedSmallChunk
```

---

# 5. ChunkType 设计

不要只保存原始 BlockType。

建议设计高层 chunk 类型：

```text
TEXT
LIST
FIGURE
TABLE
FORMULA
FOOTNOTE
MIXED
REFERENCE
ABSTRACT
```

### TEXT
由连续 `PARAGRAPH` 组成。

### LIST
主要为一个 LIST，或 LIST + 前置说明 Paragraph。

### FIGURE

```text
section path
+ figure caption
+ descriptionText
```

### TABLE

```text
section path
+ table caption
+ table content
```

### FORMULA
公式本身，必要时附带邻近正文。

### MIXED
少量异构 block，但仍属于同一语义片段，例如 `PARAGRAPH + LIST`。

### REFERENCE
专门用于 references[]。

### ABSTRACT
abstractText 单独形成高价值 chunk。

---

# 6. 第一版 Token 参数建议

第一版不要过度调参。

建议：

```text
targetTokens = 700
maxTokens = 1000
minTokens = 180
overlapTokens ≈ 100
```

可接受范围：

```text
targetTokens: 600~800
maxTokens:    900~1200
minTokens:    150~200
```

原则：

```text
maxTokens 是硬上限附近
targetTokens 是优先目标
minTokens 只用于判断是否需要和邻块合并
```

不要为了达到 targetTokens 强行跨 Section。

---

# 7. Section 遍历策略

必须按文档自然顺序遍历 Section tree。

推荐：

```text
DFS / preorder
```

即：

```text
当前 Section
→ 当前 Section.blocks
→ children 按 order 递归
```

但要注意：

> chunk 的 sectionPath 永远使用当前 block 实际所属 Section 的 path。

不要把父 Section 所有 children 先 flatten 成同一个 block list。

---

# 8. Section 级分块规则

## 8.1 每个 Section 独立处理

默认：

```text
Section A 的正文
不和
Section B 的正文
合并
```

即使两个 Section 都很短，也不要默认合并。

## 8.2 极短 Section

如果 Section：

```text
tokenCount < minTokens
```

第一版建议：

- 有明确标题：保留独立 chunk
- 无标题容器且和父 Section 强关联：可并入父 Section 最后一个 TEXT chunk
- 只是过渡句：可和同 Section 后续 block 合并

原则：

> 宁可有少量短 chunk，也不要跨 Section 错合并。

---

# 9. PARAGRAPH 处理规则

## 9.1 默认策略

同一 Section 内连续 Paragraph 按 token 累积：

```text
buffer = P1

如果 buffer + P2 <= maxTokens:
    append P2
否则:
    flush buffer
    start P2
```

## 9.2 不要拆自然段

若单个 Paragraph：

```text
tokens <= maxTokens
```

必须完整保留。

## 9.3 超长 Paragraph

只有：

```text
单个 Paragraph > maxTokens
```

才允许二次切分。

优先级：

```text
句子边界
→ 标点边界
→ token 硬切（最后兜底）
```

## 9.4 跨页 Paragraph

若 ReadingOrderRepair 已经合并出：

```text
sourceBlockIds = [pageA/Text/x, pageB/Text/y]
```

则必须视为一个完整自然段。

禁止再按页拆开。

---

# 10. LIST 处理规则

## 10.1 普通 List

如果整个 LIST <= maxTokens：

```text
整体保留
```

短前置 Paragraph，例如：

```text
The main contributions are:
```

可以与 LIST 合并为一个 `LIST` 或 `MIXED` chunk。

## 10.2 超长 List

如果 LIST > maxTokens：

```text
按 item 分批
```

每批接近 targetTokens。

禁止拆单个 item，除非该 item 自身超过 maxTokens。

---

# 11. FIGURE 处理规则

Canonical Figure 通常包含：

```text
imageKey
caption
descriptionText
sourceBlockIds
```

第一版建议 Figure 独立 chunk：

```text
Document Title
Section Path
Figure Caption
DescriptionText（如果有）
```

## 11.1 空 caption Figure

如果：

```text
caption 为空
descriptionText 为空
```

不要生成只有 imageKey 的文本向量 chunk。

可以保留结构 metadata，但跳过文本 embedding。

## 11.2 orphan CAPTION

如果仍存在独立 CAPTION：

- 不丢弃
- 可作为 TEXT/MIXED chunk
- 不要在 Chunker 里重新实现复杂 Figure 绑定

图文绑定属于 Cleaner。

---

# 12. TABLE 处理规则

Table 是高价值检索对象。

建议 embedding 文本：

```text
Document Title
Section Path
Table Caption
Table Content
```

优先使用：

```text
Table.content
```

而不是原始 HTML。

HTML 可保留为 metadata。

## 12.1 普通 Table

caption + content <= maxTokens：

```text
整个 TABLE 一个 chunk
```

## 12.2 超长 Table

不要字符硬切。

推荐：

```text
caption + header + row group
```

例如：

```text
Chunk 1 = caption + header + row1~rowN
Chunk 2 = caption + header + rowN+1~rowM
```

每个 table chunk 都重复 caption 和 header。

---

# 13. FORMULA 处理规则

第一版保守：

- 短 Formula 可与同 Section 前后短 Paragraph 合并
- 独立且有意义的 Formula 可单独 chunk
- 不因为 token 少就跨 Section 合并

---

# 14. FOOTNOTE 处理规则

普通 Footnote 检索价值较低。

建议：

```text
短 Footnote → 并入同 Section 前一个 TEXT chunk
长 Footnote → 单独 FOOTNOTE chunk
```

保留 chunkType 方便后续降权。

---

# 15. NOISE 处理规则

如果 Cleaner 后仍出现 NOISE：

```text
不进入 embedding chunk
```

记录 QA warning：

```text
NOISE_BLOCK_REACHED_CHUNKER
```

Chunker 不负责再次清洗 Noise。

---

# 16. Section 标题上下文

每个正文 chunk 的 embeddingText 建议附带：

```text
Document: <paper title>
Section: Results > Comparison ... > PTB-XL ...

Content:
...
```

这样 embedding 同时获得局部内容和章节语义。

不要只 embedding 裸正文。

---

# 17. embeddingText 与 displayText

建议区分：

```text
embeddingText
displayText
```

### embeddingText
包含 document title、sectionPath、正文、caption/table text。

### displayText
尽量保留自然展示文本。

如果第一版不想改模型，也可以先只有 `content`，但设计上预留两者分离。

---

# 18. Overlap 设计

禁止纯 token 滑窗优先。

推荐 block-level overlap：

```text
Chunk1 = P1 + P2 + P3
Chunk2 = P3 + P4 + P5
```

即重复最后一个完整 Paragraph。

只有最后 Paragraph 本身非常长时，才考虑 sentence-level overlap。

注意：

> overlap 只能发生在同一 Section 内。

---

# 19. References 分块策略

`references[]` 不走正文 SectionAwareChunker 逻辑。

可以在同一个组件中单独分支，或后续拆成 `ReferenceChunker`。

第一版：

```text
1 Reference → 1 REFERENCE chunk
```

内容：

```text
rawText
doi
year
```

不要把几十条 Reference 合成一个大 chunk。

---

# 20. Abstract 分块

`abstractText` 建议独立生成一个：

```text
ABSTRACT chunk
```

内容：

```text
Document Title
Abstract
```

Abstract 是高价值检索入口。

---

# 21. Metadata

不要把完整 metadata JSON 当成 chunk。

第一版不必单独生成 metadata chunk。

title / keywords / authors 等可作为 chunk metadata 保存。

Abstract chunk 已能覆盖大量文档级语义。

---

# 22. Chunk 顺序

建议：

```text
1. ABSTRACT
2. sections[] 按自然顺序 DFS
3. references[]
```

每个 chunk 生成稳定的全局 `order`。

---

# 23. Chunk ID

必须稳定可重复。

建议：

```text
documentId + sectionId + localChunkIndex
```

不要随机 UUID 作为唯一来源。

这样重新切块时方便 diff 和 QA。

---

# 24. Provenance

每个 chunk 必须保留：

```text
sourceBlockIds
pageRange
sectionId
sectionPath
```

多个 block 合并时：

```text
sourceBlockIds = union
startPage = min
endPage = max
```

这是后续：

```text
引用
定位原文
debug
retrieval QA
```

的基础。

---

# 25. Chunk QA 指标

实现 Chunker 后，不要立刻进入 embedding。

先做 QA。

建议统计：

```text
chunkCount
avgTokens
p50Tokens
p90Tokens
maxTokens
minTokens

oversizedChunkCount
undersizedChunkCount

crossSectionChunkCount
splitParagraphCount

figureChunkCount
tableChunkCount
listChunkCount
referenceChunkCount
abstractChunkCount

emptyChunkCount
noiseChunkCount
```

---

# 26. QA 验收

理想：

```text
crossSectionChunkCount = 0
emptyChunkCount = 0
noiseChunkCount = 0
splitParagraphCount ≈ 0
```

并确保：

```text
Figure caption 不丢
Table caption/content 不丢
跨页 repaired paragraph 不再次被按页拆开
references[] 不混入正文 chunk
```

---

# 27. 当前真实论文 QA

使用：

```text
Toward robust automated cardiovascular arrhythmia detection
using self-supervised learning and 1-dimensional vision transformers
```

重点抽查：

### Case 1：跨页 Paragraph

`/page/3/Text/11 + /page/4/Text/1`

必须保持为一个自然段逻辑单元。

### Case 2：main contributions LIST

尽量整体形成一个 LIST/MIXED chunk。

### Case 3：Figure 1

caption 必须进入 FIGURE chunk。

### Case 4：Table 2

caption + header + content 不得切成无上下文碎片。

### Case 5：Discussion / child sections

chunk 不允许跨 subsection。

### Case 6：References

必须来自 `references[]`，不与正文 chunk 混合。

---

# 28. 单元测试

至少设计：

```text
SectionAwareChunkerTest
ParagraphChunkingTest
ListChunkingTest
FigureChunkingTest
TableChunkingTest
ReferenceChunkingTest
ChunkProvenanceTest
```

Paragraph：

```text
3 个短 paragraph → 可合并
超过 max → 分块
单个超长 paragraph → 句子级拆
跨页 repaired paragraph → 不按页拆
```

List：

```text
短 list → 整体
超长 list → 按 item 分组
```

Figure/Table：

```text
caption 保留
content 保留
sourceBlockIds 聚合
pageRange 正确
```

Section boundary：

```text
Section A 很短
Section B 很短
→ 仍不默认合并
```

---

# 29. Agent 执行规则

## 第一步：只读现有代码

先检查：

```text
AcademicDocument
Section
DocumentBlock
ParagraphBlock
ListBlock
FigureBlock
TableBlock
FormulaBlock
FootnoteBlock
Reference
已有 tokenizer/token utility
已有 chunk/domain 包
已有 splitter
```

## 第二步：先输出设计

在修改代码前先输出：

```text
1. 当前数据结构确认
2. Chunk model 设计
3. 各 BlockType 策略
4. Token 参数
5. Overlap 策略
6. References 策略
7. QA 指标
8. 需要新增/修改的 Java 文件
9. 测试计划
10. 风险
```

用户确认前：

**禁止修改业务代码。**

---

# 30. 推荐实现阶段

## Phase 1

```text
AcademicChunk model
Section traversal
PARAGRAPH chunking
provenance
```

## Phase 2

```text
LIST
FIGURE
TABLE
FORMULA
FOOTNOTE
```

## Phase 3

```text
ABSTRACT
REFERENCE
Chunk QA metrics
```

## Phase 4

```text
RealPaper Chunk QA
```

---

# 31. 当前阶段禁止做

```text
Embedding
pgvector
BM25
Hybrid Search
Reranker
Query Rewrite
LLM Chunk Summary
Semantic Chunking
Agentic Chunking
```

第一版必须 deterministic、可测试、可解释。

---

# 32. 最终验收标准

满足：

```text
Section 边界不被破坏
Paragraph 基本不被硬拆
跨页 repaired paragraph 保持完整
List 尽量整体
Figure caption 不丢
Table caption/content 不丢
Reference 独立
所有 chunk 可追溯
token 长度基本稳定
无空 chunk
无 Noise chunk
```

通过 Chunk QA 后，才进入：

```text
Embedding
→ pgvector
→ Retrieval QA
```

---

# 33. 最终原则

SectionAwareChunker 的目标不是：

> 把每个 chunk 都切成完全一样长。

而是：

> 在合理 token 预算内，最大程度保持论文原本的语义结构和 Canonical Block 结构。

优先级：

```text
语义完整性
>
结构完整性
>
长度均衡
```
