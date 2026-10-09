-- =============================================
-- V3：入库解析模式 + 解析版本（决策 D1 / D2）
--   · parse_mode：三级决策（上传参数 > 配置默认 > 扩展名/MIME 自动）的结果，上传时落库
--   · version   ：MinIO artifact key 的 versions/{version} 段；重跑解析时递增
--   与 sql/init.sql 保持一致；全部 ADD COLUMN IF NOT EXISTS（旧数据回填默认值）
-- =============================================

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS parse_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    ADD COLUMN IF NOT EXISTS version    INT         NOT NULL DEFAULT 1;

-- 可选：约束（若已存在同名约束会报错，故用 DO 块做幂等）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'documents_parse_mode_check'
    ) THEN
        ALTER TABLE documents
            ADD CONSTRAINT documents_parse_mode_check
            CHECK (parse_mode IN ('AUTO', 'ACADEMIC', 'GENERIC'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'documents_version_check'
    ) THEN
        ALTER TABLE documents
            ADD CONSTRAINT documents_version_check CHECK (version >= 1);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_documents_parse_mode ON documents (parse_mode);
