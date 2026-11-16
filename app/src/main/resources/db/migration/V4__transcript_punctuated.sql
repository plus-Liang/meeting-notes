-- T4.18：转写 AI 加标点（一句一行展示的数据源）。
-- null = 未生成（LLM 不可用 / 超长 / 失败 / 历史任务），前端回退原始转写；原始 transcript 列不动。
ALTER TABLE task_result ADD COLUMN transcript_punctuated text;
