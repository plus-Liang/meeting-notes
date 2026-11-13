-- ============================================================================
-- V1 初始化：任务 / 结果 / 闭环回流 / 配额 / 死信
-- 口径见 docs/项目/录音纪要平台/plan.md §6 与 spec.md 各条 N*
-- ============================================================================

CREATE TABLE task (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL DEFAULT 1,
    source_type   VARCHAR(16)  NOT NULL,              -- UPLOAD / FETCH / PASTE / RECORD / INBOX
    source_uri    TEXT,
    content_hash  VARCHAR(64)  NOT NULL,              -- 幂等键（N1）
    duration_sec  INT,
    size_bytes    BIGINT,
    status        VARCHAR(16)  NOT NULL,              -- PENDING/RUNNING/DONE/RETRYING/DEAD
    attempts      INT          NOT NULL DEFAULT 0,
    error_code    VARCHAR(64),
    callback_url  TEXT,                               -- 供他人接入：完成回调（D-019）
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_task_hash    ON task (content_hash);
CREATE INDEX        ix_task_status  ON task (status, updated_at);
CREATE INDEX        ix_task_created ON task (created_at DESC);

CREATE TABLE task_result (
    task_id     BIGINT PRIMARY KEY REFERENCES task (id) ON DELETE CASCADE,
    transcript  TEXT,
    summary     TEXT,
    todos       JSONB,
    keywords    JSONB,
    speaker_map JSONB,
    ai_tier     VARCHAR(16),                          -- CLOUD / LOCAL / EXTRACTIVE（D-020）
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 闭环回流（D-014 / US-3）
CREATE TABLE glossary (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL DEFAULT 1,
    wrong      VARCHAR(128) NOT NULL,
    right      VARCHAR(128) NOT NULL,
    hits       INT          NOT NULL DEFAULT 1,
    priority   INT          NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, wrong)
);

CREATE TABLE speaker_profile (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL DEFAULT 1,
    label      VARCHAR(64) NOT NULL,
    note       TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, label)
);

CREATE TABLE quota (
    user_id      BIGINT NOT NULL,
    day          DATE   NOT NULL,
    used_minutes INT    NOT NULL DEFAULT 0,
    used_tasks   INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, day)
);

CREATE TABLE dead_letter (
    id         BIGSERIAL PRIMARY KEY,
    task_id    BIGINT REFERENCES task (id),
    reason     TEXT,
    payload    JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Webhook 投递记录（D-019：可观测、可重投）
CREATE TABLE webhook_delivery (
    id          BIGSERIAL PRIMARY KEY,
    task_id     BIGINT NOT NULL REFERENCES task (id) ON DELETE CASCADE,
    url         TEXT   NOT NULL,
    event       VARCHAR(32) NOT NULL,                 -- task.done / task.failed
    attempts    INT    NOT NULL DEFAULT 0,
    last_status INT,
    next_retry  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_webhook_due ON webhook_delivery (next_retry) WHERE next_retry IS NOT NULL;
