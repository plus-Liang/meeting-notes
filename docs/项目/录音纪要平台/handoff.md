# 交接：录音纪要平台

**更新**: 2026-11-13 23:30｜给谁：下一个会话 / 写代码的 AI（WorkBuddy）

## 1. 下一步

1. `docker compose up -d`（5/5 healthy）→ 验收 **T1.5**（`48b4b74`：Redis BLPOP worker + ffmpeg 抽音轨 + ASR + `task_result` + `GET /tasks/{id}` + 契约修复）→ 出 `review/2026-11-14-T1.5验收.md`。
2. 通过后发 T1.6 卡（三页面）→ T1.7 配置自检 → T1.8 上传校验单测。

## 2. 别踩的坑

1. 对外 JSON 一律 **snake_case** + 固定 6 个错误码（D-041），`接入指南.md` 是承诺；按内容/任务查询**必须带 `user_id`**（D-040）。
2. `ASR_BASE_URL` **不带 `/v1`**（容器内 `http://asr:8000`，宿主 `localhost:8001`）；无 key 验证必须**独立探针**，`mv .env` 会假绿灯（D-039）。
3. 镜像（D-038）：`REGISTRY_PREFIX` 只管 `library/`，minio/ollama 用 `MINIO_IMAGE`/`OLLAMA_IMAGE`；启动期外部依赖失败只许 WARN。
4. 连库 `-U mn`；改 SQL 必须在真实 PG16 验（H2 绕过 Flyway）；容器内 ffmpeg 8.0.1，别在宿主装。
5. 本机坑：`SERVER__PORT` 注入 / curl `--noproxy "*"` / `docker run -v D:\...` 卡 `Created` → `docker cp` / 中文 `.ps1` 存 UTF-8 **带 BOM**。
6. 别做平台链接抽取（D-027R）；手机上传 P1（D-029）；上限 60/批量 20（D-028）；硬编码/砍测试/编数字一律不可。

## 3. 关键文件

`00-先看这个.md`（入口）· `docs/协作/开工准备清单.md`（8 步门禁+材料索引）· `架构一页纸.md` · `spec.md` / `plan.md` /
`tasks.md` / `tasks-starter.md` · `decisions.md`（**47 条**）· `接入指南.md`（对外契约）· `docs/协作/开发提示词模板.md`（12 模板）。

## 4. 未决

需求确认单 A 部 14 条 + D 部 4 项待用户确认（不阻塞）· 认证 T4.6 · `content_hash` 唯一约束 T2.1 · 真实 PG Flyway 集成测试（T1.5 后）·
干净 clone `mvn test` 会红（T3.3）· ollama 第二档未真启用（T3.3）· README 首构建时长未写。
