# 状态：录音纪要平台

**更新**: 2026-11-13 19:40｜**阶段**: **第 1 周进行中 → T1.1 / T1.2 / T1.3 ✅，下一步 T1.4**

## 目标与排期（已定 5 周，D-030）

**给别人用的**开源自托管「录音/网课/短视频 → 纪要 + 待办」Web 应用（我也是第一用户），用于 2026 寒假实习。
交付 **2026-12-16**｜简历 **12-15**｜预算 **83h / 50 个任务**。
GitHub：`https://github.com/plus-Liang/meeting-notes`（main 已跟踪，最新 `fb45588`）

## 实测基线

- **ASR**：**RTF 0.105 / 0.113**（8.44h ≈ 57 分钟）；模型加载 4–8s；`small` 中文可用但专有名词会错。
- **云 LLM**：DeepSeek `deepseek-flash` 实测跑通；无 key 时应用仍能启动（`ChatModel` Bean=0）。
- **全栈**：`docker compose up -d` → **5/5 healthy**（pg16.16/redis7/minio/asr/app），app 启动 5.1s，Flyway V1 幂等。
- 真实语料：`D:\datasets\meeting-notes-samples`（11 文件 / **4.93 小时** / 552MB，仓库外）。

## 工具链（实测）

- Docker 29.8.0 / Compose 5.5.1 ✅；Hub 不通 → `REGISTRY_PREFIX=docker.m.daocloud.io/`，**非 library 镜像用 `MINIO_IMAGE`/`OLLAMA_IMAGE` 覆盖**（D-038）
- Java 21.0.2 + Maven 3.9.9 ✅（`E:\tools`）；git 2.55 ✅；app 镜像基础 JDK **21**（容器内 Maven 构建 12–17 分钟）
- 本机模型：`D:\huggingface`（faster-whisper **small 完整**）

## 进度

- **阶段 0 全部完成**：T0.1 自建 ASR / T0.2 契约 / T0.3 RTF / T0.4 Spring AI / T0.5 云 LLM + 无 key
- **第 1 周**：T1.1 骨架收尾（D-037 改名）✅ · T1.2 全栈一键起 ✅ · T1.3 Flyway 真实 PG 生效 ✅
- 审查：`review/2026-11-13-T0.4验收.md`、`-T0.5验收.md`、`-T1.1T1.2验收.md`

## 待办（第 1 周剩余，按顺序）

1. **T1.4** 上传接口（magic number / 大小 / 时长 / 格式）→ MinIO → task(PENDING)；顺手改 `MeetingNotesApplication.java:11` 注释里的绝对路径（自检噪音，P3-2）
2. T1.5 worker 端到端（容器内 `http://asr:8000` 首次实跑）→ T1.6 三页面 → T1.7 配置自检 → T1.8 上传校验单测
3. 建议 T1.4/T1.5 后补**真实 PG 的 Flyway 集成测试**（Testcontainers 或 compose pg），H2 永远绕过 SQL

## 阻塞 / 风险

- **风险**：`LlmSmokeTest` / `AsrSmokeTest` 依赖真实 key 与本地服务 → 干净 clone 跑 `mvn test` 会红（T3.3 前处理）
- **风险**：`LLM_ENABLED=true` 但无 key 时启动会崩（显式优先，属预期；P3-3 补友好报错）
- **风险**：app 镜像首构建 12–17 分钟（容器内全新依赖）→ CI/他人机器偏慢，可加 BuildKit 缓存
- **环境坑**：见 `环境搭建说明.md` §7（11 条）+ 代码仓 `AGENT.md` §4.1

## 关键约束

- 目标**给别人用**（D-003R）→ README"陌生人 30 分钟跑通"
- 上限 60min / 批量 20 / 手机上传 P1 / 链接 L1+L2+BYOF；5 模板不新增管线；不做 L3
- 配置一个不落；**不填 key 也能跑**（D-035/D-036，验证方法 D-039）；断网可用；可备份
