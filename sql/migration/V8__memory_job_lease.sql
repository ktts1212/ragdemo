-- V8: Phase 2A —— Outbox 领取令牌与主键序列（只新增列/索引/序列，不改既有数据）
-- 设计要点：领取必须可撤销（lease_owner + generation），过期 Worker 的完成/失败提交用
-- (lease_owner, generation) 条件校验，防止覆盖新 Worker 的状态。

CREATE SEQUENCE IF NOT EXISTS memory_extraction_job_seq;
CREATE SEQUENCE IF NOT EXISTS long_term_memory_seq;

ALTER TABLE memory_extraction_jobs ADD COLUMN IF NOT EXISTS lease_owner      VARCHAR(128);
ALTER TABLE memory_extraction_jobs ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ;
ALTER TABLE memory_extraction_jobs ADD COLUMN IF NOT EXISTS generation       INT NOT NULL DEFAULT 0;
ALTER TABLE memory_extraction_jobs ADD COLUMN IF NOT EXISTS extractor_version VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_mjob_lease ON memory_extraction_jobs (status, lease_expires_at);
CREATE INDEX IF NOT EXISTS idx_mjob_idem_prefix ON memory_extraction_jobs (idempotency_key);
