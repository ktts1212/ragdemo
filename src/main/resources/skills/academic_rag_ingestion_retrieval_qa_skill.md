# Skill：Academic RAG 检索链路接入与 Retrieval QA

## 1. 角色与目标

你是一个负责 Java 学术文献 RAG 项目“最后一公里接入”的工程 Agent。

当前系统已经存在两条能力链：

### 旧通用链路
```text
txt / md / 通用 PDF
→ LangChain4j parser
→ TextSplitterService
→ Embedding
→ pgvector
→ Retrieval / Chat
```

### 新学术 PDF 链路
```text
PDF
→ Marker
→ MarkerDocumentParser
→ ReadingOrderRepair
→ PaperCleaner
→ Canonical AcademicDocument
→ SectionAwareChunker
→ AcademicChunk[]
```

当前目标不是重写检索系统，而是：

> 把 AcademicChunk 稳定接入现有 Embedding + pgvector + Chat/Retrieval 链路，并建立可客观评估 section-aware 分块收益的 Retrieval QA 基线。

---

## 2. 必须坚持的原则

```text
Academic domain model 与数据库 persistence model 解耦
展示文本 content 与 embeddingText 分离
同一套 embedding 服务用于入库和查询
学术 PDF 与通用文件先并行分流，不立即强行统一
hard-invalid canonical 不入库，warning-only canonical 可入库
先做 retrieval QA，再决定 reference 过滤 / overlap / ANN 参数
```

禁止：

```text
把 DB BIGSERIAL id 强塞进 AcademicChunk.documentId 以解决 FK
让 ingestion 与 query 使用不同 embedding 模型/配置
valid=false 仍无条件入库
PDF Marker 失败后静默回退为普通 PDF 并标记 indexed
只比较最终 LLM 回答，不测 retrieval
只用单篇论文做 QA
```

---

## 3. 现状确认

Agent 必须先只读以下区域：

```text
IngestionServiceImpl
DocumentServiceImpl
DocumentParseService
TextSplitterService
CanonicalDocumentPipeline
SectionAwareChunker
AcademicChunk

EmbeddingModel bean
OpenAiClient
RagProperties.Embedding

DocumentChunk / mapper / mapper.xml
sql/init.sql
documents / document_chunks / ingestion_tasks

ChatServiceImpl
query-rewrite / system / user / summary / state prompts
现有测试
```

输出当前实际调用图，禁止仅根据 README 推断。

---

## 4. Phase 0：前置验证

### 4.1 Embedding 服务验证

验证当前实际模型：

```text
baseUrl
model
dimension
```

至少测试：

```text
输入 1 条文本
→ 返回 embedding
→ dimension == 配置 dimension
→ 数值非空、无 NaN/Inf
```

再测试批量：

```text
3~5 条文本
→ 返回数量一致
→ 每条 dimension 一致
```

### 4.2 统一 embedding 调用路径

当前若同时存在：

```text
OpenAiClient.embed()
LangChain4j EmbeddingModel
```

先确认各自实际使用位置。

目标：

> 生产 ingestion 与 query retrieval 必须最终通过同一个逻辑 EmbeddingGateway / EmbeddingService，或至少共享完全相同的 baseUrl/model/dimension/normalization 配置。

建议接口：

```java
EmbeddingGateway {
    float[] embedQuery(String text);
    List<float[]> embedDocuments(List<String> texts);
}
```

即使当前模型不区分 query/document 模式，也保留接口语义区分，方便未来替换模型。

### 4.3 README / 配置一致性

README、application.yml、SQL vector dimension 必须一致。

若当前实际为：

```text
bge-m3
1024
```

则旧的 1536 / text-embedding-3-small 描述必须修正。

---

## 5. 关键决策一：Domain documentId 与 DB document_id 解耦

### 5.1 不建议

不要：

```text
AcademicChunk.documentId = String.valueOf(documents.id)
```

仅为了满足：

```text
document_chunks.document_id BIGINT FK
```

原因：

```text
AcademicChunk.documentId 是领域/来源标识
documents.id 是 persistence surrogate key
二者语义不同
```

### 5.2 推荐

新增 persistence insert DTO：

```java
DocumentChunkInsertParam {
    Long dbDocumentId;
    Long kbId;

    String chunkKey;
    ChunkType chunkType;

    String content;
    String embeddingText;
    float[] embedding;

    ...
}
```

映射关系：

```text
AcademicChunk.documentId
→ 保持 Canonical / 离线 QA 的稳定逻辑 ID

documents.id
→ dbDocumentId
→ document_chunks.document_id
```

`chunkKey` 使用 `AcademicChunk.chunkId` 或稳定局部 key。

---

## 6. 关键决策二：document_chunks schema

采用“高频过滤字段专列 + 长尾 metadata JSONB”的混合方案。

推荐专列：

```text
chunk_key
chunk_type
section_id
section_title
section_path
section_level
embedding_text
content_hash
```

已有：

```text
document_id
kb_id
content
embedding
page_number
token_count
chunk_index
```

建议补：

```text
end_page
```

因为学术 chunk 可以跨页。

以下字段优先放 JSONB：

```text
blockTypes
sourceBlockIds
overlapBlockIds
referenceIds
charCount
oversized
undersized
```

如果后续需要对 `sourceBlockIds` 高频 SQL 查询，再升为专列。

### 6.1 section_path

若计划支持：

```text
按章节过滤
章节 facet
section-aware rerank
```

使用 `TEXT[] + GIN` 合理。

如果短期不会按 sectionPath 做 SQL 过滤，可以先存 TEXT/JSONB，避免过早索引设计。

### 6.2 chunk_key

建立：

```text
UNIQUE(document_id, chunk_key)
```

即使 v1 仍 delete + reinsert，也保留约束。

---

## 7. content 与 embeddingText

必须明确：

```text
content
= 展示 / 引用 / QA 的纯净 chunk 内容

embeddingText
= 真正送 embedding 的文本
= Document title + Section path + Content
```

Embedding 必须基于：

```text
AcademicChunk.embeddingText
```

数据库建议保存两者：

```text
content
embedding_text
```

用于：

```text
复现 embedding
debug
content vs embeddingText 消融
```

---

## 8. hard-invalid 与 warning-only canonical

不要采用“valid=false 仍入库”。

正确策略：

```text
validation.errors 非空 / valid=false
→ ingestion failed
→ 不写 indexed
→ 不进入正式检索空间
```

只有 warnings：

```text
valid=true + warnings
→ 允许入库
→ warnings 写 diagnostics / artifact metadata
```

---

## 9. 学术 PDF ingestion 分流

v1 推荐并行入口：

```text
PDF（学术模式）
→ CanonicalDocumentPipeline
→ SectionAwareChunker

txt / md / 其它通用文件
→ DocumentParseService
→ TextSplitterService
```

建议支持：

```text
AUTO
ACADEMIC
GENERIC
```

AUTO 可按扩展名/MIME 默认选择，但允许显式覆盖。

---

## 10. Marker 失败语义

Academic 模式下：

```text
Marker unavailable
Marker hard failure
Canonical hard-invalid
```

都应：

```text
documents.status = failed
ingestion_tasks = failed
记录明确 error code / message
```

禁止静默回退为 generic PDF 再标记 indexed。

---

## 11. 解析产物持久化

建议保存：

```text
original PDF
Marker raw JSON
Canonical JSON
Chunk manifest（可选）
```

原因：

```text
只保存 canonical
→ 可以重跑 chunker
但不能重跑 cleaner

保存 Marker raw
→ 可以离线重跑 Parser / Repair / Cleaner
→ 不必再次调用 Marker/GPU
```

建议 artifact metadata 至少记录：

```text
parserVersion
cleanerVersion
chunkerVersion
embeddingModel
embeddingDimension
configHash
createdAt
```

---

## 12. AcademicChunk → DB 映射器

新增明确映射层：

```text
AcademicChunk
→ DocumentChunkInsertParam
```

负责：

```text
dbDocumentId
kbId
chunkIndex
content
embeddingText
chunkType
section metadata
page range
quality metadata
provenance metadata
```

不要让 SectionAwareChunker 知道数据库结构。

---

## 13. Embedding 批处理

建议配置：

```text
batchSize = 32 / 64
```

支持：

```text
timeout
有限重试
指数退避
明确失败批次
```

只对：

```text
超时
5xx
可重试网络错误
```

重试。

不要对：

```text
dimension mismatch
4xx 参数错误
```

盲目重试。

任一批 embedding 失败：

```text
整篇 ingestion 不得 indexed
```

---

## 14. DB 写入事务与幂等

v1 可：

```text
deleteByDocumentId
→ insert all current chunks
```

但 DB 写入必须事务化：

```text
1. 先生成全部 chunk
2. 先完成全部 embedding
3. 开 DB transaction
4. delete old chunks
5. batch insert
6. update chunk_count / status
7. commit
```

Embedding 调用不要放在长 DB transaction 里。

---

## 15. REFERENCE chunk

v1：

```text
全部入库
chunk_type = REFERENCE
```

是否过滤由 Retrieval QA 决定。

后续实验：

```text
A 全部参与
B 普通 query 默认排除 REFERENCE
C REFERENCE 降权
```

---

## 16. ANN 索引策略

当前小规模语料不要把 IVFFlat 当 Retrieval QA 基准。

QA 第一阶段必须用：

```text
精确 cosine
```

数据库生产索引可以保留，但报告应分开：

```text
Exact retrieval
ANN retrieval
```

ANN 参数等真实规模后再调。

---

## 17. Retrieval QA：先评 retrieval，不先评 LLM

先建立离线：

```text
chunks
→ embedding cache
→ in-memory exact cosine
→ ranking
```

不依赖：

```text
pgvector
LLM
ChatService
```

用于隔离比较：

```text
chunking strategy
embeddingText strategy
```

---

## 18. Gold set 设计

至少 5 篇真实论文。

建议：

```text
每篇 10~20 题
总计 50~100
```

字段：

```text
id
paperId
question
questionType

expectedSourceBlockIds[]
expectedSectionPath[]
expectedPages[]
expectedEvidenceText（建议）

answerable
notes
```

questionType 至少：

```text
TEXT_FACT
METHOD
NUMERIC
COMPARISON
TABLE
FIGURE
LIST
REFERENCE
NO_ANSWER
```

### Gold 判定优先级

```text
1. sourceBlockIds overlap
2. expected Section
3. expected Page
4. expectedEvidenceText（用于旧 splitter 公平比较）
```

注意旧 TextSplitterService 没有 sourceBlockIds，因此 A/B 公平对照必须准备共同口径，如 evidence text / page。

---

## 19. Retrieval 指标

至少：

```text
Recall@1
Recall@3
Recall@5
Recall@10
MRR
HitRate@k
```

可选：

```text
nDCG@k
```

分类型：

```text
TEXT
TABLE
FIGURE
LIST
REFERENCE
NUMERIC
```

---

## 20. no-answer 测试

准备：

```text
answerable=false
```

离线 retrieval 阶段测：

```text
top1 score distribution
false-positive retrieval rate
minScore threshold
```

不要把 retrieval threshold 与 LLM 拒答混为一层。

---

## 21. A/B 对照实验

必须比较：

### A：旧分块

```text
TextSplitterService
800 / 120 chars
```

### B：新分块

```text
SectionAwareChunker
embeddingText
```

统一：

```text
论文集合
gold set
embedding model
topK
cosine metric
```

---

## 22. 必须做的消融

至少：

```text
B1 SectionAwareChunker + content
B2 SectionAwareChunker + embeddingText
```

回答：

> Section title/path 前缀到底有没有带来检索收益？

可选再做：

```text
overlap=false vs true
```

但不要先于 B1/B2。

---

## 23. Token 参数标定

当前 `ceil(chars/4)` 只是估算器。

接入实际 bge-m3 后：

```text
用真实 tokenizer
重新统计 p50 / p90 / max / oversized / undersized
```

最终 targetTokens/maxTokens/overlap 的调整应由 Retrieval QA 结果驱动。

---

## 24. pgvector QA

离线 exact QA 通过后再做：

```text
同一批 query
→ pgvector
→ 与 in-memory exact ranking 对比
```

验证：

```text
mapper SQL
cosine score
kbId filter
document status filter
chunk_type filter
minScore
```

再单独测 ANN 近似误差。

---

## 25. Chat 端到端 QA

最后进入：

```text
上传
→ ingestion
→ indexed
→ query rewrite
→ retrieval
→ context build
→ LLM answer
→ source reference
```

至少测试：

```text
普通事实题
表格数值题
Figure 问题
跨页证据
多轮指代
no-answer
REFERENCE 查询
```

Retrieval 指标与生成层指标分开报告。

---

## 26. 建议实施阶段

### Phase 0
```text
Embedding smoke
统一 embedding gateway
README/config/sql 一致
migration 策略确认
```

### Phase 1
```text
schema migration
AcademicChunk → InsertParam
embeddingText embedding
batch embedding
batch insert
transaction
幂等
```

### Phase 2
```text
PDF academic route
CanonicalDocumentPipeline
SectionAwareChunker
Marker raw + canonical artifact persistence
MinIO Figure storage
hard-invalid fail
generic route zero regression
```

### Phase 3
```text
gold set
embedding cache
exact cosine
A/B
content vs embeddingText
```

Phase 3 可与 Phase 1/2 并行。

### Phase 4
```text
pgvector exact QA
ANN comparison
filter/minScore tests
```

### Phase 5
```text
Chat E2E QA
```

---

## 27. 必须新增测试

建议：

```text
EmbeddingSmokeTest
AcademicChunkPersistenceMapperTest
AcademicChunkMappingTest
AcademicIngestionIntegrationTest
AcademicIngestionIdempotencyTest
AcademicIngestionFailureTest

OfflineRetrievalQaTest
PgvectorRetrievalIntegrationTest
ChatRetrievalRegressionTest
```

外部服务依赖测试使用：

```text
profile / tag / assumption
```

纯 mapping / metric / chunk QA 保持离线可跑。

---

## 28. Agent 修改代码前必须输出

先只读，然后输出：

```text
1. 当前生产 embedding 实际调用路径
2. 当前 DB schema 与 mapper 真实字段
3. AcademicChunk → persistence mapping
4. domain documentId 与 dbDocumentId 解耦方案
5. schema migration
6. ingestion route
7. failure semantics
8. artifact persistence
9. batch/transaction design
10. Retrieval QA gold schema
11. A/B + ablation design
12. 需要新增/修改的文件
13. 风险
```

用户确认前：

**禁止修改业务代码。**

---

## 29. 最终验收标准

### 入库

```text
学术 PDF → indexed
chunk_count 与 SectionAwareChunker 输出一致
content / embeddingText 正确
document_id FK 正确
chunk_key 唯一
重复 ingestion 不翻倍
失败不留下 indexed 半成品
```

### Metadata

```text
chunkType
sectionPath
page range
provenance
contentHash
```

可回溯。

### Retrieval

得到可复现报告：

```text
旧 TextSplitter vs SectionAwareChunker
content vs embeddingText
Recall@k
MRR
分类别 recall
no-answer threshold
```

### 回归

```text
txt/md 通用链路不受影响
Cleaner / Chunker 现有测试保持全绿
```

---

## 30. 本阶段不要提前决定

以下由 QA 数据决定：

```text
REFERENCE 默认过滤还是降权
overlap 是否开启
targetTokens 是否调整
minScore 最佳阈值
IVFFlat/HNSW/Exact 的选择与参数
```

---

## 31. 最终原则

当前真正要验证的问题不是：

> “能不能把 AcademicChunk 塞进 pgvector？”

而是：

> “SectionAwareChunker + section-aware embeddingText 是否在真实学术问题上，比旧字符切分产生更高质量、可解释、可回归的检索结果？”

工程接入和 Retrieval QA 必须一起推进。
