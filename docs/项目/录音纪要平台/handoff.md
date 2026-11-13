# 交接：录音纪要平台

**更新**: 2026-11-14 00:55｜给谁：下一个会话 / 写代码的 AI（WorkBuddy）
## 1. 下一步
1. **T1.7 配置自检**（`配置清单.md` §2 三条命令，本机实跑）→ **T1.8 上传校验单测 + worker 状态机测试（S-1）**。
2. T3.3 补 `scripts/test.ps1`（固化 `.env` + `ASR_BASE_URL`，做测试分层入口）→ T2.1 幂等（含 D-049 DEAD 重投）→ T4.1 真机验收。
## 2. 别踩的坑
1. 对外 JSON 一律 **snake_case**；错误码固定 **7 个**（含 404 `TASK_NOT_FOUND`，只增不改）；查询必带 `user_id`；`GET /tasks/{id}` 已含 `error_code`。
2. **`transcript: ""` = 未识别到语音、任务仍是 DONE**（D-048）；`null` 才是未完成。DEAD 重传暂原样返回（D-049→T2.1）。
3. **禁止手改代码仓 `docs/`（生成副本）** 与 allowlist（只减不增）：T1.6 两条都踩了，见 `review/2026-11-14-T1.6验收.md` F-1/F-2。
4. **可维护性（宪法 VII）**：commit 前 `powershell -ExecutionPolicy Bypass -File tools\check-maintainability.ps1` → exit 0。
5. `ASR_BASE_URL` 不带 `/v1`；**宿主机 `mvn test` 必须覆盖 `http://localhost:8001`**（坑 #13）。
6. 镜像（D-038）`REGISTRY_PREFIX` 只管 `library/`；构建期 Maven 走 `MAVEN_MIRROR`（D-052）；连库 `-U mn`；容器内 ffmpeg 8.0.1。
7. 本机坑：`SERVER__PORT` 注入 / curl 先清 6 个 proxy 变量 / `docker run -v D:\...` 卡 `Created` / 中文 `.ps1` 存 UTF-8 **带 BOM**。
8. 不准改测试降断言、不准编数字；别做平台链接抽取（D-027R）；上限 60min/批量 20（D-028）。
## 3. 关键文件
`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md`（M-1~M-10）· `spec.md`/`plan.md`/`tasks.md`/`tasks-starter.md` ·
`decisions.md`（**56 条**）· `接入指南.md`（对外契约）· `review/2026-11-14-T1.6验收.md`（最新验收 + 越界记录）·
`11月14日-AI长期开发复杂项目-总结.md`（治理清单）· `开发提示词模板.md`（14 模板）。
## 4. 未决
T1.7/T1.8 · 认证 T4.6 · `content_hash` 唯一约束 + DEAD 重投 T2.1 · worker 状态机单测（S-1）· 浏览器/移动端真机 T4.1 ·
干净 clone `mvn test` 红（T3.3）· 发布硬门禁（T4.x/T5）· 可维护性技术债 3 条（T2.x 清零）。
