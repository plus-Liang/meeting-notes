# 交接：录音纪要平台

**更新**: 2026-11-13 19:00｜给谁：下一个会话 / 写代码的 AI（WorkBuddy）

## 1. 下一步

1. **T1.3 之前**：决定 `glossary."right"` 是否改名 `right_text`（审查 P2-1：现在改免费，以后改要写迁移）。
2. **T1.1 → T1.8**：骨架收尾 → `docker compose up` 全套一键起 → Flyway 生效（**必须真实 PG 验**）→ 上传接口 → worker → 三页面 → 配置自检 → 单测。

## 2. 别踩的坑

1. **`ASR_BASE_URL` 不带 `/v1`**（`http://asr:8000`）；Spring AI 自己追加 `/v1/audio/transcriptions`（D-034）。
2. 转写前缀 `spring.ai.openai.audio.transcription.*`；`response-format` 必须 `json`。
3. **云 LLM**：DeepSeek `base-url=https://api.deepseek.com` + `LLM_CHAT_COMPLETIONS_PATH=/v1/chat/completions` + `deepseek-flash`；thinking 模型 `LLM_MAX_TOKENS` 太小会返回空 content。换智谱要把 path 改 `/chat/completions`。
4. **无 key 可启动**靠 `LlmModelSwitchEnvironmentPostProcessor` + `spring.ai.model.chat=none`（D-035/D-036）——**不许**用填假 base-url 或 exclude 自动配置类。
5. `glossary."right"` 是 PG 保留字必须加引号；将来写 JPA 实体要 `@Column(name="\"right\"")`。
6. **H2 测试绕过 Flyway**（`NoKeyStartupTest`）→ 改 SQL 必须手工在真实 PG16 跑一遍。
7. 第三方镜像要 `REGISTRY_PREFIX=docker.m.daocloud.io/`；`git push` 偶发 SSL 握手失败 → 重试 2–3 次（或 `git -c http.sslBackend=openssl push`）。
8. 本机环境坑：宿主注入 `SERVER__PORT` → `--server.port=18080` 覆盖；curl 本机要 `--noproxy "*"`；`docker run -v D:\...:/mig:ro` 偶发容器卡 `Created` → 用 `docker cp`。
9. 别做平台页面链接抽取（D-027R）；手机上传 P1（D-029）；上限 60min / 批量 20（D-028）；硬编码 / 砍测试 / 编数字一律不可。

## 3. 关键文件

详见 `00-先看这个.md`。要点：`spec.md`（WHAT）· `plan.md`（技术方案）· `tasks.md` / `tasks-starter.md`（执行）·
`decisions.md`（40 条，带 R 为修订条）· `T0.1-ASR验证结果.md` · `测试语料-清单.md` · `docs/协作/review/`（T0.4、T0.5 验收报告）。
代码仓 `D:\new\projects\meeting-notes`：`AGENT.md`（入口 + 已验证事实 §4.1）· `infra/asr/` · `tools/save.ps1`。

## 4. 未决

- `glossary."right"` 是否改名（建议改，趁 V1 尚未在任何环境跑）。
- `mvn test` 里 `LlmSmokeTest` / `AsrSmokeTest` 依赖真实 key 与服务 → 干净 clone 会红，T3.3 前处理。
- 完整 `docker compose up` 一键启动（T1.2）尚未实跑；容器内 `http://asr:8000` 也尚未实跑。
- `small` 模型质量是否够用；要不要补全 medium/large-v3 模型缓存。
