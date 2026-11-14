# 交接：录音纪要平台

**更新**: 2026-11-14 11:45｜给谁：下一个会话 / 写代码的 AI（WorkBuddy）
## 1. 下一步
1. **T2.11 契约与死信收口**（0.5h，卡已写：`tasks-starter.md`；含 T2.1 验收发现的 F-1/F-2）→ **T2.10 服务端列表 API（D-053）**
   → T2.2 重试/超时/死信（含 `app.asr.timeout-sec` 落地，F-3）→ T2.3 崩溃恢复。
2. 之后：T2.8 备份恢复 → T3.x 结构化 + 三档 → T4.x 交付（含 T4.12 死配置体检 / T4.13 真机 UI）→ 第 5 周 T5.1–T5.7。
## 2. 别踩的坑
1. 对外 JSON 一律 **snake_case**；错误码固定 **7 个**（含 404 `TASK_NOT_FOUND`，只增不改）；查询必带 `user_id`。
2. **`transcript: ""` = 未识别到语音、任务仍是 DONE**（D-048）；`null` 才是未完成。
3. **幂等权威 = 数据库唯一约束**（D-055）：`UNIQUE (user_id, content_hash)`，用 `ON CONFLICT DO NOTHING` + 判空回查，
   **禁止 SELECT-then-INSERT**；PG 里同事务内约束冲突后事务即 aborted，别再写"catch `DuplicateKeyException` 后回查"。
4. **DEAD 重传 = 复活**（D-049/D-055）：重置 `attempts=0/status=PENDING/error_code=NULL` 并重新入队，响应 `reused:true` + `PENDING`；
   复活时**必须同时删掉该任务的 `dead_letter` 行**（T2.11 修 F-1）。
5. **禁止手改代码仓 `docs/`（生成副本）** 与 allowlist（只减不增）：T1.6 两条都踩了，见 `review/2026-11-14-T1.6验收.md` F-1/F-2。
6. **可维护性（宪法 VII）**：commit 前 `powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-maintainability.ps1` → exit 0。
7. 测试**统一入口** `scripts/test.ps1`（T1.9）：自觉注入 `.env` + `ASR_BASE_URL=http://localhost:8001`、`-UnitOnly` 跳冒烟；
   在 `app/` 目录跑（**仓库根没有 pom**）；中文 `.ps1` 必须 UTF-8 **带 BOM**。
8. 本机坑：`SERVER__PORT` 注入 / curl 先清 6 个 proxy 变量 / `docker run -v D:\...` 卡 `Created` / `docker compose stop asr` 后
   DNS 解析可能卡 ~70s（失败发现慢，T2.2 处理）；镜像走 `REGISTRY_PREFIX`/`MAVEN_MIRROR`（D-038/D-052）；连库 `-U mn`。
9. 不准改测试降断言、不准编数字；上限 60min/批量 20（D-028）；**配置项必须有人用**（D-054），加键进 `.env.example`、砍功能连带删键。
## 3. 关键文件
`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md`（M-1~M-10）· `spec.md`/`plan.md`/`tasks.md`/`tasks-starter.md` ·
`decisions.md`（**60 条**）· `接入指南.md`（对外契约）· `review/2026-11-14-T1.9-T2.1验收.md`（最新，含 F-1~F-5）· `scripts/test.ps1` ·
`11月14日-AI长期开发复杂项目-总结.md`（治理清单）· `开发提示词模板.md`（14 模板）。
## 4. 未决
T2.11 · T2.10 列表 API（D-053） · T2.2 重试/超时（F-3）· T2.3 崩溃恢复 · T2.8 备份 · 认证 T4.6 · 真机 UI T4.13 ·
发布硬门禁 + 回滚（T4.x/T5）· 可维护性技术债 3 条（T2.x 清零）· 18 个"计划中"配置键（T4.12 清零）。
