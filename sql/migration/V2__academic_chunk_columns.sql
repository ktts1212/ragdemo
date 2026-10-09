-- =============================================
-- V2：学术链路 chunk metadata（skill §6 混合方案）
--   高频过滤字段 → 专列；长尾 → document_chunks.metadata (JSONB)
-- 说明：
--   · 全部 ADD COLUMN IF NOT EXISTS —— 对旧通用链路零影响（新列为 NULL）
--   · 同步已更新 sql/init.sql，保证「全新初始化」与「增量迁移」结果一致
--   · 应用方式：psql -f sql/migration/V2__academic_chunk_columns.sql
-- =============================================

ALTER TABLE document_chunks
    ADD COLUMN IF NOT EXISTS chunk_key      TEXT,
    ADD COLUMN IF NOT EXISTS chunk_type     VARCHAR(16),
    ADD COLUMN IF NOT EXISTS section_id     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS section_title  TEXT,
    ADD COLUMN IF NOT EXISTS section_path   TEXT[],
    ADD COLUMN IF NOT EXISTS section_level  INT,
    ADD COLUMN IF NOT EXISTS embedding_text TEXT,
    ADD COLUMN IF NOT EXISTS content_hash   VARCHAR(16),
    ADD COLUMN IF NOT EXISTS end_page       INT;

-- chunk_key 稳定可复现（= AcademicChunk.chunkId），与 document_id 组成唯一键；
-- 即使 v1 仍采用 delete + reinsert，也先建立约束（skill §6.2）
CREATE UNIQUE INDEX IF NOT EXISTS uk_document_chunks_chunk_key
    ON document_chunks (document_id, chunk_key);

-- 高频过滤/降权字段
CREATE INDEX IF NOT EXISTS idx_document_chunks_chunk_type
    ON document_chunks (chunk_type);
CREATE INDEX IF NOT EXISTS idx_document_chunks_section_id
    ON document_chunks (section_id);

-- section-aware 过滤 / facet / rerank 用（skill §6.1）
CREATE INDEX IF NOT EXISTS idx_document_chunks_section_path
    ON document_chunks USING GIN (section_path);
