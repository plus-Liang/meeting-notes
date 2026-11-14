# 交接：录音纪要平台

**更新**: 2026-11-14 11:20｜给谁：下一个会话 / 写代码的 AI（WorkBuddy）
## 1. 下一步
1. **T1.9 `scripts/test.ps1`**（0.5h，卡已写：`tasks-starter.md`）→ 第 2 周 **T2.1 幂等 + D-049 DEAD 重投** → T2.2 → **T2.10 服务端列表 API**。
2. 之后：T3.x 结构化 + 三档 → T4.x 交付（含 **T4.12 死配置体检 / T4.13 真机 UI 验收**）→ 第 5 周 T5.1–T5.7。
## 2. 别踩的坑
1. 对外 JSON 一律 **snake_case**；错误码固定 **7 个**（含 404 `TASK_NOT_FOUND`，只增不改）；查询必带 `user_id`；`GET /tasks/{id}` 已含 `error_code`。
2. **`transcript: ""` = 未识别到语音、任务仍是 DONE**（D-048）；`null` 才是未完成。DEAD 重传暂原样返回（D-049→T2.1）。
3. **禁止手改代码仓 `docs/`（生成副本）** 与 allowlist（只减不增）：T1.6 两条都踩了，见 `review/2026-11-14-T1.6验收.md` F-1/F-2。
4. **可维护性（宪法 VII）**：commit 前 `powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-maintainability.ps1` → exit 0。
5. `ASR_BASE_URL` 不带 `/v1`；**宿主机 `mvn test` 必须注入 `.env` 且覆盖 `ASR_BASE_URL=http://localhost:8001`**（坑 #13）——
   T1.9 就是把这步固化掉；在 `app/` 目录跑（**仓库根没有 pom**）。
6. 镜像（D-038）`REGISTRY_PREFIX` 只管 `library/`；构建期 Maven 走 `MAVEN_MIRROR`（D-052）；连库 `-U mn`；容器内 ffmpeg 8.0.1。
7. 本机坑：`SERVER__PORT` 注入 / curl 先清 6 个 proxy 变量 / `docker run -v D:\...` 卡 `Created` / 中文 `.ps1` 存 UTF-8 **带 BOM**。
8. 不准改测试降断言、不准编数字；别做平台链接抽取（D-027R）；上限 60min/批量 20（D-028）；
   **配置项必须有人用**（D-054）：加键必须同时进 `.env.example`，砍功能必须连带删键。
## 3. 关键文件
`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md`（M-1~M-10）· `spec.md`/`plan.md`/`tasks.md`/`tasks-starter.md` ·
`decisions.md`（**58 条**）· `接入指南.md`（对外契约）· `review/2026-11-14-T1.7-T1.8验收.md`（最新，含 F-1 死配置 / F-2 白盒测试 / F-3 编号纠正）·
`11月14日-AI长期开发复杂项目-总结.md`（治理清单）· `开发提示词模板.md`（14 模板）。
## 4. 未决
T1.9 · T2.1 幂等 + `content_hash` 唯一约束 + DEAD 重投 · T2.10 服务端列表 API（D-053） · 认证 T4.6 · 浏览器/移动端真机 T4.13 ·
干净 clone 跑测试入口（T1.9） · 发布硬门禁 + 回滚（T4.x/T5）· 可维护性技术债 3 条（T2.x 清零）· 18 个"计划中"配置键（T4.12 清零）。
