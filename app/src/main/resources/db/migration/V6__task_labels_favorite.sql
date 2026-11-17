-- V6（T4.29 / D-137）：任务标签与收藏
-- labels 用 jsonb 数组（禁逗号拼字符串）；旧任务迁移后默认 [] / false，无需回填。
ALTER TABLE task ADD COLUMN labels   jsonb   NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE task ADD COLUMN favorite boolean NOT NULL DEFAULT false;

CREATE INDEX ix_task_favorite ON task (user_id, favorite) WHERE favorite;
