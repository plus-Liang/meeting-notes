-- ============================================================================
-- V8 账号体系（T4.37a）：app_user 表 + 占位管理员（id=1，未认领）
-- 口径见任务卡 T4.37a：不做任何历史数据迁移（task/glossary/speaker_profile/
-- quota/share_link 现有 user_id=1 = 1 号账号 = 管理员）。
-- ============================================================================

CREATE TABLE app_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(32)  NOT NULL,
    -- '!' = 占位（未认领）：1 号账号在首次注册时被认领（写入真实密码哈希 + role=ADMIN）
    password_hash   VARCHAR(200) NOT NULL DEFAULT '!',
    role            VARCHAR(16)  NOT NULL DEFAULT 'USER',
    -- DB 只存 SHA-256 hex（令牌明文只在登录/注册响应出现一次）；同一用户仅一个有效令牌
    token_hash      CHAR(64),
    token_issued_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 用户名大小写不敏感唯一；令牌哈希唯一（仅对非空行，占位管理员/未登录用户不受限）
CREATE UNIQUE INDEX ux_app_user_username ON app_user (lower(username));
CREATE UNIQUE INDEX ux_app_user_token    ON app_user (token_hash) WHERE token_hash IS NOT NULL;

-- 占位管理员：历史 279 条任务归属它；API_TOKEN 在过滤器里等价 1 号账号（管理员座位）
INSERT INTO app_user (id, username, password_hash, role)
VALUES (1, 'admin', '!', 'ADMIN');
SELECT setval(pg_get_serial_sequence('app_user', 'id'), 1, true);
