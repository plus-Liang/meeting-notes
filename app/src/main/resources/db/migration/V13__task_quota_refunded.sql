-- T4.87 / 审计 P-31：配额策略显式化——失败自动退还分钟数（次数不退）、删除不退（D-115）、复活重新扣费。
-- 不变量：task.quota_refunded 与 task.status='DEAD' 同真同假（置 DEAD 同事务退款置 TRUE；离开 DEAD 同事务重新消费置 FALSE）。
-- 只加列：不建索引（无按它查询的路径）、不动 quota 表、不做历史回补（老 DEAD 保持 FALSE）。
ALTER TABLE task ADD COLUMN quota_refunded BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN task.quota_refunded IS '与 status=''DEAD'' 同真同假：失败已退还分钟数（T4.87/P-31）';
