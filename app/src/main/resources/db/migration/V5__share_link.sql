-- V5（T4.28 / D-136）：只读分享链接
-- token 明文永不落库：DB 只存 SHA256 hex（varchar(64)）；失效手段 = 撤销（revoked_at）或过期（expires_at）。
-- task 删除级联删链接（on delete cascade），不留孤儿。
CREATE TABLE share_link (
    id         BIGSERIAL PRIMARY KEY,
    task_id    BIGINT       NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    user_id    BIGINT       NOT NULL,
    token_hash VARCHAR(64)  NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_share_link_task  ON share_link (task_id);
CREATE INDEX ix_share_link_token ON share_link (token_hash);
