-- V7: L3 长期记忆（仅写入与管理；本阶段不做检索/Prompt 注入）
-- 复用 PostgreSQL + pgvector；维度与 document_chunks.embedding 一致（bge-m3 = 1024，见 sql/init.sql:78 与 application.yml L61-62）
-- 注意：本迁移只新增表/索引，不触碰任何既有表与数据。

CREATE TABLE IF NOT EXISTS long_term_memories (
    memory_id              BIGINT       PRIMARY KEY,
    principal_id           VARCHAR(128) NOT NULL,           -- 服务端可信身份（客户端/模型不可设置）
    scope_key              VARCHAR(128),                    -- 项目/课题作用域；NULL = 个人全局
    memory_type            VARCHAR(16)  NOT NULL,           -- FACT / PREFERENCE / DECISION
    subject                VARCHAR(256) NOT NULL,           -- 归一化主语
    canonical_key          VARCHAR(64)  NOT NULL,           -- sha256(principal|scope|type|subject|norm(predicate))
    content                TEXT         NOT NULL,
    embedding              VECTOR(1024),
    status                 VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    version                INT          NOT NULL DEFAULT 1,
    confidence             REAL         NOT NULL DEFAULT 0.5,
    source_conversation_id VARCHAR(64),
    source_message_id      VARCHAR(64),
    source_run_id          VARCHAR(64),
    superseded_by          BIGINT REFERENCES long_term_memories (memory_id),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_ltm_type CHECK (memory_type IN ('FACT', 'PREFERENCE', 'DECISION')),
    CONSTRAINT ck_ltm_status CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'CONFLICT', 'REVOKED')),
    CONSTRAINT ck_ltm_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_ltm_version CHECK (version >= 1)
);

-- 约束：同一 (principal, scope, canonical_key) 至多一条 ACTIVE（数据库层强制，非 Prompt 层约定）
CREATE UNIQUE INDEX IF NOT EXISTS uk_ltm_active_slot
    ON long_term_memories (principal_id, COALESCE(scope_key, ''), canonical_key)
    WHERE status = 'ACTIVE';

-- 版本号唯一（历史版本可追溯，同一槽位的版本不可重复）
CREATE UNIQUE INDEX IF NOT EXISTS uk_ltm_version
    ON long_term_memories (principal_id, COALESCE(scope_key, ''), canonical_key, version);

CREATE INDEX IF NOT EXISTS idx_ltm_principal_status ON long_term_memories (principal_id, status);
CREATE INDEX IF NOT EXISTS idx_ltm_principal_type ON long_term_memories (principal_id, memory_type, status);
CREATE INDEX IF NOT EXISTS idx_ltm_source_run ON long_term_memories (source_run_id);
-- 与现有 document_chunks 一致的向量索引类型（ivfflat）
CREATE INDEX IF NOT EXISTS idx_ltm_embedding ON long_term_memories USING ivfflat (embedding vector_cosine_ops);

CREATE TABLE IF NOT EXISTS memory_extraction_jobs (
    job_id            BIGINT       PRIMARY KEY,
    principal_id      VARCHAR(128) NOT NULL,
    conversation_id   VARCHAR(64)  NOT NULL,
    source_message_id VARCHAR(64),
    source_run_id     VARCHAR(64),
    idempotency_key   VARCHAR(128) NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts          INT          NOT NULL DEFAULT 0,
    last_error        VARCHAR(512),
    next_retry_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_memory_job_idem UNIQUE (idempotency_key),
    CONSTRAINT ck_mjob_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'DEAD')),
    CONSTRAINT ck_mjob_attempts CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS idx_mjob_pick ON memory_extraction_jobs (status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_mjob_conversation ON memory_extraction_jobs (conversation_id);
