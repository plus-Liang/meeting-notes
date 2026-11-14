# 交接：录音纪要平台

**更新**: 2026-11-14 12:25｜给谁：下一个会话 / 写代码的 AI（WorkBuddy）
## 1. 下一步
1. **T2.2 ASR 超时**（D-058，卡已写：`ASR_CONNECT_TIMEOUT_SEC` 默认 5s + 既有 900s 总超时；目标首次失败 ≤15s、DEAD ≤45s）
   → **T2.4 健康检查四项** → T2.5 结果缓存 → T2.6 配额并发 → T2.7 JSON 日志 → T2.8 备份恢复。
2. 之后：T3.x 结构化 + 三档 → T4.x 交付（含 T4.12 死配置体检 / T4.13 真机 UI）→ 第 5 周 T5.1–T5.7。
## 2. 别踩的坑
1. 对外 JSON 一律 **snake_case**；**业务码固定 7 个**（D-047）；参数/框架类错误用**通用码**
   （`INVALID_PARAM`/`NOT_FOUND`/`METHOD_NOT_ALLOWED`/`UNSUPPORTED_MEDIA_TYPE`，D-057），不许借用 `INVALID_MEDIA`。
2. **`transcript: ""` = 未识别到语音、任务仍是 DONE**（D-048）；`null` 才是未完成。
3. **幂等权威 = 数据库唯一约束**（D-055）：`UNIQUE (user_id, content_hash)` + `ON CONFLICT DO NOTHING` + 判空回查；
   禁止 SELECT-then-INSERT；PG 同事务内约束冲突后事务已 aborted。
4. **DEAD 重传 = 复活**（D-049/D-055）：重置 attempts/status/error_code + 重新入队 + **同时删 `dead_letter`**；响应 `reused:true` + `PENDING`。
5. **兜底 handler 必须尊重框架语义**（D-057）：`ErrorResponse` 按原状态码返回；只有真未知异常才 500 `INTERNAL_ERROR`。
6. **启动期副作用必须尽力而为**（`rules/dev.md`，第 2 次踩）：建桶/回收/预热要 catch 依赖异常 → WARN → 继续启动，不许拖垮 context；
   单测要覆盖"空库/依赖缺失也能起"（`NoKeyStartupTest` 就是这条闸）。
7. **崩溃恢复靠 `StartupRecovery`**（T2.3）：启动时把 `RUNNING` 一律回 `PENDING` 重入队（单实例假设，注释已写）；0 个也打日志。
8. **禁止手改代码仓 `docs/`（生成副本）** 与 allowlist（只减不增）：T1.6 已踩，见 `review/2026-11-14-T1.6验收.md` F-1/F-2。
9. **可维护性（宪法 VII）**：commit 前 `powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-maintainability.ps1` → exit 0（M3：文件 ≤300 行）。
10. 测试入口 `scripts/test.ps1`：自动注入 `.env` + `ASR_BASE_URL`；**全量需设 `ASR_SMOKE_AUDIO`**（未设 → exit 3）；`-UnitOnly` 跳过冒烟。
11. 本机坑：`SERVER__PORT` 注入 / curl 先清 6 个 proxy 变量 / `docker run -v D:\...` 卡 `Created` / `docker compose stop asr` 后 DNS 卡 ~70s；
    镜像 `REGISTRY_PREFIX`/`MAVEN_MIRROR`；连库 `-U mn`；中文 `.ps1` 必须 UTF-8 带 BOM。
12. 不准改测试降断言、不准编数字；上限 60min/批量 20（D-028）；配置项必须有人用（D-054）；`status` 严格校验（D-057）。
## 3. 关键文件
`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md`（M-1~M-10）· `spec.md`/`plan.md`/`tasks.md`/`tasks-starter.md` ·
`decisions.md`（**62 条**）· `接入指南.md`（对外契约：上传/详情/**列表 3.3**/错误码 3.4）· `review/2026-11-14-T2.12-T2.3验收.md`（最新）·
`scripts/test.ps1` · `rules/dev.md`（启动期副作用规则）· `开发提示词模板.md`（14 模板）。
## 4. 未决
T2.2 ASR 超时 · T2.4 健康检查 · T2.5 缓存 · T2.6 配额并发 · T2.7 JSON 日志 · T2.8 备份 · 认证 T4.6 · 真机 UI T4.13 ·
发布硬门禁 + 回滚 · 可维护性技术债 3 条 · 18 个"计划中"配置键（T4.12）。
