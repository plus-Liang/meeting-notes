ALTER TABLE task ADD COLUMN next_retry_at TIMESTAMPTZ;
COMMENT ON COLUMN task.next_retry_at IS '下次可重试时间（status=RETRYING 且未到期时不得入队）';
CREATE INDEX ix_task_retry_due ON task (next_retry_at) WHERE status = 'RETRYING';
