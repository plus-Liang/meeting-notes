-- ============================================================================
-- V11 登录失败计数与账号锁定（T4.81 / 审计 P-29 后半）
-- 计数与锁定时点落库（不落内存）：重启/多实例都保持一致，避免「重启即解锁」绕过。
-- 按主键更新，不加索引。
-- ============================================================================

ALTER TABLE app_user ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN locked_until TIMESTAMPTZ;
