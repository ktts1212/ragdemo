-- V9: 与项目既有约定对齐（其它表使用 TIMESTAMP 而非 TIMESTAMPTZ），
-- 避免 MyBatis 映射 LocalDateTime 时报 "Cannot convert TIMESTAMPTZ to LocalDateTime"。
-- 只调整本阶段新增的两张表，不触碰任何既有表。

ALTER TABLE memory_extraction_jobs
    ALTER COLUMN created_at       TYPE TIMESTAMP USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at       TYPE TIMESTAMP USING updated_at AT TIME ZONE 'UTC',
    ALTER COLUMN next_retry_at    TYPE TIMESTAMP USING next_retry_at AT TIME ZONE 'UTC',
    ALTER COLUMN lease_expires_at TYPE TIMESTAMP USING lease_expires_at AT TIME ZONE 'UTC';

ALTER TABLE long_term_memories
    ALTER COLUMN created_at TYPE TIMESTAMP USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMP USING updated_at AT TIME ZONE 'UTC';
