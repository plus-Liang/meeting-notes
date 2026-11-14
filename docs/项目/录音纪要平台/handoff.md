# 交接：录音纪要平台（更新 2026-11-14 13:20，给下一个会话 / WorkBuddy）

## 1. 下一步

1. **T3.0 W3 前置小修**（卡在 `tasks-starter.md`，W2 关账条件）：①`scripts/test.ps1` 注入 `.env` 时**跳过 `ASR_BASE_URL`**（容器地址进不了宿主机）②加 `MAVEN_OPTS=-Dfile.encoding=UTF-8`（中文日志乱码）③compose `TZ`（配额日切，D-067，需人类点头）。
2. 然后 **T3.1 摘要生成**（云 `ChatModel` → `summary`/`keywords`/`ai_tier`，卡已写）→ T3.2 待办 JSON → T3.3 三档 → T3.4 抽取式 → T3.5/T3.6 回流 → T3.7/T3.9/T3.10 → T3.8 周验收 → 第 4 周 T4.x 交付 → 第 5 周。我这边补 README（备份/恢复 + cron 示例、按 taskId 查日志，D-063）已补完。

## 2. 别踩的坑

1. 对外 JSON 一律 **snake_case**；业务码固定 **7 个**（D-047），参数/框架错用**通用码**（D-057）；`transcript: ""` = 未识别到语音但仍 DONE（D-048）。
2. 幂等权威 = **数据库唯一约束**（`ON CONFLICT DO NOTHING` + 判空回查，D-055）；**DEAD 重传 = 复活**（重置 attempts/status/error_code + 重入队 + 删 `dead_letter`）。
3. **兜底 handler 必须敬畏框架语义**（D-057）；**启动期副作用必须尽力而为**（rules/dev.md）；**ASR 超时** connect 5s/read 900s 只给业务调用（D-058），探活 2s（D-059）。
4. 健康检查：四项进 readiness，依赖挂 → **503 DOWN**、容器 ~30s unhealthy 属预期；**不要用环境变量关探活**（启动会失败，D-060）；**并发 v1 = 单线程串行**（D-061）。
5. **日志默认 JSON**（D-062，taskId 是独立字段），`LOG_JSON=false` 回退文本；**备份不含 `.env`**、恢复必须 `-Force`、**恢复前 postgres/minio 必须先起**、自动备份交宿主 cron（D-063/D-064）。
6. **`TranscriptionWorker` 已 290 行**（上限 300）：下一张卡别再塞逻辑，要加先拆类；commit 前 `tools\check-maintainability.ps1` → exit 0。
7. 测试入口 `scripts/test.ps1`：全量需设 `ASR_SMOKE_AUDIO`（未设 → exit 3），`-UnitOnly` 跳过冒烟；**不准改断言/编数字**。**坑**：脚本会把 `.env` 的 `ASR_BASE_URL=http://asr:8000`（容器内地址）注入测试进程 → 宿主机解析不了 → 冒烟必红（D-066，T3.0 修）；本机坑：`SERVER__PORT` 注入 / curl 先清 proxy / `docker run -v D:\...` 卡 `Created` / 连库 `-U mn` / 中文 `.ps1` 必须 UTF-8 带 BOM / `docker compose cp` 的 stderr 进度不是错误。
8. **禁止手改代码仓 `docs/`（生成副本）与 allowlist（只减不增）**：T1.6 已踩；配置项必须有人用（D-054，现 16 个计划中）。

## 3. 关键文件

`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md` · `spec.md`/`plan.md`/`tasks.md`/`tasks-starter.md` · `decisions.md`（**71 条**，最新 D-067 配额时区）· `接入指南.md` · `review/2026-11-14-T2.9验收.md`（最新）· `配置清单.md`（§2 第 5 条反查已补 `getProperty`）· `scripts/backup.ps1`/`restore.ps1` · `rules/dev.md`。

## 4. 未决

T3.0 三处小修 · T3.1 摘要生成起 W3 · 认证 T4.6 · 真机 UI T4.13 · 发布硬门禁 + 回滚 · 可维护性技术债 2 条（M5 反向依赖）· 死配置 16 键（T4.12）· 跨机器恢复/损坏备份/磁盘满未验（T2.8 §6）。
