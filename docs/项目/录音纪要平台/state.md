# 状态：录音纪要平台

**更新**: 2026-11-13 18:55｜**阶段**: **阶段 0 全部完成（T0.1–T0.5 ✅）** → 下一步第 1 周 T1.1

## 目标与排期（已定 5 周，D-030）

**给别人用的**开源自托管「录音/网课/短视频 → 纪要 + 待办」Web 应用（我也是第一用户），用于 2026 寒假实习。
交付 **2026-12-16**｜简历 **12-15**｜预算 **83h / 50 个任务**。
GitHub：`https://github.com/plus-Liang/meeting-notes`（main 已跟踪，已推 `483d6f2`）

## 实测基线

- **ASR**：**RTF 0.105 / 0.113**（8.44h ≈ 57 分钟）；模型加载 4–8s；`small` 中文可用但专有名词会错。
- **云 LLM**：DeepSeek `deepseek-flash` 实测跑通（3 条中文摘要）；无 key 时应用仍能启动（`ChatModel` Bean=0）。
- 真实语料：`D:\datasets\meeting-notes-samples`（11 文件 / **4.93 小时** / 552MB，仓库外）。

## 工具链（实测）

- Docker 29.8.0 / Compose 5.5.1 ✅（Hub 不通，第三方镜像用 `REGISTRY_PREFIX=docker.m.daocloud.io/`）
- Java 21.0.2 + Maven 3.9.9 ✅（`E:\tools`）；git 2.55 ✅
- 本机模型：`D:\huggingface`（faster-whisper **small 完整**；medium/large-v3 不完整）

## 进度

- **阶段 0 全部完成**：T0.1 自建 ASR 容器 / T0.2 契约验证 / T0.3 RTF 实测 / T0.4 Spring AI 冒烟 / T0.5 云 LLM + 无 key 启动
- 审查：`review/2026-11-13-T0.4验收.md`、`review/2026-11-13-T0.5验收.md`（均由独立上下文的 DSH 复核，含我补做的 PG16 Flyway 验证）
- **22 份项目文档** ✅；代码仓已推 GitHub ✅（含 `AGENT.md`、`infra/asr/`、`tools/save.ps1`）

## 待办（第 1 周，按顺序）

1. **T1.3 前**：决定 `glossary."right"` 是否改名 `right_text`（审查报告 P2-1，现在改免费）
2. T1.1 骨架收尾 → T1.2 `docker compose up` 全套一键起（关键：第三方镜像走 `REGISTRY_PREFIX`）
3. T1.3 Flyway 生效（**必须用真实 PG 验，H2 测试绕过它**）→ T1.4 上传接口 → T1.5 worker → T1.6 三页面 → T1.7 配置自检 → T1.8 单测

## 阻塞 / 风险

- **风险**：`LlmSmokeTest` / `AsrSmokeTest` 依赖真实 key 与本地服务 → 干净 clone 跑 `mvn test` 会红（T3.3 前处理）
- **风险**：H2 测试绕过 Flyway → SQL 改动只能靠手工 PG 验证（T1.3 起加集成测试）
- **风险**：`LLM_ENABLED=true` 但无 key 时启动会崩（显式优先，属预期；T1.x 补友好日志）
- **环境坑**：宿主注入 `SERVER__PORT`；curl 本机要 `--noproxy "*"`；`docker run -v D:\...:/mig:ro` 偶发卡 `Created`

## 关键约束

- 目标**给别人用**（D-003R）→ README"陌生人 30 分钟跑通"
- 上限 60min / 批量 20 / 手机上传 P1 / 链接 L1+L2+BYOF；5 模板不新增管线；不做 L3
- 配置一个不落；**不填 key 也能跑**（D-035/D-036）；断网可用；可备份；每周测试全绿
