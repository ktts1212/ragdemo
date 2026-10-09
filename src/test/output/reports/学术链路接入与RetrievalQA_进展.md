# 学术链路接入与 Retrieval QA —— 现状与本次改动

> 依据 Skill：`src/main/resources/skills/academic_rag_ingestion_retrieval_qa_skill.md`（31 节 / Phase 0~5）
> 本文档对应 skill §28 要求的「只读勘察 + 设计输出」，并说明**在此基础上实际做了什么**。

---

## 一、当前项目情况（只读勘察结论）

### 1.1 存在两条能力链

```text
旧通用链路（已完整可用）
  txt/md/通用PDF → DocumentParseService(LangChain4j pdfbox/poi/tika) → TextSplitterService(800/120 字符)
  → EmbeddingModel.embedAll → document_chunks(pgvector) → ChatServiceImpl 检索

新学术 PDF 链路（已建 + 已 QA，但未接入）
  PDF → Marker → MarkerDocumentParser → ReadingOrderRepair → PaperCleaner
  → Canonical AcademicDocument → SectionAwareChunker → AcademicChunk[]（5 篇 598 chunks）
```

### 1.2 实际调用图（禁止按 README 推断；以下为代码实测）

```text
【写入】DocumentController → DocumentServiceImpl → MinIO + Kafka
        → KafkaConsumerService → IngestionServiceImpl.ingest()
        → DocumentParseService → TextSplitterService → EmbeddingModel(embedAll)
        → DocumentChunkMapper.insertChunkWithEmbedding（@Insert 注解）

【检索】ChatController → ChatServiceImpl.chat()
        → embeddingModel.embed(改写后问题) → toPgVectorString
        → DocumentChunkMapper.searchSimilarChunks（XML：<=> 余弦 + kbId + d.status='indexed'）
        → minScore 过滤 → buildContext → ChatModelFactory.chat → SourceReference

【Embedding bean】config/EmbeddingConfig#embeddingModel(RagProperties) ← 唯一 bean，
        写入侧与检索侧共用（这是好事，但缺一层显式契约）
【Embedding 配置】application.yml: bge-m3:latest @ http://localhost:11434/v1, dimension=1024
【SQL】document_chunks(embedding VECTOR(1024), metadata JSONB, page_number, token_count,
        start_offset, end_offset, UNIQUE(document_id, chunk_index)) + ivfflat(lists=100)
```

### 1.3 缺口（本次要解决的）

```text
① SectionAwareChunker 输出未入库（IngestionServiceImpl 仍用 TextSplitterService）
② CanonicalDocumentPipeline 未接入 ingestion（javadoc 明确 gate 未打开）
③ 检索侧 QA 完全空白（无 gold set / 无指标 / 无回归）
④ document_chunks 无学术元数据列；且 content 与 embeddingText 未分离
⑤ 无统一 EmbeddingGateway（OpenAiClient.embed 与 LangChain4j EmbeddingModel 并存）
```

### 1.4 ⚠️ 环境探测结果（决定本次可交付范围）

```text
❌ Ollama (localhost:11434)   不可达  → 无法跑真实 embedding
❌ PostgreSQL (localhost:15432) 不可达 → 无法应用 migration / 无法入库 / 无 pgvector QA
❌ Marker API (127.0.0.1:18001) 不可达 → 无法跑端到端
❌ docker 不可用                    → 以上三者均无法启动
```

→ 结论：**Phase 0（embedding smoke）/ Phase 1（落库）/ Phase 2（端到端）/ Phase 4（pgvector QA）/ Phase 5（Chat E2E）
在本环境无法完成也无法验证**。本次交付聚焦：**Phase 3（离线 Retrieval QA，不依赖 DB/LLM）+ Phase 1 的纯映射层 + Phase 0 的 gateway 抽象**——这三块可离线实现并用单测验证。

---

## 二、§28 要求的 13 项设计输出

| # | 项 | 结论 |
|---|---|---|
| 1 | 生产 embedding 实际调用路径 | 写入侧/检索侧**共用同一个 `EmbeddingModel` bean**，但调用点分散（`IngestionServiceImpl` / `ChatServiceImpl` / `OpenAiClient`）→ 本次抽出 `EmbeddingGateway` |
| 2 | DB schema 与 mapper 真实字段 | `document_chunks` 11 列；`DocumentChunkInsertParam` 10 字段；`insertChunkWithEmbedding` 为 @Insert 注解式 |
| 3 | AcademicChunk → persistence mapping | 新增 `AcademicChunkPersistenceMapper`（skill §12），22 字段 → 专列 + JSONB 长尾 |
| 4 | domain documentId 与 dbDocumentId 解耦 | **采纳 skill §5.1 的"不建议"**：不把 `String.valueOf(documents.id)` 塞进 `AcademicChunk.documentId`；改为映射层显式接收 `dbDocumentId`，兼容 `OpenAiEmbeddingModel` 的路径——写入 FK 用 `dbDocumentId`，领域 id 保持稳定（供离线 QA / 溯源） |
| 5 | schema migration | 新增 `sql/migration/V2__academic_chunk_columns.sql`（纯 ADD COLUMN，旧链路零影响）+ 同步 `init.sql` |
| 6 | ingestion route | 设计为并行入口 `AUTO / ACADEMIC / GENERIC`（skill §9）——**本次未落地**（需 DB 才能验证，见 §四） |
| 7 | failure semantics | hard-invalid → failed 不 indexed；Marker 不可用 → failed **不静默回退**（skill §8/§10）——**本次未落地**（同上） |
| 8 | artifact persistence | Marker raw + Canonical JSON 存 MinIO（skill §11）——**本次未落地**（同上） |
| 9 | batch / transaction design | embedding 分批（默认 32）+ 有限重试已实现；**DB 写入事务/批量 insert 未实现**（需 DB 验证） |
| 10 | Retrieval QA gold schema | 新增 `RetrievalGoldCase`（含 QuestionType 九类 + answerable + 4 类期望证据） |
| 11 | A/B + ablation design | 新增 `OfflineRetrievalQa`：A=旧字符切分 / B1=content / B2=embeddingText |
| 12 | 需要新增/修改的文件 | 见 §三 |
| 13 | 风险 | 见 §五 |

---

## 三、本次实际修改（在此基础上做了什么）

### 3.1 新增（主代码）

| 文件 | 作用 | 关键契约 |
|---|---|---|
| `embedding/EmbeddingGateway.java` | 统一 embedding 入口接口（query / documents 语义分离） | skill §4.2 |
| `embedding/LangChain4jEmbeddingGateway.java` | 实现：复用既有 `EmbeddingModel` bean；**分批**（`batch-size`，默认 32）+ **指数退避有限重试**（仅超时/5xx/网络；维度不一致/4xx 不重试） | skill §4.2 / §13 |
| `persistence/AcademicChunkPersistenceMapper.java` | `AcademicChunk` → `DocumentChunkInsertParam` | ①embedding **必须**由 `embeddingText` 现算（提供接收 gateway 的重载，从契约层保证）②写入 `document_id` 的是 `dbDocumentId`，**不是**领域 id |
| `retrieval/qa/RetrievalUnit.java` | 新旧链路**共用口径**的可检索单元 | skill §18 注 |
| `retrieval/qa/RetrievalGoldCase.java` | gold 用例 + `QuestionType`（9 类）+ `answerable` | skill §18 |
| `retrieval/qa/RetrievalMetrics.java` | Recall@1/3/5/10 · MRR · HitRate@5 · 按类型 recall@5；**判定按优先级取第一个双方都非空的信号** | skill §18/§19 |
| `retrieval/qa/ExactCosineRetriever.java` | 精确余弦 topK（**不用 ANN**） | skill §16 |
| `retrieval/qa/OfflineRetrievalQa.java` | A/B 语料构建 + 消融 + 运行。**A 组通过对全文建立 span→block/页码 映射，使旧字符切分链路也具备 blockId/页码证据**（否则 A/B 口径不对等） | skill §17/§21/§22 |

### 3.2 修改（既有文件）

| 文件 | 改动 | 是否破坏旧链路 |
|---|---|---|
| `config/RagProperties.java` | `Embedding` 增加 `batchSize=32` / `maxRetries=2` | 否（新增） |
| `application.yml` | 增加 `rag.embedding.batch-size` / `max-retries` | 否（新增） |
| `util/VectorUtils.java` | 新增 `toPgTextArray(List<String>)`（元素转义，空列表 → `{}`） | 否（新增） |
| `model/param/DocumentChunkInsertParam.java` | 新增 9 个学术字段（chunkKey/chunkType/section*/embeddingText/contentHash/endPage） | 否（旧链路留 null） |
| `mapper/DocumentChunkMapper.java` | `insertChunkWithEmbedding` 的 @Insert 扩展为 19 列（含学术列）；改用 text block 提升可读性 | 否（旧链路学术列为 NULL，同一条 SQL 服务两条链路） |
| `sql/init.sql` | `document_chunks` 增加 9 列 + 3 索引 | 否（`ADD COLUMN` 语义等价） |
| `sql/migration/V2__academic_chunk_columns.sql` | 新增增量迁移脚本 | 否（幂等 `IF NOT EXISTS`） |

### 3.3 新增（测试，全部离线可跑）

```text
persistence/AcademicChunkPersistenceMapperTest   4 例：解耦契约 / embeddingText→embedding / 空值拒绝 / text[] 转义
retrieval/qa/RetrievalMetricsTest                4 例：Recall+MRR 数值 / no-answer 排除 / 判定优先级 / 空输入
retrieval/qa/OfflineRetrievalQaTest              2 例：A/B + 消融冒烟（确定性词袋 embedding）/ 精确余弦排序
```

**验证**：`mvn test` → **230 tests / 0 failures / 0 errors / 13 skipped · BUILD SUCCESS**（本次前为 220）

---

## 四、未能完成的部分与解锁条件

| Skill 阶段 | 状态 | 阻塞原因 | 解锁条件 |
|---|---|---|---|
| Phase 0 embedding smoke | ❌ | Ollama 不可达 | 启动 Ollama（`bge-m3`）后跑 smoke |
| Phase 1 落库（migration 应用 / 批量 insert / 事务 / 幂等验证） | ⛔ 部分（纯映射层已完成） | PostgreSQL 不可达 | `docker compose up -d` + 应用 `V2` migration |
| Phase 2 学术 ingestion 分流 / artifact 持久化 / hard-invalid fail | ❌ | 需 Marker + DB | Marker 可达 + DB 可用 |
| Phase 3 离线 Retrieval QA | ✅ **机制已完成**（含 A/B + 消融） | 真实 embedding 需 Ollama | 出题（gold set，50~100 题）+ Ollama 可达即可出真实对照表 |
| Phase 4 pgvector exact QA / ANN 对比 | ❌ | DB 不可达 | DB 可用 |
| Phase 5 Chat E2E QA | ❌ | 需 DB + LLM | 全链路可用 |

**另外两项待你决策**（skill §28 要求确认后才动业务代码，故本次未做）：
```text
1. ingestion route 形态：AUTO/ACADEMIC/GENERIC 的显式覆盖入口放哪（上传参数 / 配置 / MIME 自动）
2. artifact 持久化的 MinIO key 约定（canonical.json / marker-raw.json 的命名与目录）
```

---

## 五、风险

| 风险 | 说明 | 现状 |
|---|---|---|
| 真实 A/B 尚不可跑 | 离线机制已验证，但用的是词袋假 embedding（无真实语义） | 待 Ollama 可达 |
| A 组小文档会同质 | 冒烟里 A-legacy `units=1`（整篇 < 800 字符 → 1 个 chunk，命中率天然 1.0） | 真实语料（每篇 8k~15k 字符）下不会出现 |
| migration 未经 DB 验证 | 纯 DDL 且 `IF NOT EXISTS`，但未实际执行 | 待 DB 可用后 `psql -f` 验证 |
| `CAST(#{sectionPath} AS text[])` 未经 DB 验证 | 传 pg 数组字面量字符串再 CAST | 待 DB 验证；已有纯单测覆盖字面量生成 |
| `ivfflat lists=100` | 小数据量下 ANN 召回可能劣于精确 | skill §16 已要求 QA 阶段用精确检索（已遵循） |
| pgvector 维度锁死 1024 | 换模型需改表 | 与 `EmbeddingGateway` 配置同源，换模型时需同步 |

---

## 六、skill 逐条对照摘要

```text
✅ 满足：§4.2 统一 gateway（接口+实现）· §5 domain/db id 解耦 · §6 schema 混合方案 · §7 content/embeddingText 分离
        · §12 映射层 · §13 embedding 批处理+有限重试 · §16 精确 cosine · §17 离线 QA · §18 gold 判定优先级
        · §19 recall/MRR · §21 A/B · §22 content-vs-embeddingText 消融 · §27 离线部分测试
⛔ 未做：§8 hard-invalid 落库语义 · §9 分流 · §10 Marker 失败语义 · §11 artifact 持久化 · §14 DB 事务/批量
        · §15 REFERENCE（v1 全量入库+标记，已由 chunkType 支撑）· §20 no-answer 阈值 · §23 tokenizer 标定
        · §24 pgvector QA · §25 Chat E2E（均因外部服务不可达）
```
