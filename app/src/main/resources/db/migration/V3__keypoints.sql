-- T4.17：音频要点（keypoints）——LLM 结构化产物的新一节（编号要点）。
-- 历史/未生成的任务为 NULL，前端与导出按「未生成」同语义处理；列表接口不读该列。
ALTER TABLE task_result ADD COLUMN keypoints jsonb;
