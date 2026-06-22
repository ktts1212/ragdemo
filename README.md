# ragdemo

一个最小可运行的 Java RAG 学习项目，技术栈：

- Java 17
- Spring Boot 3
- Maven
- LangChain4j
- PostgreSQL + pgvector
- OpenAI-compatible API

## 项目结构

```text
ragdemo
├── docker-compose.yml
├── pom.xml
├── README.md
├── sql
│   └── init.sql
└── src
    └── main
        ├── java
        │   └── com/example/ragdemo
        │       ├── RagDemoApplication.java
        │       ├── config
        │       │   ├── AppConfig.java
        │       │   └── RagProperties.java
        │       ├── controller
        │       │   ├── ChatController.java
        │       │   ├── DocumentController.java
        │       │   └── GlobalExceptionHandler.java
        │       ├── model
        │       │   ├── dto
        │       │   │   ├── ChatRequest.java
        │       │   │   ├── ChatResponse.java
        │       │   │   ├── SourceReference.java
        │       │   │   └── UploadResponse.java
        │       │   └── entity
        │       │       └── DocumentChunk.java
        │       ├── repository
        │       │   └── DocumentChunkRepository.java
        │       ├── service
        │       │   ├── ChatService.java
        │       │   ├── DocumentService.java
        │       │   └── OpenAiClient.java
        │       └── util
        │           └── VectorUtils.java
        └── resources
            └── application.yml
```

## 启动 PostgreSQL + pgvector

```bash
cd D:/IDEAProjects/ragdemo
docker compose up -d
```

`sql/init.sql` 会自动创建 `vector` 扩展和 `document_chunks` 表。

注意：当前建表 SQL 使用 `VECTOR(1536)`，对应默认 embedding 模型 `text-embedding-3-small`。如果你换成其他维度的 embedding 模型，需要同步修改：

- `sql/init.sql` 里的 `VECTOR(1536)`
- `application.yml` 或环境变量 `OPENAI_EMBEDDING_DIMENSION`

## 配置 API Key

推荐使用环境变量：

```bash
set OPENAI_API_KEY=你的_api_key
set OPENAI_BASE_URL=https://api.openai.com/v1
set OPENAI_CHAT_MODEL=gpt-4o-mini
set OPENAI_EMBEDDING_MODEL=text-embedding-3-small
```

PowerShell 写法：

```powershell
$env:OPENAI_API_KEY="你的_api_key"
$env:OPENAI_BASE_URL="https://api.openai.com/v1"
$env:OPENAI_CHAT_MODEL="gpt-4o-mini"
$env:OPENAI_EMBEDDING_MODEL="text-embedding-3-small"
```

如果使用国产或本地 OpenAI-compatible 服务，只要把 `OPENAI_BASE_URL`、模型名和 Key 换成对应服务即可。

## 启动应用

```bash
mvn spring-boot:run
```

应用默认端口：`http://localhost:8080`

## 测试接口

准备一个文档：

```text
RAG 是 Retrieval-Augmented Generation 的缩写。
它会先从知识库检索相关内容，再把检索结果交给大模型生成回答。
```

保存为 `rag.md` 后上传：

```bash
curl -X POST "http://localhost:8080/documents/upload" \
  -F "file=@rag.md"
```

提问：

```bash
curl -X POST "http://localhost:8080/chat" \
  -H "Content-Type: application/json" \
  -d "{\"question\":\"RAG 的基本流程是什么？\",\"topK\":5}"
```

返回示例：

```json
{
  "answer": "RAG 的基本流程是先从知识库检索相关内容，再把检索结果作为上下文交给大模型生成回答。\n\n引用：rag.md#0",
  "sources": [
    {
      "docName": "rag.md",
      "chunkIndex": 0,
      "score": 0.83,
      "preview": "RAG 是 Retrieval-Augmented Generation 的缩写..."
    }
  ]
}
```

## RAG 完整执行流程

1. 文档上传：用户通过 `/documents/upload` 上传 txt/md 文件。
2. 文本读取：后端读取文件内容。
3. 文档切块：`DocumentService` 使用 LangChain4j 的 `DocumentSplitters.recursive` 切分 chunk。
4. 向量生成：每个 chunk 调用 OpenAI-compatible `/embeddings` 接口生成 embedding。
5. 向量入库：chunk 文本、向量、文档名、chunk_index 写入 PostgreSQL pgvector。
6. 用户提问：用户调用 `/chat`。
7. 问题向量化：后端将问题也转成 embedding。
8. 相似度检索：pgvector 使用 cosine distance 检索 TopK chunk。
9. Prompt 拼接：把检索到的 chunk 拼成上下文。
10. LLM 生成：调用 `/chat/completions` 生成回答。
11. 返回引用：接口返回回答和引用来源 `docName + chunkIndex`。

## 每个类的作用

- `RagDemoApplication`：Spring Boot 启动类。
- `AppConfig`：创建 OpenAI-compatible API 的 `WebClient`。
- `RagProperties`：读取 `application.yml` 中的 RAG 配置。
- `DocumentController`：提供 `/documents/upload` 上传接口。
- `ChatController`：提供 `/chat` 问答接口。
- `GlobalExceptionHandler`：把常见异常转成 JSON 错误响应。
- `DocumentService`：处理文档读取、切块、embedding、入库。
- `ChatService`：处理问题 embedding、TopK 检索、prompt 拼接、LLM 调用。
- `OpenAiClient`：封装 OpenAI-compatible 的 embedding 和 chat HTTP 调用。
- `DocumentChunkRepository`：用 JDBC 操作 PostgreSQL + pgvector。
- `DocumentChunk`：文档 chunk 数据模型。
- `ChatRequest`、`ChatResponse`、`SourceReference`、`UploadResponse`：接口请求和响应 DTO。
- `VectorUtils`：把 Java float 数组转成 pgvector 可识别的字符串，并生成预览文本。

## 文档上传时发生了什么

1. `DocumentController.upload` 接收 multipart 文件。
2. `DocumentService.upload` 校验文件扩展名，只允许 `.txt` 和 `.md`。
3. 读取 UTF-8 文本内容。
4. 删除同名文档旧 chunk，方便重复上传调试。
5. 使用 LangChain4j 按字符数和 overlap 切块。
6. 对每个 chunk 调用 embedding 模型。
7. `DocumentChunkRepository.save` 将 chunk 和向量写入 `document_chunks` 表。

## 用户提问时发生了什么

1. `ChatController.chat` 接收问题。
2. `ChatService.chat` 调用 embedding 模型把问题转为向量。
3. `DocumentChunkRepository.searchSimilar` 使用 pgvector 的 `<=>` 做 cosine 检索。
4. `ChatService` 把 TopK chunk 拼成上下文 prompt。
5. `OpenAiClient.chat` 调用大模型生成回答。
6. 返回回答和 `sources`，每个 source 包含 `docName`、`chunkIndex`、`score` 和预览文本。

## 后续扩展方向

- PDF：引入 Apache PDFBox 或 LangChain4j document loader，先抽取文本，再复用现有切块和入库流程。
- rerank：TopK 先多取一些，例如 20 条，再调用 rerank 模型重排，最终取前 5 条进入 prompt。
- memory：增加 `chat_sessions` 和 `chat_messages` 表，把历史对话按 session 保存；提问时把最近 N 轮对话拼入 prompt。
- agent tool：把检索、数据库查询、外部 HTTP API 封装为 tool，让模型按任务自动决定调用哪些工具。
