# 状态：录音纪要平台

**更新**: 2026-11-13 20:10｜**阶段**: **第 1 周 → T1.1/T1.2/T1.3/T1.4 ✅，下一步 T1.5（开工前先修契约）**

## 目标与排期（已定 5 周，D-030）

**给别人用的**开源自托管「录音/网课/短视频 → 纪要 + 待办」Web 应用（我也是第一用户），用于 2026 寒假实习。
交付 **2026-12-16**｜简历 **12-15**｜预算 **83h / 50 个任务**。
GitHub：`https://github.com/plus-Liang/meeting-notes`（main 已跟踪，最新 `3ce9386`）

## 实测基线

- **ASR**：**RTF 0.105 / 0.113**（8.44h ≈ 57 分钟）；模型加载 4–8s；`small` 中文可用但专有名词会错。
- **云 LLM**：DeepSeek `deepseek-flash` 实测跑通；无 key 时应用仍能启动（`ChatModel` Bean=0）。
- **全栈**：`docker compose up -d` → **5/5 healthy**；app 启动 5.1s；Flyway V1 幂等；app 镜像 FFmpeg/ffprobe 8.0.1。
- **上传链路（T1.4 真机）**：exe 改名 → 400；90 秒真实 wav → 201 + PENDING；重复 → 幂等命中；413/400/429 全对。
- 真实语料：`D:\datasets\meeting-notes-samples`（11 文件 / **4.93 小时** / 552MB，仓库外）。

## 工具链（实测）

- Docker 29.8.0 / Compose 5.5.1 ✅；`REGISTRY_PREFIX=docker.m.daocloud.io/`，非 library 镜像用 `MINIO_IMAGE`/`OLLAMA_IMAGE`（D-038）
- Java 21.0.2 + Maven 3.9.9 ✅（`E:\tools`）；app 容器内 Maven 构建 12–17 分钟（首次）
- 测试：`mvn -q test` **14 个用例全绿**（需注入 `.env` + `ASR_BASE_URL=http://localhost:8001`）

## 进度

- **阶段 0 全部完成**（T0.1–T0.5）；**第 1 周**：T1.1 ✅ T1.2 ✅ T1.3 ✅ **T1.4 ✅（上传接口）**
- 审查 4 份：`review/2026-11-13-{T0.4,T0.5,T1.1T1.2,T1.4}验收.md`（全部独立上下文复跑，已随仓同步）

## 待办（T1.5，按顺序）

1. **先修 T1.4 审查遗留（一个 commit）**：snake_case 契约 + 错误码（D-041）· 幂等返回真实状态 · `findIdByHash` 加 `user_id`（D-040）
2. T1.5 worker：Redis 阻塞队列 → ffmpeg 抽音轨 → 容器内 `http://asr:8000` → 写 `task_result` → DONE；并补 `GET /api/v1/tasks/{id}`
3. T1.6 三页面 → T1.7 配置自检 → T1.8 上传校验单测

## 阻塞 / 风险

- **P1 契约**：实现原用 `taskId`/`TOO_LARGE`/`TOO_LONG`，与 `接入指南.md` 承诺不符 → 已定 D-041，T1.5 前改
- **P2 越权隐患**：`findIdByHash` 未带 `user_id`，认证（T4.6）上线即变跨用户读取 → T1.5 前修（D-040）
- **风险**：`LlmSmokeTest` / `AsrSmokeTest` 依赖真实 key 与服务 → 干净 clone 跑 `mvn test` 会红（T3.3 前处理）
- **风险**：`content_hash` 无唯一约束 → 并发同文件仍可双插（T2.1 解决）
- **环境坑**：见 `环境搭建说明.md` §7（12 条）+ 代码仓 `AGENT.md` §4.1

## 关键约束

- 目标**给别人用**（D-003R）→ README"陌生人 30 分钟跑通"
- 上限 60min / 批量 20 / 手机上传 P1 / 链接 L1+L2+BYOF；5 模板不新增管线；不做 L3
- 配置一个不落；**不填 key 也能跑**（D-035/D-036，验证方法 D-039）；对外 JSON 一律 snake_case（D-041）
