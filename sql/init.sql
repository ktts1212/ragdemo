-- =============================================
-- RAG Demo 数据库初始化脚本
-- 6 表设计：knowledge_bases / documents / document_chunks / ingestion_tasks / chat_conversations / chat_messages
-- =============================================

DROP TABLE IF EXISTS document_chunks CASCADE;
DROP TABLE IF EXISTS ingestion_tasks CASCADE;
DROP TABLE IF EXISTS chat_messages CASCADE;
DROP TABLE IF EXISTS chat_conversations CASCADE;
DROP TABLE IF EXISTS documents CASCADE;
DROP TABLE IF EXISTS knowledge_bases CASCADE;

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS knowledge_bases (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

INSERT INTO knowledge_bases (id, name, description)
VALUES (1, '默认知识库', '初始默认知识库')
ON CONFLICT (id) DO NOTHING;

SELECT setval('knowledge_bases_id_seq', COALESCE((SELECT MAX(id) FROM knowledge_bases), 1));

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

    -- 入库解析模式（三级决策结果：上传参数 > 配置默认 > 扩展名/MIME 自动）
    parse_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO'
        CHECK (parse_mode IN ('AUTO', 'ACADEMIC', 'GENERIC')),
    -- 解析版本号（MinIO artifact key 的 versions/{version} 段）
    version INT NOT NULL DEFAULT 1 CHECK (version >= 1),

    status VARCHAR(32) NOT NULL DEFAULT 'uploaded'
        CHECK (status IN ('uploaded', 'parsing', 'chunking', 'embedding', 'indexed', 'failed')),

    progress INT NOT NULL DEFAULT 0 CHECK (progress >= 0 AND progress <= 100),
    chunk_count INT NOT NULL DEFAULT 0 CHECK (chunk_count >= 0),
    error_message TEXT,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_documents_knowledge_base_id ON documents(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_documents_status ON documents(status);
CREATE INDEX IF NOT EXISTS idx_documents_created_at ON documents(created_at DESC);

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
    metadata JSONB,

    embedding VECTOR(1024),

    -- 学术链路 chunk metadata（skill §6 混合方案；与 sql/migration/V2__academic_chunk_columns.sql 保持一致）
    chunk_key      TEXT,
    chunk_type     VARCHAR(16),
    section_id     VARCHAR(255),
    section_title  TEXT,
    section_path   TEXT[],
    section_level  INT,
    embedding_text TEXT,
    content_hash   VARCHAR(16),
    end_page       INT,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    UNIQUE (document_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_document_chunks_embedding
ON document_chunks
USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 100);

CREATE UNIQUE INDEX IF NOT EXISTS uk_document_chunks_chunk_key
ON document_chunks (document_id, chunk_key);

CREATE INDEX IF NOT EXISTS idx_document_chunks_chunk_type ON document_chunks (chunk_type);
CREATE INDEX IF NOT EXISTS idx_document_chunks_section_id ON document_chunks (section_id);
CREATE INDEX IF NOT EXISTS idx_document_chunks_section_path
ON document_chunks USING GIN (section_path);

CREATE INDEX IF NOT EXISTS idx_document_chunks_document_id ON document_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_document_chunks_knowledge_base_id ON document_chunks(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_document_chunks_document_chunk_index ON document_chunks(document_id, chunk_index);

CREATE TABLE IF NOT EXISTS ingestion_tasks (
    id BIGSERIAL PRIMARY KEY,

    document_id BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,

    status VARCHAR(32) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'running', 'success', 'failed')),

    current_step VARCHAR(64),
    progress INT NOT NULL DEFAULT 0 CHECK (progress >= 0 AND progress <= 100),

    started_at TIMESTAMP,
    finished_at TIMESTAMP,

    error_message TEXT,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ingestion_tasks_document_id ON ingestion_tasks(document_id);
CREATE INDEX IF NOT EXISTS idx_ingestion_tasks_status ON ingestion_tasks(status);
CREATE INDEX IF NOT EXISTS idx_ingestion_tasks_created_at ON ingestion_tasks(created_at DESC);

-- =============================================
-- 短期记忆：会话与消息表
-- =============================================

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