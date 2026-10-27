# Runner 前置就绪审计（Step 5，只审计不跑分）

> 目的：确认两个问答入口能否**公平地**访问同一份隔离的评测语料，并给出统一 `EvaluationResult` Schema 与 Runner 设计。
> 依据：真实源码取证（非文档推断）。**本轮未运行任何题目**；参考答案未被修改。

## 1. 接口事实（源码取证）

| 维度 | `/api/chat`（传统 RAG） | `/api/agent/chat`（Agent） |
|---|---|---|
| Controller | `ChatController` → `ChatService.chat(ChatRequestDto)` | `AgentChatController` → `RagAgentService.run(...)` |
| 响应 | `Result<ChatResponseDto>{answer, sources[SourceReference], conversationId}` | `AgentRunResult{answer, sources, usedTools, finishReason, warnings, trace, handles, toolCallsUsed, llmTurnsUsed, runId, …}`；异常时 `{error, message}` |
| KB 作用域 | ✅ 请求体 `knowledgeBaseId`（+`topK`/`minScore`/`model`/`conversationId`），默认值由服务端补 | ⚠ 请求体 `knowledgeBaseId`，但**必须命中服务端白名单** `rag.agent.allowed-knowledge-base-ids`（默认 `[1]`） |
| 检索语料 | `DocumentRetrievalService.retrieve(RetrievalRequest(query, kbId, topK, minScore))` → pgvector `document_chunks` 按 kb 过滤 | **同一** `DocumentRetrievalService`（`search` 工具内）⇒ 语义一致 |
| 幂等/隔离 | `conversationId` 会话记忆 | `conversationId` + `requestKey`（同 key 同问题 ⇒ 同 Run 零重复执行；异问题 ⇒ 409） |
| 延迟 | 需 Runner 自行计时（响应无 latency 字段） | 同 |
| Tool Calls / LLM Turns | ❌ 无（无工具概念） | ✅ `usedTools`/`trace`/`toolCallsUsed`/`llmTurnsUsed` |
| **Token usage** | ❌ 不可观测 | ❌ **不可观测**（全仓无 usage 字段；不得估算） |
| 超时/错误语义 | 由 `GlobalExceptionHandler` 统一（具体形状待 Runner 抓取实测） | 400/403(跨KB)/409(requestKey 冲突、LEASE_LOST)/500(AGENT_ERROR)/503(AGENT_DISABLED) |

## 2. 评测 KB 可行性结论

- ✅ **支持独立 KB**：`POST /api/documents/upload`（与 `/batch-upload`）带 `knowledgeBaseId` 参数；`DocumentServiceImpl.upload()` 会 `checkKnowledgeBaseExists()`（`KnowledgeBasesMapper.existsById`）。
- ❌ **没有创建 KB 的业务接口**（`knowledge_bases` 仅 MyBatis-Plus `BaseMapper`）⇒ 需要一个 `knowledge_bases` 行。**这是本轮未擅自执行的决策点**：
  - 方案 A（推荐）：由你确认后，**插入一行 `knowledge_bases(id=<新id>, name='eval-...')`**（这是**引用数据**插入，不是 `documents` 插入；随后所有论文仍**必须**走上传/入库 Graph）；
  - 方案 B：复用某个已存在的干净 KB（需先列出 `knowledge_bases` 现有行确认是否存在）；
  - 方案 C：继续用 kb=1（❌ 不推荐：存在 96 条重复记录，会污染检索排名）。
- ⚠ Agent 侧需在运行配置中将评测 KB 加入 `allowed-knowledge-base-ids`（**配置变更**，非架构变更），否则 Agent 会以 403 拒绝。

## 3. 统一 EvaluationResult Schema（Runner 输出，一行一题一系统）

```json
{
  "schema_version": "1.0",
  "run_id": "uuid",
  "run_at": "ISO-8601",
  "system": "rag|agent",
  "question_id": "sha256_xxx-q07",
  "paper_id": "sha256:xxx",
  "document_id": 123,
  "knowledge_base_id": 7,
  "top_k": 5,
  "min_score": 0.6,
  "request": {"question": "...", "conversation_id": "...", "request_key": "..."},
  "http_status": 200,
  "latency_ms": 1234,
  "answer": "...",
  "sources": [{"documentId": 123, "page": 8, "score": 0.71, "preview": "..."}],
  "tool_calls": 0,
  "used_tools": [],
  "llm_turns": 1,
  "finish_reason": "ANSWERED",
  "error": {"code": null, "message": null},
  "token_usage": "unknown",
  "raw_response_path": "eval-harness/evals/logs/<run_id>/<question_id>.<system>.json",
  "notes": ["latency measured by runner (not exposed by API)"]
}
```

## 4. Runner 设计（Java，`eval-harness` 阶段实现）

1. **输入**：`eval-dataset/frozen/core_subset.v3.jsonl`（110 题；建议先用 `SEMANTIC_VERIFIED` 91 题，`VISUAL_PENDING` 19 题作扩展集）。
2. **隔离**：每题、每系统使用**独立 `conversationId`**（如 `eval-<run_id>-<qid>-<system>`）与**独立 `requestKey`**（agent 侧 `eval-<run_id>-<qid>`）⇒ 幂等且互不串扰；串行执行避免并发抢占同一 Run。
3. **公平性**：同 KB、同 `topK`/`minScore`、同题集；模型差异**必须记录**（`/api/chat` 可用 `model` 参数，agent 用服务端 `rag.agent.model-key`/`rag.models.default-model`）⇒ 报告中单列。
4. **观测**：`latency_ms` 由 Runner 计时；`tool_calls`/`llm_turns` 仅 agent 可得（rag 记 0/1 并标注"含可能的一次 query rewrite"）；`token_usage` **一律 `unknown`**（不得估算）。
5. **落盘**：每题原样保存响应 JSON（`evals/logs/`）+ 聚合 CSV/JSON（`evals/experiments/`）；`run_config`（KB id、模型、topK、题集 sha256）随实验保存以复现。
6. **禁用**：Runner **不得**把任何模型输出写回评测集（`frozen/**` 只读）；评分（是否命中参考答案/证据页）另做，不改 gold。

## 5. 阻塞项（Gate 未过）
1. 评测 KB 行（方案 A/B/C 待你决策）✗；
2. 15 篇论文尚未导入评测 KB（须走上传+入库 Graph；单篇分钟级，需断点续跑）✗；
3. Agent 白名单未包含评测 KB ✗；
4. `core_subset.v3` 的每题 `document_id` 目前为 `null`（`kb_mapping_status=NOT_FOUND/AMBIGUOUS`）✗；
5. Token usage 不可观测（永久限制，非阻塞）⚠。
