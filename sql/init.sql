-- =============================================
-- RAG Demo 数据库初始化（单文件版）
--
-- 说明：
--   1) 本文件已合并 sql/migration/ 下的全部增量迁移（V2 到 V10，无 V1），
--      并直接写成“最终态”结构，不再需要逐个执行 ALTER。
--   2) 破坏性：会先删除并重建下列业务表（含 Agent Run 与长期记忆）。
--      仅用于全新初始化 / 清空重建。
--   3) 向量模型：bge-m3，维度 1024（与 document_chunks.embedding 一致）。
--
-- 表清单：
--   基础：knowledge_bases / documents / document_chunks / ingestion_tasks
--   记忆：chat_conversations / chat_messages / long_term_memories / memory_extraction_jobs
--   编排：agent_runs
-- =============================================

-- ---------- 0. 清理（破坏性） ----------

DROP TABLE IF EXISTS memory_extraction_jobs CASCADE;
DROP TABLE IF EXISTS long_term_memories CASCADE;
DROP TABLE IF EXISTS agent_runs CASCADE;
DROP TABLE IF EXISTS document_chunks CASCADE;
DROP TABLE IF EXISTS ingestion_tasks CASCADE;
DROP TABLE IF EXISTS chat_messages CASCADE;
DROP TABLE IF EXISTS chat_conversations CASCADE;
DROP TABLE IF EXISTS documents CASCADE;
DROP TABLE IF EXISTS knowledge_bases CASCADE;

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------- 1. 知识库 ----------

CREATE TABLE IF NOT EXISTS knowledge_bases (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

INSERT INTO knowledge_bases (id, name, description)
VALUES (1, '默认知识库', '初始化默认知识库')
ON CONFLICT (id) DO NOTHING;

SELECT setval('knowledge_bases_id_seq', COALESCE((SELECT MAX(id) FROM knowledge_bases), 1));

-- ---------- 2. 文档 ----------
-- parse_mode：解析模式（上传参数 > 知识库默认 > 扩展名/MIME 自动）
-- version   ：文档版本号，参与 MinIO artifact key 的 versions/{version} 段

CREATE TABLE IF NOT EXISTS documents (
    id BIGSERIAL PRIMARY KEY,

    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,

    file_name VARCHAR(512) NOT NULL,
    stored_file_name VARCHAR(512) NOT NULL,
    storage_path TEXT NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size >= 0),
    content_type VARCHAR(255),
    file_ext VARCHAR(32),
    checksum VARCHAR(128),

    parse_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    version INT NOT NULL DEFAULT 1,

    status VARCHAR(32) NOT NULL DEFAULT 'uploaded'
        CHECK (status IN ('uploaded', 'parsing', 'chunking', 'embedding', 'indexed', 'failed')),

    progress INT NOT NULL DEFAULT 0 CHECK (progress >= 0 AND progress <= 100),
    chunk_count INT NOT NULL DEFAULT 0 CHECK (chunk_count >= 0),
    error_message TEXT,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT documents_parse_mode_check CHECK (parse_mode IN ('AUTO', 'ACADEMIC', 'GENERIC')),
    CONSTRAINT documents_version_check CHECK (version >= 1)
);

CREATE INDEX IF NOT EXISTS idx_documents_knowledge_base_id ON documents(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_documents_status ON documents(status);
CREATE INDEX IF NOT EXISTS idx_documents_created_at ON documents(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_documents_parse_mode ON documents(parse_mode);

-- ---------- 3. 文档分块 ----------
-- 学术链路专属字段直接列化（不再塞进 metadata JSONB），与 AcademicChunk 的 chunkId 对齐

CREATE TABLE IF NOT EXISTS document_chunks (
    id BIGSERIAL PRIMARY KEY,

    document_id BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,

    chunk_index INT NOT NULL CHECK (chunk_index >= 0),
    content TEXT NOT NULL,

    page_number INT,
    token_count INT,
    start_offset INT,
    end_offset INT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,

    embedding VECTOR(1024),

    chunk_key TEXT,
    chunk_type VARCHAR(16),
    section_id VARCHAR(255),
    section_title TEXT,
    section_path TEXT[],
    section_level INT,
    embedding_text TEXT,
    content_hash VARCHAR(16),
    end_page INT,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    UNIQUE (document_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_document_chunks_embedding
    ON document_chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

CREATE UNIQUE INDEX IF NOT EXISTS uk_document_chunks_chunk_key
    ON document_chunks (document_id, chunk_key);
CREATE INDEX IF NOT EXISTS idx_document_chunks_chunk_type ON document_chunks (chunk_type);
CREATE INDEX IF NOT EXISTS idx_document_chunks_section_id ON document_chunks (section_id);
CREATE INDEX IF NOT EXISTS idx_document_chunks_section_path
    ON document_chunks USING GIN (section_path);
CREATE INDEX IF NOT EXISTS idx_document_chunks_document_id ON document_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_document_chunks_knowledge_base_id ON document_chunks(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_document_chunks_document_chunk_index
    ON document_chunks(document_id, chunk_index);

-- ---------- 4. 入库任务（含租约与 fencing） ----------
-- lease_expires_at 一律以数据库时间判断过期；
-- fencing_token 每次成功抢占 +1，业务关键写入必须校验 owner + token；
-- 同一 document 同时只允许一个活跃任务（部分唯一索引）。

CREATE TABLE IF NOT EXISTS ingestion_tasks (
    id BIGSERIAL PRIMARY KEY,

    document_id BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,

    status VARCHAR(32) NOT NULL DEFAULT 'pending',

    current_step VARCHAR(64),
    progress INT NOT NULL DEFAULT 0 CHECK (progress >= 0 AND progress <= 100),

    started_at TIMESTAMP,
    finished_at TIMESTAMP,

    error_message TEXT,

    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMP,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP,
    last_error_code VARCHAR(64),

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT ingestion_tasks_status_check
        CHECK (status IN ('pending', 'running', 'retry_wait', 'waiting', 'success', 'failed'))
);

CREATE INDEX IF NOT EXISTS idx_ingestion_tasks_document_id ON ingestion_tasks(document_id);
CREATE INDEX IF NOT EXISTS idx_ingestion_tasks_status ON ingestion_tasks(status);
CREATE INDEX IF NOT EXISTS idx_ingestion_tasks_created_at ON ingestion_tasks(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ingestion_tasks_lease_expires_at ON ingestion_tasks (lease_expires_at);
CREATE INDEX IF NOT EXISTS idx_ingestion_tasks_next_retry_at ON ingestion_tasks (next_retry_at);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ingestion_tasks_active_document
    ON ingestion_tasks (document_id)
    WHERE status IN ('pending', 'running', 'retry_wait', 'waiting');

-- ---------- 5. 会话记忆（L2） ----------

CREATE TABLE IF NOT EXISTS chat_conversations (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_base_id BIGINT,
    model_key VARCHAR(64),
    title VARCHAR(255),
    summary TEXT,
    session_state JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content TEXT NOT NULL,
    token_count INT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation
    ON chat_messages(conversation_id, created_at);

-- ---------- 6. Agent Run Registry（含执行租约） ----------
-- thread_id = 'agent-' + run_id，与 LangGraph4j checkpoint 1:1；
-- request_key 为 HTTP 幂等键（可空，部分唯一索引只约束非空值）。

CREATE TABLE IF NOT EXISTS agent_runs (
    run_id VARCHAR(64) PRIMARY KEY,

    conversation_id VARCHAR(64),
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    principal_id VARCHAR(128),

    request_key VARCHAR(128),
    thread_id VARCHAR(128) NOT NULL UNIQUE,

    status VARCHAR(16) NOT NULL DEFAULT 'CREATED'
        CHECK (status IN ('CREATED', 'RUNNING', 'INTERRUPTED', 'COMPLETED', 'FAILED')),

    question_digest VARCHAR(64),
    memory_committed BOOLEAN NOT NULL DEFAULT FALSE,
    revision INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64),

    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMP,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    generation INT NOT NULL DEFAULT 0,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_runs_request_key
    ON agent_runs (request_key) WHERE request_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_runs_conversation ON agent_runs (conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_runs_status ON agent_runs (status);
CREATE INDEX IF NOT EXISTS idx_agent_runs_kb ON agent_runs (knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_agent_runs_lease ON agent_runs (status, lease_expires_at);

-- ---------- 7. 长期记忆（L3） ----------
-- 槽位身份与内容版本分离：
--   canonical_key = sha256(principal | scope | type | subject | normalize(predicate))
--   content_hash  = sha256(normalize(content))
-- 时间列统一使用 TIMESTAMP（避免 TIMESTAMPTZ 与 LocalDateTime 的映射问题）。

CREATE SEQUENCE IF NOT EXISTS long_term_memory_seq;
CREATE SEQUENCE IF NOT EXISTS memory_extraction_job_seq;

CREATE TABLE IF NOT EXISTS long_term_memories (
    memory_id BIGINT PRIMARY KEY,
    principal_id VARCHAR(128) NOT NULL,
    scope_key VARCHAR(128),
    memory_type VARCHAR(16) NOT NULL,
    subject VARCHAR(256) NOT NULL,
    predicate VARCHAR(128),
    canonical_key VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(64),
    embedding VECTOR(1024),
    embedding_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 1,
    confidence REAL NOT NULL DEFAULT 0.5,
    source_conversation_id VARCHAR(64),
    source_message_id VARCHAR(64),
    source_run_id VARCHAR(64),
    superseded_by BIGINT REFERENCES long_term_memories (memory_id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_ltm_type CHECK (memory_type IN ('FACT', 'PREFERENCE', 'DECISION')),
    CONSTRAINT ck_ltm_status CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'CONFLICT', 'REVOKED')),
    CONSTRAINT ck_ltm_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_ltm_version CHECK (version >= 1)
);

-- 同一 (principal, scope, canonical_key) 最多一条 ACTIVE
CREATE UNIQUE INDEX IF NOT EXISTS uk_ltm_active_slot
    ON long_term_memories (principal_id, COALESCE(scope_key, ''), canonical_key)
    WHERE status = 'ACTIVE';

-- 同一槽位版本号唯一（版本链可追溯）
CREATE UNIQUE INDEX IF NOT EXISTS uk_ltm_version
    ON long_term_memories (principal_id, COALESCE(scope_key, ''), canonical_key, version);

CREATE INDEX IF NOT EXISTS idx_ltm_principal_status ON long_term_memories (principal_id, status);
CREATE INDEX IF NOT EXISTS idx_ltm_principal_type ON long_term_memories (principal_id, memory_type, status);
CREATE INDEX IF NOT EXISTS idx_ltm_source_run ON long_term_memories (source_run_id);
CREATE INDEX IF NOT EXISTS idx_ltm_slot_content
    ON long_term_memories (principal_id, canonical_key, content_hash);
CREATE INDEX IF NOT EXISTS idx_ltm_embedding_status ON long_term_memories (embedding_status);
CREATE INDEX IF NOT EXISTS idx_ltm_embedding
    ON long_term_memories USING ivfflat (embedding vector_cosine_ops);

-- ---------- 8. 记忆提取任务（Outbox） ----------
-- 领取用 FOR UPDATE SKIP LOCKED；成败提交必须带 lease_owner + generation。

CREATE TABLE IF NOT EXISTS memory_extraction_jobs (
    job_id BIGINT PRIMARY KEY,
    principal_id VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    source_message_id VARCHAR(64),
    source_run_id VARCHAR(64),
    idempotency_key VARCHAR(128) NOT NULL,
    extractor_version VARCHAR(32),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(512),
    next_retry_at TIMESTAMP,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMP,
    generation INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_memory_job_idem UNIQUE (idempotency_key),
    CONSTRAINT ck_mjob_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'DEAD')),
    CONSTRAINT ck_mjob_attempts CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS idx_mjob_pick ON memory_extraction_jobs (status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_mjob_conversation ON memory_extraction_jobs (conversation_id);
CREATE INDEX IF NOT EXISTS idx_mjob_lease ON memory_extraction_jobs (status, lease_expires_at);
