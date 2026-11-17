-- T4.35a：task_result 增句子级时间戳 segments（jsonb，可空、不建索引）。
-- 语义（卡面逐字）：新音频任务由 worker 与 transcript 同一语句写入；旧任务 / PASTE 无音频任务
-- 恒为 NULL（不是 []）；空音频（有响应无内容）才是 []。不改既有列，码表/键表只增。
ALTER TABLE task_result ADD COLUMN segments jsonb;
