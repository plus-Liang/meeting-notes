# 交接：录音纪要平台

**更新**: 2026-11-13 22:20｜给谁：下一个会话 / 写代码的 AI（WorkBuddy）
## 1. 下一步
1. **发 T1.6 卡**（`docs/项目/录音纪要平台/tasks-starter.md` 的 T1.6 段）：三页面静态 HTML + 原生 JS，
   **前置 D-048**（空文本 → `DONE` + 空 `transcript`，带 1 条单测）。一卡一 commit。
2. 验收 T1.6 后 → T1.7 配置自检 → T1.8 上传校验单测 + worker 状态机测试（S-1）→ T3.3 补 `scripts/test.ps1`。
## 2. 别踩的坑
1. 对外 JSON 一律 **snake_case**；错误码固定 **7 个**（D-041+D-047，含 404 `TASK_NOT_FOUND`，只增不改）；查询必带 `user_id`（D-040）。
2. **`transcript: ""` = 未识别到语音、任务仍是 DONE**（D-048），别当失败；`null` 才是未完成。DEAD 重传暂原样返回 DEAD（D-049→T2.1）。
3. **重试语义归 worker**：Spring AI 内部重试已收敛 2 次（`AI_RETRY_*`，D-046）；`error_code` 只放稳定码，原因进 `dead_letter.reason`。
4. **可维护性（宪法 VII）**：commit 前 `powershell -ExecutionPolicy Bypass -File tools\check-maintainability.ps1` → exit 0；allowlist 只减不增。
5. `ASR_BASE_URL` **不带 `/v1`**（容器 `http://asr:8000`）；**宿主机跑 `mvn test` 必须覆盖 `http://localhost:8001`**，否则 1 error（坑 #13）。
6. 镜像（D-038）`REGISTRY_PREFIX` 只管 `library/`；连库 `-U mn`；改 SQL 必须真实 PG16 验；容器内 ffmpeg 8.0.1。
7. 本机坑：`SERVER__PORT` 注入 / curl 代理（先清 `http_proxy` 等 6 个变量 + `--noproxy "*"`）/ `docker run -v D:\...` 卡 `Created` / 中文 `.ps1` 存 UTF-8 **带 BOM**。
8. 别做平台链接抽取（D-027R）；手机上传 P1（D-029）；上限 60min/批量 20（D-028）；不准改测试降断言、不准编数字。
## 3. 关键文件
`00-先看这个.md`（入口）· `docs/协作/开工准备清单.md`（G1–G8 + 材料索引）· `架构一页纸.md` · `可维护性要求.md`（M-1~M-10）·
`spec.md` / `plan.md` / `tasks.md` / `tasks-starter.md`（下一张卡）· `decisions.md`（**53 条**）· `接入指南.md`（对外契约）·
`review/2026-11-13-T1.5验收.md`（最新验收标准与遗留项）· `docs/协作/开发提示词模板.md`（12 模板）。
## 4. 未决
T1.6 页面（含 D-048）· 认证 T4.6 · `content_hash` 唯一约束 T2.1（含 D-049 重投）· worker 单测 S-1 · 干净 clone `mvn test` 红（T3.3 的 `scripts/test.ps1`）·
ollama 第二档未真启用（T3.3）· README 首构建时长未写 · 可维护性技术债 4 条（T2.x 清零）。
