# 交接：录音纪要平台

**更新**: 2026-11-14 13:00｜给谁：下一个会话 / 写代码的 AI（WorkBuddy）
## 1. 下一步
1. **T2.7 结构化 JSON 日志**（卡已写：消费 `app.log-json`，Boot 内建结构化日志 + MDC taskId，`LOG_JSON=false` 可回退）
   → **T2.8 备份与恢复**（卡已写：`pg_dump -Fc` + MinIO volume tar、`-Force` 保护、`-Keep`、不含 `.env`）
   → T2.9 本周单测/验收（含 429+`Retry-After` 复验）。
2. 之后：T3.x 结构化 + 三档 → T4.x 交付（含 T4.12 死配置体检 / T4.13 真机 UI）→ 第 5 周 T5.1–T5.7。
## 2. 别踩的坑
1. 对外 JSON 一律 **snake_case**；**业务码固定 7 个**（D-047）；参数/框架类错误用**通用码**（D-057）。
2. **`transcript: ""` = 未识别到语音、任务仍是 DONE**（D-048）；`null` 才是未完成。
3. **幂等权威 = 数据库唯一约束**（D-055）：`ON CONFLICT DO NOTHING` + 判空回查；禁止 SELECT-then-INSERT。
4. **DEAD 重传 = 复活**（D-049/D-055）：重置 attempts/status/error_code + 重入队 + **删 `dead_letter`**。
5. **兜底 handler 必须尊重框架语义**（D-057）；**启动期副作用必须尽力而为**（rules/dev.md）。
6. **ASR 超时**（T2.2/D-058）：connect 5s + read 900s，由 `AsrHttpClientConfig` 统一设；**探活/其它调用不许复用 900s**（D-059 探活 2s）。
7. **健康检查**（T2.4/D-059）：四项进 readiness；依赖挂 → `/actuator/health` **503 DOWN**、容器 ~30s 后 unhealthy（预期，不重启）。
   不要用环境变量关探活——`readiness.include` 的组员校验会让应用启动失败（D-060）。
8. **并发 = 单线程串行**（D-061）：不要引入多 worker；`app.asr.max-concurrency` 是 P2 计划中配置。
9. **备份/恢复**（T2.8）：备份**不含 `.env`**（D-023）；`restore` 必须 `-Force` 才执行。
10. **禁止手改代码仓 `docs/`（生成副本）** 与 allowlist（只减不增）：T1.6 已踩。
11. **可维护性（宪法 VII）**：commit 前 `powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-maintainability.ps1` → exit 0（文件 ≤300 行）。
12. 测试入口 `scripts/test.ps1`：自动注入 `.env` + `ASR_BASE_URL`；**全量需设 `ASR_SMOKE_AUDIO`**（未设 → exit 3）；`-UnitOnly` 跳过冒烟。
13. 本机坑：`SERVER__PORT` 注入 / curl 先清 6 个 proxy 变量 / `docker run -v D:\...` 卡 `Created` / 镜像 `REGISTRY_PREFIX`/`MAVEN_MIRROR` /
    连库 `-U mn` / 中文 `.ps1` 必须 UTF-8 带 BOM。
14. 不准改测试降断言、不准编数字；上限 60min/批量 20（D-028）；配置项必须有人用（D-054）。
## 3. 关键文件
`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md` · `spec.md`/`plan.md`/`tasks.md`/`tasks-starter.md` ·
`decisions.md`（**65 条**）· `接入指南.md`（上传/详情/列表/错误码 + ASR 时延语义）· `review/2026-11-14-T2.4验收.md`（最新）·
`scripts/test.ps1` · `rules/dev.md` · `配置清单.md`（§2 五条机器判据，快照 35 键）。
## 4. 未决
T2.7 JSON 日志 · T2.8 备份恢复 · T2.9 本周验收 · T3.x 结构化/三档 · 认证 T4.6 · 真机 UI T4.13 ·
发布硬门禁 + 回滚 · 可维护性技术债 3 条 · 17 个"计划中"配置键（T4.12）。
