# 交接：录音纪要平台

**更新**: 2026-11-13 19:40｜给谁：下一个会话 / 写代码的 AI（WorkBuddy）

## 1. 下一步

1. **T1.4** 上传接口：magic number / 大小 / 时长 / 格式校验 → MinIO → `task(PENDING)`；坏文件 400 `INVALID_MEDIA`。
   顺手把 `MeetingNotesApplication.java:11` 注释里的 `D:\new\...` 改成相对路径（自检噪音）。
2. **T1.5 → T1.8**：worker 端到端（容器内 `http://asr:8000` 首次实跑）→ 三页面 → 配置自检 → 上传校验单测。

## 2. 别踩的坑

1. **`ASR_BASE_URL` 不带 `/v1`**（`http://asr:8000`）；Spring AI 自己追加 `/v1/audio/transcriptions`（D-034）。
2. 转写前缀 `spring.ai.openai.audio.transcription.*`；`response-format` 必须 `json`。
3. **云 LLM**：DeepSeek `base-url=https://api.deepseek.com` + `LLM_CHAT_COMPLETIONS_PATH=/v1/chat/completions` + `deepseek-flash`；thinking 模型 `LLM_MAX_TOKENS` 太小返回空 content。
4. **无 key 可启动**靠 `LlmModelSwitchEnvironmentPostProcessor` + `spring.ai.model.chat=none`（D-035/D-036）。
   **验证必须用独立探针**（`docker run -e LLM_ENABLED= -e LLM_API_KEY= ...`），**禁止 `mv .env`**（会假绿灯，D-039）。
   `LLM_ENABLED=true` + 无 key 崩是预期。
5. **镜像来源（D-038）**：`REGISTRY_PREFIX` 只管 `library/` 镜像；minio/ollama 用 `MINIO_IMAGE`/`OLLAMA_IMAGE`（本机走华为云 ddn-k8s）。
6. **app 镜像**：JDK 21；apt 源在 deb822 `/etc/apt/sources.list.d/*.sources`，换源用 `grep -rlE ... /etc/apt | xargs -r sed`；首构建 12–17 分钟。
7. **连库 `-U mn`**（`postgres` 角色不存在）；`H2 测试绕过 Flyway` → 改 SQL 必须在真实 PG16 验。
8. 本机环境坑：宿主注入 `SERVER__PORT`；curl 要 `--noproxy "*"`；`docker run -v D:\...:/mig:ro` 会卡 `Created` → `docker cp`。
9. 别做平台页面链接抽取（D-027R）；手机上传 P1（D-029）；上限 60min / 批量 20（D-028）；硬编码 / 砍测试 / 编数字一律不可。

## 3. 关键文件

详见 `00-先看这个.md`。要点：`spec.md`（WHAT）· `plan.md`（技术方案）· `tasks.md` / `tasks-starter.md`（执行）·
`decisions.md`（**43 条**，带 R 为修订条）· `docs/协作/review/`（T0.4、T0.5、T1.1T1.2 验收）。
代码仓 `D:\new\projects\meeting-notes`：`AGENT.md`（入口 + 已验证事实 §4.1）· `docker-compose.yml` · `app/Dockerfile` · `infra/asr/`。

## 4. 未决

- `mvn test` 里 `LlmSmokeTest` / `AsrSmokeTest` 依赖真实 key 与服务 → 干净 clone 会红，T3.3 前处理。
- `ollama` 第二档只验证了镜像变量解析（`profiles: local-llm` 未真启），T3.3 做。
- 真实 PG 的 Flyway 集成测试还没有（目前靠人肉 + compose 验证）。
- `small` 模型质量是否够用；要不要补全 medium/large-v3 模型缓存。
