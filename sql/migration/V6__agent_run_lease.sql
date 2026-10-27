-- =============================================================================
-- Day 2 Step 4E：Agent Run 执行租约（受约束的单执行者 + 崩溃后可接管）
--
-- 手工迁移（与 V2–V5 一致）：
--   docker exec -i ragdemo-postgres psql -U liyong -d ragdemo < sql/migration/V6__agent_run_lease.sql
-- 回滚：
--   ALTER TABLE agent_runs DROP COLUMN IF EXISTS lease_owner, DROP COLUMN IF EXISTS lease_expires_at,
--                          DROP COLUMN IF EXISTS fencing_token, DROP COLUMN IF EXISTS generation;
--   DROP INDEX IF EXISTS idx_agent_runs_lease;
--
-- 语义：
--   * lease_expires_at 由数据库时钟判定过期（不依赖应用机时钟）；
--   * fencing_token 每次成功抢占 +1 ⇒ 过期执行者的迟到写入必须被拒（WHERE lease_owner=? AND fencing_token=?）；
--   * generation 记录"被接管次数"，便于审计/日志；
--   * 本步仍**不**宣称多实例安全：LangGraph4j Saver 无写入所有权，
--     强制点只能放在"每个节点执行前的租约校验"（见 .omo/step4-handoff.md §5.4）。
-- =============================================================================

ALTER TABLE agent_runs
    ADD COLUMN IF NOT EXISTS lease_owner      VARCHAR(128),
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS fencing_token    BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS generation       INT    NOT NULL DEFAULT 0;

-- 便于"过期 RUNNING 可被接管"的扫描/审计
CREATE INDEX IF NOT EXISTS idx_agent_runs_lease
    ON agent_runs (status, lease_expires_at);
