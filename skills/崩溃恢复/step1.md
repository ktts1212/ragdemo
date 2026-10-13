# 任务：Java RAG 项目 LangGraph4j 改造前审查（Day 1 / Step 1）

## 一、项目背景

这是一个基于 Spring Boot 的 Java 文献 RAG 项目。

现有技术栈包括：
- LangChain4j：LLM、Embedding
- Marker API：学术 PDF 解析
- Kafka：异步文献入库
- MinIO：源文件与中间产物存储
- PostgreSQL + pgvector：业务数据与向量存储

目前已经实现：
1. 文档上传与 Kafka 异步入库
2. 学术 PDF 解析、清洗、分块
3. Embedding 与 pgvector 入库
4. 文献检索与 RAG 问答
5. 会话记忆

现在计划基于 LangGraph4j 1.9.4 实现持久化工作流与崩溃恢复，后续扩展单 Agent。

## 二、本次任务

本次仅进行代码审查和改造设计。

**严禁修改、创建或删除任何项目文件。**

### 1. 检查项目依赖

分析 pom.xml，确认：
- Java、Spring Boot、LangChain4j 版本
- LangGraph4j 是否已引入
- 是否存在依赖版本冲突风险
- PostgreSQL 数据源配置
- 测试框架是否完善

### 2. 审查入库执行链路

重点阅读：

- DocumentServiceImpl
- IngestionServiceImpl
- AcademicIngestionService
- KafkaConsumerService
- IngestionStatusUpdater
- ChunkPersistenceService
- CanonicalDocumentPipeline
- EmbeddingGateway
- MinioServiceImpl

以实际存在的类为准。

梳理上传、任务创建、Kafka 消费、解析、清洗、分块、Embedding、数据库写入的真实执行顺序。

### 3. 审查任务状态与数据库

分析：
- documents
- ingestion_tasks
- document_chunks

重点确认：
- taskId 如何生成及传递
- 当前有哪些任务状态
- 是否存在任务并发执行保护
- 哪些操作具备幂等性
- 哪些操作有数据库事务
- 哪些状态在崩溃后无法恢复
- 现有中间产物是否足够支撑恢复

### 4. 设计 LangGraph4j 接入边界

明确：
- 哪些现有 Service 可以直接复用
- 哪些编排逻辑未来需要迁移到 StateGraph
- 哪些操作应该保持原子事务
- 哪些节点适合阶段级 Checkpoint
- Embedding 如何实现批次级恢复
- Graph State 应保存哪些字段
- taskId 和 threadId 如何映射

不要实际实施改造。

### 5. 核实 LangGraph4j 1.9.4 API

优先依据官方文档、对应版本源码及已下载的依赖确认：
- StateGraph
- AgentState
- CompileConfig
- RunnableConfig
- GraphInput
- PostgresSaverV2
- StateSerializer

无法确认的 API 必须标记为待验证，禁止虚构接口。

## 三、输出要求

请在最终回复中输出以下内容：

1. 当前真实项目结构与调用链
2. 已有的状态管理、事务和幂等能力
3. 与崩溃恢复有关的主要风险
4. 推荐新增的 workflow 包结构
5. Day 1 Step 2–5 的具体实施建议
6. 需要修改或新增的文件清单
7. 当前尚不确定、需要实验验证的内容

每一项重要结论尽可能标注实际文件路径、方法名或代码依据。

## 四、限制

- 不修改项目代码
- 不创建文档或其他文件
- 不重构现有业务链路
- 不引入 Agent
- 不运行可能改变业务数据的操作
- 不清空 PostgreSQL、Kafka 或 MinIO 数据

本次目标是生成可信的改造方案，而不是直接实现功能。