-- ============================================================================
-- V10 多设备会话（T4.71 O-2）：同一账号可同时持多个有效令牌，登出只失效当前一个。
-- app_user.token_hash/token_issued_at 保留列但**不再作为鉴权来源**（不 drop；
-- 登录/注册的令牌从本版本起只写 user_session）。
-- 迁移安全：把存量非空令牌搬进新表——升级重启后正在用的会话不掉线。
-- ============================================================================

CREATE TABLE user_session (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_user_session_user ON user_session(user_id);

-- 存量迁移：app_user 里非空 token_hash = 升级前在用的会话（一账号一行，单令牌时代）
INSERT INTO user_session (user_id, token_hash)
SELECT id, token_hash FROM app_user WHERE token_hash IS NOT NULL;
