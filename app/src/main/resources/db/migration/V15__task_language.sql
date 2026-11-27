ALTER TABLE task ADD COLUMN language VARCHAR(8);
COMMENT ON COLUMN task.language IS 'per-task ASR 语言码（zh/en/ja… 或 auto）；NULL = 用部署级 ASR_LANGUAGE';
