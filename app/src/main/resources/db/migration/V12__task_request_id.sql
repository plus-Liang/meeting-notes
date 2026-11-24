-- ============================================================================
-- V12 任务关联请求标识（T4.86 / 审计 P-26：X-Request-Id 全链路透传）
-- request_id 记录「这条任务是哪次 HTTP 请求建的」，让 worker/webhook 日志能
-- 反查入口请求（值来自 RequestIdFilter 的 MDC；历史行为 NULL）。
-- 可空原因：历史数据没有这个概念、非 HTTP 入口（未来可能的批量/命令行）也无值；
-- 不建索引的原因：查询一律按 id 或 status 过滤，不会按 request_id 检索
-- （排障路径是「从日志 requestId 反查任务」，用 grep 日志而非 SQL）。
-- 只 ADD COLUMN，不动既有列语义。
-- ============================================================================

ALTER TABLE task ADD COLUMN request_id VARCHAR(64);
