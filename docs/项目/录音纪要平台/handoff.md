# 交接：录音纪要平台

**更新**: 2026-11-13 20:10｜给谁：下一个会话 / 写代码的 AI（WorkBuddy）

## 1. 下一步

1. **T1.5 开工前先修 3 个遗留**（一个 commit）：①对外 JSON 改 snake_case + 错误码 `SIZE_EXCEEDED`/`DURATION_EXCEEDED`（D-041）；
   ②幂等命中返回库里**真实** status（现在硬编码 PENDING）；③`findIdByHash` 加 `AND user_id = ?`（D-040，防认证上线后越权）。
2. **T1.5**：Redis 阻塞队列 worker → ffmpeg 抽音轨 → 容器内 `http://asr:8000` → 写 `task_result` → DONE；补 `GET /api/v1/tasks/{id}`。

## 2. 别踩的坑

1. **对外 JSON 一律 snake_case**（`task_id`/`content_hash`/`reused`），错误码固定 6 个（D-041）——`接入指南.md` 是承诺。
2. **`ASR_BASE_URL` 不带 `/v1`**；容器内是 `http://asr:8000`，宿主验证才用 `localhost:8001`（D-034）。
3. **无 key 可启动**靠 EPP + `spring.ai.model.chat=none`（D-035/D-036）。验证必须用**独立探针**（D-039），`mv .env` 会假绿灯。
4. 启动期任何"外部依赖尽力确认"（如 MinIO bucket）**失败只能 WARN**，不能阻断启动——T1.4 的 `ensureBucket` 差点破坏 D-035。
5. **镜像来源**（D-038）：`REGISTRY_PREFIX` 只管 `library/` 镜像；minio/ollama 用 `MINIO_IMAGE`/`OLLAMA_IMAGE`。
6. **连库 `-U mn`**；`down -v` 后 `up -d` 必须 5/5 healthy；改 SQL 必须在真实 PG16 验（H2 绕过 Flyway）。
7. 容器内已有 **ffmpeg/ffprobe 8.0.1**，别在宿主装（SAC 拦未签名二进制）；抽音轨用 `-vn -ac 1 -ar 16k`。
8. 本机坑：宿主注入 `SERVER__PORT`；curl 要 `--noproxy "*"`；`docker run -v D:\...` 会卡 `Created` → 用 `docker cp`；含中文的 `.ps1` 必须 UTF-8 **带 BOM**。
9. 别做平台链接抽取（D-027R）；手机上传 P1（D-029）；上限 60min / 批量 20（D-028）；硬编码/砍测试/编数字一律不可。

## 3. 关键文件

详见 `00-先看这个.md`。要点：`spec.md`（WHAT）· `plan.md`（方案）· `tasks.md` / `tasks-starter.md`（执行）·
`decisions.md`（**46 条**）· `接入指南.md`（对外 API 契约，改动必须同步）· `docs/协作/review/`（4 份验收报告）。
**协作基线（D-042）**：`docs/协作/开发提示词模板.md`（三准备+四规则 7 模板）+ `docs/协作/11月13日-音频总结.md`（出处）。
代码仓 `D:\new\projects\meeting-notes`：`AGENT.md`（入口 + 已验证事实 §4.1）· `docker-compose.yml` · `app/` · `infra/asr/`。

## 4. 未决

- 认证（`API_TOKEN` → 401）留到 **T4.6**（D-040）；在此之前单用户 `user_id=1`，但查询必须带 `user_id`。
- `content_hash` 唯一约束 + 并发双插 → **T2.1**；配额在创建时消费、任务最终 DEAD 不回退（v1 语义，T3.x 定案）。
- 干净 clone 跑 `mvn test` 会红（`LlmSmokeTest`/`AsrSmokeTest` 依赖真实 key 与服务）→ T3.3 前处理。
- `ollama` 第二档只验证了镜像变量解析，T3.3 真启用；真实 PG 的 Flyway 集成测试仍未做。
