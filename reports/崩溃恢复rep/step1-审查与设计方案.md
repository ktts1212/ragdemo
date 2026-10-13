# 崩溃恢复改造 · Step 1 审查与设计方案

> 任务来源：`skills/崩溃恢复/step1.md`（Day 1 / Step 1）
> 约束遵守：**本次未修改/创建/删除任何项目代码**（唯一产出即本报告）
> 审查基准：分支 `v2`，实际磁盘代码 + 真实依赖 jar（`javap` 核实）+ 一次已跑通的端到端实测
> 关联文档：`reports/{记忆系统设计,检索与生成链路设计,入库链路设计,清洗与分块链路设计,数据库表结构说明,端到端测试报告}.md`

---

## 0. 结论速览

| 问题 | 结论 |
|---|---|
| LangGraph4j 是否已引入 | ✅ **已引入**：`langgraph4j-bom:1.9.4` + `langgraph4j-core` + `langgraph4j-postgres-saver`（pom.xml:26-48），jar 已下载到本地仓库 |
| 是否 Java 17 兼容 | ✅ 字节码 major=**61**（Java 17） |
| 依赖冲突风险 | ✅ 实测 `dependency:tree` **BUILD SUCCESS**，slf4j/jackson/HikariCP 与 Boot 3.3.5 管理版本一致 |
| 7 个点名 API 是否真实存在 | ✅ **全部存在且签名已用 `javap` 核实**（§5） |
| 是否已具备崩溃恢复能力 | ❌ **完全没有**。当前是"Kafka 消费 + 内存态编排 + 无重试"，进程一崩任务即永久卡在 `running` |
| 现有中间产物够不够支撑恢复 | ⚠️ **够，但完全没被利用**：MinIO 已有 marker-raw/canonical/chunks 三个阶段的产物，但代码里没有任何"读回续跑"逻辑 |
| 最大的两个风险 | ① 无并发保护 ② GENERIC 链路**完全没有事务**（逐条 insert，中途崩溃留下半成品） |
| 最大的改造障碍 | **`src/test/java` 已 0 个文件**（230 个测试全丢）⇒ 崩溃恢复这种必须靠实验验证的改造，目前没有回归网 |

---

## 1. 依赖审查（step1 §1）

### 1.1 版本矩阵（证据：`pom.xml`）

| 项 | 版本 | 位置 |
|---|---|---|
| Java | **17** | `pom.xml:21` |
| Spring Boot | **3.3.5** | `pom.xml:10`（starter-parent） |
| LangChain4j | **0.36.2** | `pom.xml:22` |
| **LangGraph4j** | **1.9.4** | `pom.xml:23`（BOM import）+ `:42` core + `:47` postgres-saver |
| MyBatis-Plus | 3.5.9 | `pom.xml:75` |
| MinIO | 8.5.10 | `pom.xml:121` |
| jsoup | 1.17.2 | `pom.xml:128` |
| httpclient5 | **交由 Boot BOM 管理**（无显式版本） | `pom.xml:152-155`（已修版本冲突，见 `端到端测试报告.md` §3） |

### 1.2 LangGraph4j 传递依赖（实测 `mvn dependency:tree`）

```text
org.bsc.langgraph4j:langgraph4j-core:1.9.4
  ├── org.bsc.async:async-generator:5.1.0
  └── org.slf4j:slf4j-api:2.0.16
org.bsc.langgraph4j:langgraph4j-postgres-saver:1.9.4
  ├── org.slf4j:jul-to-slf4j:2.0.16
  └── com.zaxxer:HikariCP:5.1.0
jackson-{annotations,core,databind}:2.17.2
→ BUILD SUCCESS（无版本冲突）
```

**结论**：slf4j 2.0.16 / jackson 2.17.2 / HikariCP 5.1.0 **均与 Spring Boot 3.3.5 的依赖管理一致**，引入 LangGraph4j **未引入冲突**。字节码 `major version: 61` = Java 17 ✓。

### 1.3 数据源

```yaml
spring.datasource.url: jdbc:postgresql://localhost:15432/ragdemo   # application.yml:8
username: liyong / driver: org.postgresql.Driver
```
Boot 默认 HikariCP 连接池；MyBatis-Plus 走同一数据源。**PostgresSaverV2 可直接复用该 DataSource**（其 Builder 提供 `datasource(javax.sql.DataSource)`，见 §5）。

### 1.4 测试框架

`spring-boot-starter-test` 在 `pom.xml:143-147` ✓，但：

```
src/test/java/**          → 0 个 .java 文件
src/test/resources/marker → 0 个 fixture
```
⇒ **测试网缺失**（详见 `端到端测试报告.md` §5）。对崩溃恢复改造而言这是**第一优先级障碍**：恢复语义必须靠"杀进程 → 重启 → 校验"的实验来证明，而不能只靠静态推理。

### 1.5 发现的遗留问题（非本次引入）

| 项 | 说明 |
|---|---|
| `langchain4j-document-parser-apache-tika` 用 `1.11.8-beta19` | 与其余 langchain4j `0.36.2` **不同版本线**（`pom.xml:109`）。当前未致冲突，但属隐患 |
| `OpenAiClient` 是死代码 | 全仓无注入点（`检索与生成链路设计.md` §8#4） |

---

## 2. 当前真实项目结构与调用链（step1 §2）

### 2.1 上传链（同步）

```text
POST /api/documents/upload (file, knowledgeBaseId?=1, parseMode?)
 └─ DocumentServiceImpl.upload(file, kbId, requestedMode)      // DocumentServiceImpl.java
     1 validateFile()                .pdf/.docx/.txt/.md
     2 checkKnowledgeBaseExists()
     3 extractFileExt / generateStoredFileName(uuid) / calculateSha256()  ← 流式 SHA-256
     4 ★parseModeResolver.resolve(requested, fileName, contentType)       ← 三级决策
     5 ★documentsMapper.insert(document)   ← 先插，取 id；storage_path 先空串
     6 按 parseMode 算 key → minioService.upload(file, key)
          ACADEMIC: documents/{id}/versions/{v}/source/original{ext}
          GENERIC : knowledge-base/{kbId}/{yyyyMMdd}/{uuid}{ext}
     7 documentsMapper.updateStoragePath(id, key)
     8 ingestionTaskMapper.insert(task)     ← 生成 taskId（BIGSERIAL）
     9 kafkaProducerService.sendDocumentIngestionTask(
             new DocumentIngestionMessage(document.getId(), kbId, task.getId()))
```

### 2.2 消费与编排链（异步）—— **这是崩溃恢复要改造的核心**

```text
KafkaConsumerService.consume(DocumentIngestionMessage msg)     // topic: rag-document-ingestion
 └─ IngestionServiceImpl.ingest(msg.documentId(), msg.taskId())  // ★编排点 A
     1 statusUpdater.markTaskRunning(taskId)
     2 documentsMapper.selectById(documentId)          → null ⇒ task failed
     3 status == "indexed" ⇒ 幂等短路（markTaskSuccess）
     4 ★ParseMode.from(document.getParseMode()) ⇒ 分流
          ├ ACADEMIC → AcademicIngestionService.ingest(document, taskId)   // ★编排点 B
          └ 其它     → ingestGeneric(document, taskId)
     5 catch(Exception) → documents.status=failed + task failed
```

### 2.3 ACADEMIC 分支（未来要拆成图的节点）

```text
AcademicIngestionService.ingest(document, taskId)
 1 statusUpdater.updateDocumentStatus(id, "parsing", 20)
 2 minioService.download(storagePath).readAllBytes()            ← ★整份入内存
 3 canonicalDocumentPipeline.process(domainId=checksum, pdfBytes, fileName)
      ├ MarkerClient.parse()                    → Marker raw JSON（远端 GPU，实测 ~117s）
      ├ MarkerDocumentParser.parse()            → Parsed AcademicDocument
      ├ ReadingOrderRepair.repair()             → 跨页续接（audit）
      └ PaperCleaner.clean(document, cnt)       → 13 步清洗
 4 if (!result.valid()) throw ACADEMIC_INVALID_CANONICAL
 5 minioService.upload(marker-raw.json) / upload(canonical.json)   ← 阶段产物落盘
 6 statusUpdater.updateDocumentStatus(id, "chunking", 45)
   SectionAwareChunker.chunk(canonical) → chunks；空 ⇒ throw ACADEMIC_NO_CHUNKS
   minioService.upload(chunks.json manifest)
 7 statusUpdater.updateDocumentStatus(id, "embedding", 70)
   embeddingGateway.embedDocuments(chunks 的 embeddingText)      ← 分批(32)+重试；★事务外
 8 academicChunkPersistenceMapper.toInsertParam(chunk, dbDocId, kbId, vector)
 9 chunkPersistenceService.replaceChunks(id, params)             ← ★唯一 @Transactional
10 statusUpdater.markTaskSuccess(taskId)
```

### 2.4 GENERIC 分支

```text
 IngestionServiceImpl.ingestGeneric()
 parsing(20) → minio download → DocumentParseService.parse（LangChain4j pdfbox/poi/tika）
 chunking(45) → TextSplitterService.split（字符递归 800/120）→ updateChunkCount
 embedding(70) → embeddingModel.embedAll
 ★deleteByDocumentId → 逐条 insert（无事务！）→ status=indexed → markTaskSuccess
```

### 2.5 未来必须迁移到 StateGraph 的两个点

| 编排点 | 类.方法 | 为什么必须迁移 |
|---|---|---|
| **A** | `IngestionServiceImpl.ingest` | 它是"分流 + 失败处理"的中枢；分流天然是**条件边**，失败处理天然是**边的目标节点** |
| **B** | `AcademicIngestionService.ingest` | 10 步线性流程 → 每步一个节点，阶段边界即 checkpoint 点 |

其余组件（Parser/Repair/Cleaner/Validator/Chunker/Gateway/Mapper/Minio/StatusUpdater）**全部可直接复用**，不进图。

---

## 3. 任务状态、事务与幂等能力（step1 §3）

### 3.1 taskId 的生成与传递（真实链路）

```text
DocumentServiceImpl.upload:  IngestionTask task = new IngestionTask(); task.setStatus("pending");
                             ingestionTaskMapper.insert(task);        ← MyBatis-Plus 回填 BIGSERIAL id
                             ... new DocumentIngestionMessage(document.getId(), kbId, task.getId())
KafkaConsumerService.consume: ingestionService.ingest(message.documentId(), message.taskId())
```
⇒ **taskId = `ingestion_tasks.id`（DB 自增）**，经 Kafka 消息传递；消息**不含 parseMode**（consumer 从 `documents.parse_mode` 读）。

### 3.2 任务状态

| 表 | 状态集 | 写入方 |
|---|---|---|
| `documents.status` | `uploaded → parsing → chunking → embedding → indexed` / `failed` | `IngestionStatusUpdater.updateDocumentStatus`（**唯一入口**） |
| `ingestion_tasks.status` | `pending → running → success` / `failed` | `IngestionStatusUpdater.markTask*` |

进度：`documents.progress` 有 20/45/70/100；`ingestion_tasks.progress` 与 `current_step` **运行中不更新**（列就绪未用）。

### 3.3 并发保护：**没有**

- 无分布式锁、无 `documents.status` 的 CAS 式守卫（唯一守卫是 `status == "indexed"` 短路）
- 无"同一 documentId 只允许一个 running task"约束
- ⇒ 同一文档重复投递/并发消费 ⇒ 两条链路互相 `delete + insert`，靠 `UNIQUE(document_id, chunk_index)` 兜底但会抛错

### 3.4 事务边界（只有一处）

| 路径 | 事务 | 证据 |
|---|---|---|
| **ACADEMIC 落库** | ✅ `@Transactional`（delete→insert→chunk_count→indexed） | `ChunkPersistenceService.replaceChunks` |
| ACADEMIC embedding | ✅ **刻意在事务外** | `AcademicIngestionService` 第 7 步先算完再进第 9 步 |
| **GENERIC 落库** | ❌ **完全无事务**（逐条 insert） | `IngestionServiceImpl.saveChunksAndMarkIndexed` |
| 上传（insert doc + create task + send kafka） | ✅ `@Transactional` | `DocumentServiceImpl.upload` 上的 `@Transactional`（⚠️ 但 MinIO 上传不可回滚） |

### 3.5 幂等能力（5 条，均有代码依据）

| # | 机制 | 位置 |
|---|---|---|
| 1 | `status == "indexed"` ⇒ 直接短路 | `IngestionServiceImpl.ingest` |
| 2 | `deleteByDocumentId` + 重插 | `ChunkPersistenceService` / `saveChunksAndMarkIndexed` |
| 3 | `UNIQUE(document_id, chunk_index)` | `sql/init.sql:93` |
| 4 | `uk_document_chunks_chunk_key`（内容寻址，重传内容相同 ⇒ key 相同） | `sql/init.sql:101` |
| 5 | MinIO artifact 同 version 覆盖写 | `ArtifactKeyBuilder` |

### 3.6 崩溃后**无法恢复**的点（核心风险清单）

| # | 崩溃场景 | 当前后果 | 证据 |
|---|---|---|---|
| 1 | **Kafka offset 已提交后进程崩溃** | 消息不会重投（**无消费重试 / 无 DLQ**）⇒ 任务永久 `running`、文档永久停在 `parsing/chunking/embedding` | `KafkaConsumerService` 无 errorHandler；`application.yml:24-29` 无 retry 配置 |
| 2 | Marker 解析中途崩溃（117s 窗口） | Marker 结果**只在整条 pipeline 完成后**才落盘（第 5 步）⇒ **白跑一次昂贵调用** | `AcademicIngestionService` 第 3→5 步 |
| 3 | Embedding 中途崩溃 | 已算好的批次只在**内存**里 ⇒ 重启后**全部重算** | 第 7 步 |
| 4 | 无法判断"卡在哪" | `ingestion_tasks.current_step` / `progress` 运行中不更新 | 列存在、无写入点 |
| 5 | 无 resume 入口 | 没有任何"从 checkpoint/产物续跑"的代码 | 全仓无 resume 逻辑 |
| 6 | 重启后无自动接管 | 无启动扫描"running 但超时"的任务 | 无此类组件 |
| 7 | GENERIC 半成品 | 无事务 ⇒ 崩溃留下**部分 chunk**，且 `documents.status` 可能仍是 `embedding` | `saveChunksAndMarkIndexed` |

### 3.7 现有中间产物是否足够支撑恢复？—— **够，但没利用**

MinIO 中已有（实测目标论文）：

```text
documents/{documentId}/versions/{version}/source/original.pdf          2,751,045 B
documents/{documentId}/versions/{version}/artifacts/marker-raw.json      696,193 B
documents/{documentId}/versions/{version}/artifacts/canonical.json       153,024 B
documents/{documentId}/versions/{version}/artifacts/chunks.json           40,153 B
```

⇒ **理论上可从三个粒度恢复**：

| 恢复起点 | 可跳过的步骤 | 依据 |
|---|---|---|
| `marker-raw.json` 存在 | 跳过 Marker（省 ~117s 远端 GPU） | 可离线重跑 Parser/Repair/Cleaner |
| `canonical.json` 存在 | 跳过 Marker + Parser + Repair + Cleaner | 可离线重跑 Chunker |
| `chunks.json` 存在 | 跳过上面全部，只重跑 embedding + 落库 | manifest 含 chunk 内容之外的关键字段（**但 manifest 未含 content 全文** ⇒ 需补） |

**缺口**：① 没有"读取已存在产物并续跑"的代码；② `chunks.json` 是 **manifest（只存 chunkId/chunkType/tokenCount/sectionPath/pages/sourceBlockIds/contentHash）**，**不含 content 与 embeddingText 全文** ⇒ 从 chunks.json 恢复需要**同时**读 canonical.json（可接受：canonical 更小且完整）。

---

## 4. LangGraph4j 接入边界设计（step1 §4）

### 4.1 可直接复用的现有 Service（**一行不改**）

`MarkerClient`/`MarkerHttpClient` · `MarkerDocumentParser` · `ReadingOrderRepair` · `PaperCleaner`(+13 个清洗组件) · `CanonicalDocumentValidator` · `SectionAwareChunker` · `EmbeddingGateway` · `AcademicChunkPersistenceMapper` · `ChunkPersistenceService` · `MinioService` · `IngestionStatusUpdater` · `ParseModeResolver` · `ArtifactKeyBuilder`

### 4.2 必须迁移到图编排的逻辑

| 现有 | 迁移为 |
|---|---|
| `IngestionServiceImpl.ingest` 的分流 if/else | **条件边**（`addConditionalEdges(start, routeByParseMode, {ACADEMIC→…, GENERIC→…})`） |
| `AcademicIngestionService.ingest` 的 10 步 | **7 个节点**（见 §4.4） |
| `ingest` 的 `catch → failed` | **错误边/统一失败节点**（或 Graph 异常出口 + 外层兜底，二者择一，见 §8） |
| `ingestGeneric` | 保留为**单个节点**（GENERIC 不拆，降低改造面） |

### 4.3 必须保持原子事务的

**`ChunkPersistenceService.replaceChunks`（ACADEMIC）与 GENERIC 的 saveChunks 都必须整体在一个节点内、且一个事务里** —— 如果拆成"delete 节点"+"insert 节点"，checkpoint 落在两者之间会让恢复变成"数据已删、尚未插"，比现状更糟。**结论：落库是"不可切分节点"**。

> 顺带建议（不改也可）：给 GENERIC 的 `saveChunksAndMarkIndexed` 补 `@Transactional`，否则它比 ACADEMIC 更脆弱。

### 4.4 阶段级 Checkpoint 节点划分（ACADEMIC，建议 7 节点）

| # | 节点 | 幂等性 | checkpoint 价值 | 恢复动作 |
|---|---|---|---|---|
| 1 | `prepare`（读 documents/校验 + 决定 parseMode） | 幂等读 | 低 | — |
| 2 | `downloadSource`（MinIO→byte[]） | 幂等 | 低（可重复下载） | 重下 |
| 3 | **`parseAndClean`**（Marker→Parser→Repair→Cleaner + **落 marker-raw & canonical**） | **需先查产物存在** | **★最高**（省 117s 远端 GPU） | 若产物在 ⇒ 跳过 |
| 4 | `validateCanonical`（hard-invalid 判定） | 纯判定 | 低 | — |
| 5 | `chunk`（Chunker + 落 chunks.json） | 需先查产物 | 高 | 若产物在 ⇒ 跳过 |
| 6 | **`embed`**（★批次级可恢复，见 §4.5） | 批次级 | **★高** | 从 `completedBatches` 继续 |
| 7 | `persist`（**不可切分**，原子事务） | delete+insert | 高 | 重跑（幂等） |
| 8 | `finalize`（indexed + task success） | 幂等 | 低 | — |

### 4.5 Embedding 批次级恢复设计（关键）

**约束**：PostgresSaverV2 把 state 序列化进 `LG4JCheckpoint.state_data`（TEXT/JSONB）——**绝不要把 126×1024 个 float 放 state**（会撑爆 checkpoint 表且每次落盘极慢）。

**建议方案（产物外置 + state 只存进度）**：

```text
节点 embed 内部按 batchSize(默认 32) 循环：
  每批完成 → 把该批向量写到 MinIO: documents/{id}/versions/{v}/artifacts/embeddings/batch-{i}.json
           → 更新 state: { embeddingBatchSize, totalBatches, completedBatches:[i,j,…] }
           → 触发 checkpoint（该节点可用 addNode(..., AsyncNodeActionWithConfig) 拿 config 主动落盘，
              或拆成 N 个"批节点 embed#i"由 checkpointer 天然逐批记录）
恢复时：读 state.completedBatches ⇒ 只对未完成批次调用 gateway ⇒ 从 MinIO 读回已完成批次
```
> 两种实现路径（**逐批节点** vs **单节点内自管进度**）各有取舍，建议先做**单节点自管**（改动小），验证后再考虑拆批节点。

### 4.6 Graph State 字段建议（小而可序列化）

```java
class RagIngestionState extends AgentState {   // 依据：AgentState(Map<String,Object>) 构造 + value() 读取
  // —— 身份 ——
  Long   documentId;          // DB documents.id
  String domainDocumentId;    // 文件 SHA-256（ACADEMIC 领域 id）
  Long   taskId;              // = ingestion_tasks.id（同时用作 threadId）
  Long   knowledgeBaseId;
  String parseMode;           // AUTO/ACADEMIC/GENERIC（决策结果）
  int    version;             // documents.version
  String fileName;
  String storagePath;         // MinIO 源文件 key

  // —— 阶段产物 key（幂等判定的依据）——
  String markerRawKey;
  String canonicalKey;
  String chunksKey;
  int    chunkCount;

  // —— embedding 批次进度（★不放向量本体）——
  int    embeddingBatchSize;
  int    totalBatches;
  List<Integer> completedBatches;
  List<String>  batchRefKeys;   // artifacts/embeddings/batch-i.json

  // —— 终态 ——
  boolean persisted;
  String  errorCode;            // ACADEMIC_* 错误码
  String  errorMessage;
  List<String> warnings;        // canonical warnings（warning-only 允许入库）
  int    repairCount;           // 供 metrics
}
```

### 4.7 taskId ↔ threadId 映射

**依据**（实测 DDL，见 §5.3）：`LG4JThread.thread_name VARCHAR(255) UNIQUE`；`LG4JCheckpoint.thread_id BIGINT`。

```java
// 推荐：直接用 taskId 字符串作为 threadId（唯一、稳定、无需额外映射表）
RunnableConfig cfg = RunnableConfig.builder()
        .threadId(String.valueOf(taskId))      // RunnableConfig$Builder.threadId(String)
        .build();
compiledGraph.invoke(GraphInput.args(initialState), cfg);   // 首次
compiledGraph.invoke(GraphInput.resume(), cfg);            // 崩溃后从最后 checkpoint 继续
compiledGraph.getState(cfg);                               // 查当前阶段（可用于"卡在哪"可视化）
```
可选增强：`RunnableConfig.builder().checkPointId(...)` 做**定点回滚**（`withCheckPointId`）。

---

## 5. LangGraph4j 1.9.4 API 核实结果（step1 §5）

> 核实方式：**直接从本地仓库真实 jar 用 `javap` 读取签名**（`~/.m2/repository/org/bsc/langgraph4j/...`），并解包 jar 读取其自带 DDL。**未依据任何记忆或推测。**

### 5.1 点名的 7 个 API

| # | API | 是否存在 | 关键真实签名（节选） |
|---|---|---|---|
| 1 | `StateGraph` | ✅ | `StateGraph(Map<String,Channel<?>>, StateSerializer<State>)` · `addNode(String, AsyncNodeAction<State>)` · `addEdge(String,String)` · **`addConditionalEdges(String, AsyncEdgeAction<State>, Map<String,String>)`** · `compile()` / `compile(CompileConfig)` |
| 2 | `AgentState` | ✅ | `AgentState(Map<String,Object>)` · `data()` · `value(String)` / `value(String,T)` · `MARK_FOR_RESET` / `MARK_FOR_REMOVAL` |
| 3 | `CompileConfig` | ✅ | `builder()` · `recursionLimit()` · **`checkpointSaver() → Optional<BaseCheckpointSaver>`** · `interruptsBefore()` / `interruptsAfter()` · `releaseThread()` |
| 4 | `RunnableConfig` | ✅ | `builder()` · **`threadId() → Optional<String>`** · `checkPointId()` / `withCheckPointId(String)` · `streamMode()` · `recursionLimit()` |
| 5 | `GraphInput` | ✅（interface） | `value()` · **`resume()` / `resume(Map)` / `resume(Checkpoint)`** · `args(Map)` · `noArgs()` |
| 6 | `PostgresSaverV2` | ✅ | `builder()` → Builder：**`datasource(DataSource)`** · `host/port/user/password/database` · **`createTables(boolean)`** · `dropTablesFirst(boolean)` · `stateSerializer(StateSerializer)` · `build() throws Exception`（构造器 protected ⇒ 必须走 builder） |
| 7 | `StateSerializer` | ✅（abstract） | `StateSerializer(AgentStateFactory<State>)` · `stateOf(Map)` · `writeDataAsString(Map)` · `readDataFromReader(Reader)` · `declareTransientAttributes(String...)`。实现类：`plain_text.jackson.JacksonStateSerializer` · `plain_text.gson.GsonStateSerializer` · `std.ObjectStreamStateSerializer` · `std.StdStateSerializer` |

### 5.2 附加确认（对设计有直接影响）

| API | 用途 |
|---|---|
| `CompiledGraph.invoke(GraphInput, RunnableConfig) → Optional<State>` | 同步执行 |
| `CompiledGraph.stream(GraphInput, RunnableConfig) → AsyncGenerator.Cancellable<NodeOutput<State>>` | 逐节点流（可做进度上报） |
| **`CompiledGraph.getState(RunnableConfig)` / `stateOf` / `lastStateOf` / `getStateHistory`** | 查当前阶段 / 历史（**"卡在哪"可视化**） |
| **`CompiledGraph.updateState(RunnableConfig, Map, String)`** | 外部修正 state（运维补救） |
| `Channels.base(Supplier)` / `base(Reducer)` / `appender(Supplier<List<T>>)` | state schema 声明（key + reducer） |
| `AsyncNodeAction<S>` = `Function<S, CompletableFuture<Map<String,Object>>>`（含 `node_async(NodeAction)`） | 节点实现方式 |
| `AsyncEdgeAction<S>` = `Function<S, CompletableFuture<String>>`（含 `edge_async(EdgeAction)`） | 条件边实现方式 |
| `prebuilt.MessagesStateGraph` | 现成的消息态图（本项目暂不需要） |

### 5.3 Checkpoint 的 DDL —— **jar 自带，无需自写**

`langgraph4j-postgres-saver-1.9.4.jar` 内含：

```text
db/migration/v1.0__init.sql   v1.1__init.sql   v2.0__init.sql   v2.1__init.sql
db/v1.0__commands.sql         v1.1__commands.sql  v2.0__commands.sql  v2.1__commands.sql
```

`db/migration/v2.1__init.sql`（**V2 的最终形态**）：

```sql
CREATE TABLE IF NOT EXISTS LG4JThread (
    thread_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    thread_name VARCHAR(255) UNIQUE NOT NULL,
    parent_thread_id BIGINT, is_interrupted BOOLEAN DEFAULT FALSE NOT NULL,
    message TEXT, created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL);

CREATE TABLE IF NOT EXISTS LG4JThreadTag (
    thread_id BIGINT PRIMARY KEY, thread_name VARCHAR(255), released_version INTEGER,
    parent_thread_id BIGINT, is_released BOOLEAN DEFAULT FALSE NOT NULL,
    is_error BOOLEAN DEFAULT FALSE NOT NULL, message TEXT, created_at TIMESTAMPTZ);

CREATE TABLE IF NOT EXISTS LG4JCheckpoint (
    checkpoint_id UUID PRIMARY KEY, parent_checkpoint_id UUID, thread_id BIGINT NOT NULL,
    node_id VARCHAR(255), next_node_id VARCHAR(255),
    state_data TEXT NOT NULL, state_content_type VARCHAR(100) NOT NULL,   -- v2.0 是 JSONB，v2.1 改为 TEXT
    saved_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP);
-- 索引：thread_id · (thread_id, saved_at DESC) · (thread_name, released_version DESC)
-- 外键 FK(thread_id)→LG4JThread 在 DDL 中【被注释掉】（未强制）
```

**四条重要结论**：
1. 表名 `LG4J*` 与业务表**无命名冲突**（`knowledge_bases/documents/document_chunks/ingestion_tasks/chat_*`）
2. `thread_name` **UNIQUE VARCHAR(255)** ⇒ **`taskId` 字符串可直接当 threadId**（§4.7）
3. V2.1 的 `state_data` 是 **TEXT + state_content_type**（v2.0 曾是 JSONB）⇒ **序列化器选择需与之匹配**（TEXT ⇒ 应使用 `PlainTextStateSerializer` 家族，如 `JacksonStateSerializer`；若用 `ObjectStreamStateSerializer` 会写二进制，与 TEXT 语义不符）→ **列为待验证**
4. Builder 有 **`createTables(boolean)` / `datasource(DataSource)`** ⇒ **可复用 Spring 数据源并自动建表**（免手写 DDL、免额外连接配置）

---

## 6. Day 1 Step 2–5 实施建议（step1 §5）

> 原则：**每步都能独立验收；先跑通、再切持久化、最后才做批次级恢复**。
> 前置建议（强烈）：**先把测试网补回来**（至少 `IngestionServiceImpl`/`ChunkPersistenceService`/`AcademicIngestionService` 的集成测试 + 一个"杀进程恢复"实验脚本），否则后续每步都无法回归。

### Step 2 — 骨架（不改业务行为）
```text
目标：StateGraph 能编译、能跑通一条"占位图"，PostgresSaverV2 能建表并落盘 checkpoint
产出：workflow 包（见 §7）+ RagIngestionState + 一个 start→prepare→finalize 的最小图
验收：① 启动时 LG4JThread/LG4JThreadTag/LG4JCheckpoint 三表自动创建
      ② invoke 后 LG4JCheckpoint 有行；getState(cfg) 能读回
      ③ 现有 Kafka 链路完全不受影响（Graph 尚未接入）
风险点：stateSerializer 与 v2.1 的 TEXT 列匹配（§5.3 结论 3）
```

### Step 3 — 1:1 搬节点（仍用 MemorySaver）
```text
目标：把 AcademicIngestionService 的 10 步原样拆成 7 节点，行为与现状【完全一致】
做法：节点内直接调用现有 Service（不重写逻辑）；用 MemorySaver 先跑通（避开 DB）
验收：同一篇 PDF 经 Graph 产生的 chunks 数 / 类型分布 / MinIO 4 个 artifact 与现状【逐项一致】
      （已知基准：目标论文 126 chunks、refs 66、tab 6、fig 3）
```
> 这一步是本改造的**行为等价性证明**，务必先做、先测。

### Step 4 — 切 PostgresSaverV2 + 崩溃实验
```text
目标：证明"崩溃后可恢复"
实验设计（建议 4 个断点）：
  A 在 parseAndClean 之前 kill -9  → 重启后应重跑 Marker
  B 在 parseAndClean 之后 kill -9  → 重启后应【跳过 Marker】（读 marker-raw/canonical）
  C 在 chunk 之后 kill -9         → 重启后应【跳过 Marker+Chunker】（读 chunks.json）
  D 在 persist 之后 kill -9       → 重启后应幂等重跑落库（不产生重复 chunk）
每次校验：documents.status / chunk_count / LG4JCheckpoint.node_id / MinIO 产物
验收：4 个断点全部恢复成功，且无重复 chunk（UNIQUE + chunk_key 双保险）
```

### Step 5 — 批次级 embedding 恢复 + 幂等/并发补强
```text
① embed 节点自管批次进度（§4.5），state 只存 completedBatches，向量写 MinIO
② 补并发保护：同一 documentId 的"running 任务"唯一（DB 级唯一部分索引 或 状态 CAS）
③ 补超时接管：启动扫描 status=running 且 updated_at 超阈值的任务 → 重新投递
④ 给 GENERIC 的落库补 @Transactional
⑤ （可选）Kafka 消费错误处理：retry + DLT（至少不要静默丢消息）
验收：embedding 中途崩溃只重算未完成批次（日志可证）；并发重复投递不产生双写
```

---

## 7. 需要修改或新增的文件清单（step1 §6）

### 7.1 新增（推荐包结构）

```text
src/main/java/com/example/ragdemo/workflow/
├── state/
│   ├── RagIngestionState.java          # 见 §4.6（extends AgentState）
│   └── RagIngestionStateKeys.java      # state key 常量 + Channels schema 声明
├── node/
│   ├── PrepareNode.java                # 读 documents / 决定 parseMode
│   ├── DownloadSourceNode.java
│   ├── ParseAndCleanNode.java          # ★最高价值 checkpoint（产物存在则跳过）
│   ├── ValidateCanonicalNode.java
│   ├── ChunkNode.java                  # 产物存在则跳过
│   ├── EmbedNode.java                  # ★批次级恢复
│   ├── PersistNode.java                # 不可切分（事务）
│   └── FinalizeNode.java               # indexed + task success
├── node/GenericIngestionNode.java      # GENERIC 作为单节点（直接复用 ingestGeneric）
├── route/ParseModeRouter.java          # 条件边：ACADEMIC / GENERIC
├── IngestionGraphFactory.java          # 建图（节点+边+条件边）+ compile(CompileConfig)
├── IngestionGraphConfig.java           # @Configuration：PostgresSaverV2(DataSource) + StateSerializer
└── IngestionWorkflowRunner.java        # 对外唯一入口：start(documentId, taskId) / resume(taskId)
```

### 7.2 需要修改（尽量小）

| 文件 | 改动 | 说明 |
|---|---|---|
| `service/serviceImpl/IngestionServiceImpl.java` | `ingest()` 改为委托 `IngestionWorkflowRunner.start/resume`；`ingestGeneric` 原样保留（作为单节点逻辑） | 保留旧路径可灰度（配置开关切换） |
| `config/RagProperties.java` | 新增 `Workflow` 段（enabled / checkpointBackend=postgres\|memory / embeddingBatchSize） | 可一键回退 |
| `src/main/resources/application.yml` | 新增 `rag.workflow.*` | |
| `service/serviceImpl/KafkaConsumerService.java` | 增加消费失败的错误处理（重试/DLT 或至少明确日志） | Step 5 ② |
| `service/serviceImpl/IngestionServiceImpl.java`（GENERIC 落库） | 补 `@Transactional` | Step 5 ④ |

### 7.3 明确**不改**的

`pom.xml`（LangGraph4j 已就绪，**无需新增依赖**）· `sql/init.sql`（LG4J 三表由 saver 的 `createTables(true)` 建）· `sql/migration/*`（同上）· Parser/Repair/Cleaner/Validator/Chunker/EmbeddingGateway/Mapper/MinioService/ArtifactKeyBuilder/IngestionStatusUpdater（**全部复用**）

---

## 8. 尚不确定、需要实验验证的内容（step1 §7 —— **禁止虚构**）

| # | 不确定项 | 为什么不确定 | 验证方法 |
|---|---|---|---|
| 1 | **StateSerializer 该选哪个** | jar 提供 Jackson/Gson/Std/ObjectStream 四种；v2.1 的 `state_data` 是 **TEXT** ⇒ 二进制序列化器语义不符，但源码未读 ⇒ **不能断定** | Step 2：分别注入 `JacksonStateSerializer` 与 `ObjectStreamStateSerializer` 各跑一次，检查 `state_data` 内容可读性与 `state_content_type` 取值 |
| 2 | `createTables(true)` 是否执行 jar 内的 `db/migration/*` | 只看到资源文件与 Builder 开关，未读实现 | Step 2：空库启动 → 查 `information_schema.tables` 是否出现 LG4J* |
| 3 | **resume 的语义是"重跑未完成节点"还是"重跑当前节点（at-least-once）"** | 关系到 `parseAndClean` 崩溃后是否重复调用 Marker（贵！） | Step 4 实验 A/B：观察 Marker 调用次数与 `LG4JCheckpoint.node_id` |
| 4 | 单节点内自管批次进度时，如何在该节点中途落 checkpoint | `AsyncNodeAction` 只返回最终 Map；中途落盘可能需 `AsyncNodeActionWithConfig` 拿 config 或用 interrupt | Step 5：两种方案各做原型，比较改动量与可行性 |
| 5 | `interruptsBefore/After` 能否用于"阶段级暂停/人工介入" | 仅看到 Set 配置项，未验证运行语义 | 可选实验 |
| 6 | `GraphInput.resume(Map)` 注入的覆盖数据与 checkpoint state 的合并语义 | 影响"运维修正后继续"的可行性 | Step 4：用 `updateState(cfg, map, nodeId)` 改 state 后 resume，观察生效情况 |
| 7 | 并发场景下 PostgresSaverV2 的 thread 争用行为（`releaseThread` 语义） | `CompileConfig.releaseThread()` 存在但语义未读 | Step 5 并发实验 |
| 8 | LangGraph4j 1.9.4 与 **Spring 事务/MyBatis** 混用是否冲突（同一 DataSource 被 saver 与业务共用） | HikariCP 池被两条路径共享，可能出现连接争用 | Step 4：压测/长时间运行观察连接池指标 |
| 9 | 目标论文 126 chunks 经图重跑后**是否逐项一致** | 节点拆分可能改变调用顺序（如 embedding 与 chunk 的边界） | Step 3 行为等价性验证（已知基准见 §6 Step 3） |

---

## 附录：本次审查用到的命令（可复现）

```powershell
# 依赖
mvn dependency:tree "-Dincludes=org.bsc.langgraph4j:*,org.bsc.async:*,com.fasterxml.jackson.core:*,org.slf4j:*,com.zaxxer:*"
# API 签名（真实 jar）
javap -classpath "$env:USERPROFILE\.m2\repository\org\bsc\langgraph4j\langgraph4j-core\1.9.4\langgraph4j-core-1.9.4.jar" org.bsc.langgraph4j.StateGraph
javap -classpath "$env:USERPROFILE\.m2\repository\org\bsc\langgraph4j\langgraph4j-postgres-saver\1.9.4\langgraph4j-postgres-saver-1.9.4.jar" org.bsc.langgraph4j.checkpoint.PostgresSaverV2
# 自带 DDL（解包 jar 的 db/ 目录）
# Java 兼容性
javap -verbose -classpath "<core jar>" org.bsc.langgraph4j.StateGraph   # major version: 61 (Java 17)
```
