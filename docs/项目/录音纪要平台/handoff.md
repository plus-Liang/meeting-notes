# 交接：录音纪要平台（更新 2026-11-14 13:20，给下一个会话 / WorkBuddy）

## 1. 下一步

1. **T2.9 本周单测/验收 + 三处收口**（卡在 `tasks-starter.md`）：① 429+`Retry-After` **真 HTTP** 复验（D-061）；② F-1：`app/src/test/resources/application.yml` 加 `app.log-json: false`（测试控制台别再被 JSON 糊住）；③ F-4：`restore.ps1` 加 preflight「postgres/minio 未运行 → **exit 20** + 先 `up -d`」。
2. 之后：第 3 周 T3.x 结构化 + 三档 AI → 第 4 周 T4.x 交付（T4.12 死配置 16 键 / T4.13 真机 UI）→ 第 5 周 T5.1–T5.7。我这边补 README（备份/恢复 + cron 示例、按 taskId 查日志，D-063）。

## 2. 别踩的坑

1. 对外 JSON 一律 **snake_case**；业务码固定 **7 个**（D-047），参数/框架错用**通用码**（D-057）；`transcript: ""` = 未识别到语音但仍 DONE（D-048）。
2. 幂等权威 = **数据库唯一约束**（`ON CONFLICT DO NOTHING` + 判空回查，D-055）；**DEAD 重传 = 复活**（重置 attempts/status/error_code + 重入队 + 删 `dead_letter`）。
3. **兜底 handler 必须敬畏框架语义**（D-057）；**启动期副作用必须尽力而为**（rules/dev.md）；**ASR 超时** connect 5s/read 900s 只给业务调用（D-058），探活 2s（D-059）。
4. 健康检查：四项进 readiness，依赖挂 → **503 DOWN**、容器 ~30s unhealthy 属预期；**不要用环境变量关探活**（启动会失败，D-060）；**并发 v1 = 单线程串行**（D-061）。
5. **日志默认 JSON**（D-062，taskId 是独立字段），`LOG_JSON=false` 回退文本；**备份不含 `.env`**、恢复必须 `-Force`、**恢复前 postgres/minio 必须先起**、自动备份交宿主 cron（D-063/D-064）。
6. **`TranscriptionWorker` 已 290 行**（上限 300）：下一张卡别再塞逻辑，要加先拆类；commit 前 `tools\check-maintainability.ps1` → exit 0。
7. 测试入口 `scripts/test.ps1`：全量需设 `ASR_SMOKE_AUDIO`（未设 → exit 3），`-UnitOnly` 跳过冒烟；**不准改断言/编数字**。本机坑：`SERVER__PORT` 注入 / curl 先清 proxy / `docker run -v D:\...` 卡 `Created` / 连库 `-U mn` / 中文 `.ps1` 必须 UTF-8 带 BOM / `docker compose cp` 的 stderr 进度不是错误。
8. **禁止手改代码仓 `docs/`（生成副本）与 allowlist（只减不增）**：T1.6 已踩；配置项必须有人用（D-054，现 16 个计划中）。

## 3. 关键文件

`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md` · `spec.md`/`plan.md`/`tasks.md`/`tasks-starter.md` · `decisions.md`（**68 条**）· `接入指南.md` · `review/2026-11-14-T2.7-T2.8验收.md`（最新）· `配置清单.md`（§2 第 5 条反查已补 `getProperty`）· `scripts/backup.ps1`/`restore.ps1` · `rules/dev.md`。

## 4. 未决

T2.9 三处收口 · 第 3 周 T3.x · 认证 T4.6 · 真机 UI T4.13 · 发布硬门禁 + 回滚 · 可维护性技术债 3 条 · 死配置 16 键（T4.12）· README 两节（我写）。
