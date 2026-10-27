-- =============================================================================
-- Day 2 Step 4：Agent Run Registry（Agent 跨请求/跨 JVM 的运行身份与执行权）
--
-- 本仓库未启用迁移框架（与 V2/V3/V4 一致），本文件由维护者手工执行：
--   docker exec -i ragdemo-postgres psql -U liyong -d ragdemo < sql/migration/V5__agent_run_registry.sql
-- 回滚（仅回滚本步新增表，不动其它数据）：
--   DROP TABLE IF EXISTS agent_runs;
--
-- 设计要点：
--   * run_id 为一次 HTTP 请求内的 Agent 执行 ID（服务端生成 UUID）；
--   * thread_id = 'agent-' + run_id，是 LangGraph4j 的本轮 checkpoint 身份，UNIQUE 保证 1:1；
--   * request_key 用于 HTTP 重试幂等（可为空 ⇒ 偏索引唯一），同一 key 只允许一条 Run；
--   * revision 支持乐观并发抢占（单条 UPDATE ... WHERE revision = ? 原子抢占）；
--   * memory_committed 让"本轮问答写入会话记忆"最多发生一次（崩溃恢复后不重复插入）；
--   * 不存原文、句柄、密钥（只存问题摘要 digest）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS agent_runs (
    run_id            VARCHAR(64) PRIMARY KEY,

    -- 跨请求会话（可为空：未启用记忆的调用）
    conversation_id   VARCHAR(64),
    -- 受信知识库范围：恢复时**只**用于与请求授权结果做一致性校验，绝不用它创建 Toolkit
    knowledge_base_id BIGINT      NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    principal_id      VARCHAR(128),

    -- HTTP 重试幂等键（客户端可传；服务端校验归属）
    request_key       VARCHAR(128),
    thread_id         VARCHAR(128) NOT NULL UNIQUE,

    status            VARCHAR(16) NOT NULL DEFAULT 'CREATED'
        CHECK (status IN ('CREATED', 'RUNNING', 'INTERRUPTED', 'COMPLETED', 'FAILED')),

    -- 仅问题摘要（sha256），不存原文
    question_digest   VARCHAR(64),
    -- 本轮问答是否已写入会话记忆（幂等标记）
    memory_committed  BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 乐观并发版本号
    revision          INT         NOT NULL DEFAULT 0,
    last_error_code   VARCHAR(64),

    created_at        TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP   NOT NULL DEFAULT NOW(),
    completed_at      TIMESTAMP
);

-- 同一 request_key 只允许一条 Run（null 不计入）
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_runs_request_key
    ON agent_runs (request_key) WHERE request_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_runs_conversation ON agent_runs (conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_runs_status ON agent_runs (status);
CREATE INDEX IF NOT EXISTS idx_agent_runs_kb ON agent_runs (knowledge_base_id);
