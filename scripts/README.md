# scripts/ —— 工程化脚本

- `test.ps1`：统一测试入口（T1.9）。裸 shell 直接跑即注入 `.env` 与 ASR 冒烟地址；
  `-UnitOnly` 跳过两个 `*SmokeTest`。详见文件头注释。
- `backup.ps1`（T2.8 / US-11）：备份 PostgreSQL（`pg_dump -Fc`）+ MinIO 音频对象（volume 打包）+ `manifest.json`
  （5 表行数 / 对象数 / 大小 / sha256），产物在 `backups/<时间戳>/`；`-Keep N` 保留最近 N 份（默认 7）。
  **备份包不含 `.env`**（D-023）；`backups/` 已在 `.gitignore`。
  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup.ps1 [-Keep 7]
  ```
- `restore.ps1`（T2.8 / US-11）：从备份恢复；**不带 `-Force` 拒绝执行**（exit 10）；先停 app 再
  `pg_restore --clean --if-exists` + 恢复 MinIO volume + 按 manifest 逐表自检。
  前提：`postgres` / `minio` 必须先 running（灾难后先 `docker compose up -d`）。
  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore.ps1 -BackupDir backups\<时间戳> -Force
  ```
  退出码：`10` 缺 `-Force`、`11` 备份缺失/为空、`12` DB 恢复失败、`13` 对象恢复失败、`14` 自检不一致。

> 每天自动备份不做内置调度器（D-063）：用宿主 cron / Windows 任务计划调用 `backup.ps1`，
> 且备份目录必须在**宿主**上（放 named volume 会被 `down -v` 一起删）。
