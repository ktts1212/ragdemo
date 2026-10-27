-- =====================================================================
-- Step 5 / Phase B：ingestion_tasks 的任务租约、原子抢占与 fencing
-- ---------------------------------------------------------------------
-- 设计要点：
--   * 全部为**新增列/索引**，不改写既有列，不动既有数据 ⇒ 可重复执行、可回滚。
--   * lease_expires_at 一律用**数据库时钟**（NOW() + ttl）判断过期，避免多机时钟漂移。
--   * fencing_token 单调递增：每次成功抢占 +1；业务关键写入（replaceChunks）在
--     同一事务内校验 owner+token，过期 Worker 无法提交。
--   * status 枚举需要新增 retry_wait（有限重试等待）与 waiting（等待人工确认），
--     因此**放宽**原有 CHECK；既有行仍满足新约束（宽化，非破坏）。
--   * 同一 document 同时只允许一个活跃任务（partial unique index）：
--     防止不同 taskId 对同一文档并发 delete+insert 造成损坏。
--
-- 应用方式：手工执行（与 V2/V3 一致），不由应用启动自动执行 DDL。
-- 回滚：
--   DROP INDEX IF EXISTS uk_ingestion_tasks_active_document;
--   ALTER TABLE ingestion_tasks DROP CONSTRAINT IF EXISTS ingestion_tasks_status_check;
--   ALTER TABLE ingestion_tasks ADD CONSTRAINT ingestion_tasks_status_check
--     CHECK (status IN ('pending','running','success','failed'));
--   ALTER TABLE ingestion_tasks
--     DROP COLUMN IF EXISTS lease_owner, DROP COLUMN IF EXISTS lease_expires_at,
--     DROP COLUMN IF EXISTS fencing_token, DROP COLUMN IF EXISTS attempt_count,
--     DROP COLUMN IF EXISTS next_retry_at, DROP COLUMN IF EXISTS last_error_code;
-- =====================================================================

ALTER TABLE ingestion_tasks ADD COLUMN IF NOT EXISTS lease_owner      VARCHAR(128);
ALTER TABLE ingestion_tasks ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP;
ALTER TABLE ingestion_tasks ADD COLUMN IF NOT EXISTS fencing_token    BIGINT  NOT NULL DEFAULT 0;
ALTER TABLE ingestion_tasks ADD COLUMN IF NOT EXISTS attempt_count    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ingestion_tasks ADD COLUMN IF NOT EXISTS next_retry_at    TIMESTAMP;
ALTER TABLE ingestion_tasks ADD COLUMN IF NOT EXISTS last_error_code  VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_ingestion_tasks_lease_expires_at
    ON ingestion_tasks (lease_expires_at);
CREATE INDEX IF NOT EXISTS idx_ingestion_tasks_next_retry_at
    ON ingestion_tasks (next_retry_at);

-- 状态枚举宽化：加 retry_wait / waiting（不改既有语义）
DO $$
DECLARE
    cname TEXT;
BEGIN
    SELECT conname INTO cname
      FROM pg_constraint
     WHERE conrelid = 'ingestion_tasks'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) ILIKE '%status%';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE ingestion_tasks DROP CONSTRAINT %I', cname);
    END IF;
END $$;

ALTER TABLE ingestion_tasks ADD CONSTRAINT ingestion_tasks_status_check
    CHECK (status IN ('pending', 'running', 'retry_wait', 'waiting', 'success', 'failed'));

-- 同一 document 只允许一个活跃任务（防不同 taskId 并发替换 chunk）
CREATE UNIQUE INDEX IF NOT EXISTS uk_ingestion_tasks_active_document
    ON ingestion_tasks (document_id)
    WHERE status IN ('pending', 'running', 'retry_wait', 'waiting');
