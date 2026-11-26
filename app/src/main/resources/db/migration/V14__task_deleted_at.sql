-- T4.96/C1（v0.2 回收站，D-245）：软删除列。
-- 非空 = 任务在回收站：保留期内可恢复（restore 清空该列），到期由 TaskTrashSweeper 物理删除。
-- 只加列加索引，不删不改既有列、不搬迁数据；回滚 = 部署上一版镜像（列保留、旧代码忽略即等价原行为）。
ALTER TABLE task ADD COLUMN deleted_at TIMESTAMPTZ;
CREATE INDEX ix_task_deleted ON task (user_id, deleted_at);
COMMENT ON COLUMN task.deleted_at IS '非空 = 在回收站；保留期内可恢复，到期物理删除';
