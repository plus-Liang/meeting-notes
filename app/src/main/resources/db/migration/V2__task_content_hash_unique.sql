-- ============================================================================
-- V2: 幂等唯一约束（T2.1 / D-049）
--   幂等的权威从"先 SELECT 再 INSERT"改为数据库唯一约束 (user_id, content_hash)：
--   并发两个同内容请求，只有一个 INSERT 成功，败者回查后返回 reused:true。
--   同时撤销 V1 的全局唯一索引 ux_task_hash——它按 content_hash 全局唯一，
--   会在多用户（D-040/D-053）下挡住"用户 B 上传与用户 A 相同内容"这一合法场景。
-- ============================================================================

-- 1) 先查重：保留每组 (user_id, content_hash) 中最小 id 的任务，删掉重复行
--    （task_result 随 FK ON DELETE CASCADE 一并删；dead_letter 无级联，删前若有残留需人工处理）
DELETE FROM task a USING task b
  WHERE a.user_id = b.user_id
    AND a.content_hash = b.content_hash
    AND a.id > b.id;

-- 2) 撤销 V1 的全局唯一索引（幂等语义收敛到用户维度，见 D-040）
DROP INDEX IF EXISTS ux_task_hash;

-- 3) 新唯一约束：同用户同内容只允许一个任务
CREATE UNIQUE INDEX IF NOT EXISTS ux_task_user_hash ON task (user_id, content_hash);
