-- V10: L3 记忆槽位身份（Phase 2B）
-- 设计：canonical_key = sha256(principal|scope|type|subject|predicate) ⇒ **逻辑槽位**
--       content_hash  = sha256(normalizedContent) ⇒ 同一槽位下的**内容版本**
--       embedding_status 明确区分"内容已存但向量未就绪"，避免 NULL 向量被当作已索引。
-- 只新增列/索引，不改写任何既有数据；旧行 predicate=NULL、content_hash=NULL、embedding_status='PENDING'。

ALTER TABLE long_term_memories ADD COLUMN IF NOT EXISTS predicate VARCHAR(128);
ALTER TABLE long_term_memories ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE long_term_memories ADD COLUMN IF NOT EXISTS embedding_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

-- 槽位内内容去重/查询辅助（不改变"同槽最多一条 ACTIVE"的既有部分唯一索引）
CREATE INDEX IF NOT EXISTS idx_ltm_slot_content
    ON long_term_memories (principal_id, canonical_key, content_hash);
CREATE INDEX IF NOT EXISTS idx_ltm_embedding_status
    ON long_term_memories (embedding_status);
