-- V9（T4.43 / D-160）：任务展示名（用户可重命名）
-- 可空、无默认、不回填：旧任务 display_name 保持 NULL（前端回退清洗文件名展示，NULL ≠ ''）。
-- 只加展示列，不动 source_uri 与溯源（原文件名不改、不丢）。
ALTER TABLE task ADD COLUMN display_name VARCHAR(120);
