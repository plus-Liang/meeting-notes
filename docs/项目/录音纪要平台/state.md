# 状态：录音纪要平台

**更新**: 2026-11-13 20:30｜**阶段**: **阶段 0 已过闸**（ASR 服务实测可用）→ 下一步 T0.4 / T0.5

## 目标与排期（**已定 5 周**，D-030）

**给别人用的**开源自托管「录音/网课/短视频 → 纪要 + 待办」Web 应用（我也是第一用户），用于 2026 寒假实习。
交付 **2026-12-16**｜简历 **12-15**｜预算 **83h / 50 个任务**。
GitHub：`https://github.com/plus-Liang/meeting-notes`（main 已跟踪，可 push）

## 实测基线

- `C:\Users\ljh\Videos` 2026-11-13 首次扫描：20 文件 / 8.44h / 0.90GB，9 组 mp4 与 mp3 时长一致（手工剪映转过 9 次）。
  ⚠️ **二次检查时该目录只剩 1 个 mp4**（mp3 已不在）→ 需与本人确认去向。
- **ASR 实测：RTF 0.105（90s）/ 0.113（13min）**；模型加载 4–8s；**8.44h ≈ 57 分钟**。

## 工具链（实测）

- Docker 29.8.0 / Compose 5.5.1 ✅（Hub 不通，第三方镜像需 `REGISTRY_PREFIX=docker.m.daocloud.io/`）
- Java 21.0.2 + Maven 3.9.9 ✅（`E:\tools`）；git 2.55 ✅
- 本机模型：`D:\huggingface`（faster-whisper **small 完整**；medium/large-v3 只有配置）

## 进度

- **T0.1 ✅** 自建 ASR 容器（`infra/asr/`）：`docker compose build asr` + `up -d asr` → **healthy**
- **T0.2 ✅** 契约验证：`/v1/models` 与 `POST /v1/audio/transcriptions` 均返回正确 JSON（简体中文、有标点）
- **T0.3 ✅** RTF 实测（见上），已写进 `T0.1-ASR验证结果.md`
- T0.4 Spring AI 冒烟 / T0.5 云 LLM ❌ 未做
- 21 份项目文档 ✅；代码仓已推 GitHub ✅

## 待办（按顺序）

1. **T0.4**：Spring AI `TranscriptionModel` → `http://asr:8000/v1`，`mvn -q -Dtest=AsrSmokeTest test` 通过
2. **T0.5**：云 LLM（智谱优先）通道跑通，实测 base-url/模型名记进 D-007
3. 进第 1 周：上传接口 → task 表 → worker → 结果页

## 阻塞 / 风险

- **待确认**：`C:\Users\ljh\Videos` 里的 mp3 去哪了（影响 README 实测表述）
- **风险**：`small` 模型专有名词会错（实测"文件夹→文件家""通义→通易"）→ 靠术语回流补，或后续换 medium/large
- **风险**：第三方镜像（pg/redis/minio）未实测拉取，需 `REGISTRY_PREFIX`（T1.2 会验证）
- **缺口**：你实际整理单文件耗时、手机里"短视频"数量

## 关键约束

- 目标**给别人用**（D-003R）→ README"陌生人 30 分钟跑通"
- 上限 60min / 批量 20 / 手机上传 P1 / 链接 L1+L2+BYOF；5 模板不新增管线；不做 L3
- 配置一个不落；不填 key 也能跑；断网可用；可备份；每周测试全绿
