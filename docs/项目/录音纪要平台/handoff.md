# 交接：录音纪要平台

**更新**: 2026-11-13 v6｜给谁：下一个会话 / 写代码的 AI（WorkBuddy）

## 1. 下一步

1. **T0.4**：Spring AI `TranscriptionModel` 指向 `http://asr:8000/v1`，`mvn -q -Dtest=AsrSmokeTest test` 通过。
2. **T0.5**：云 LLM（智谱优先）通道跑通，实测 base_url/模型名记进 `decisions.md` D-007。
3. 然后进第 1 周（上传接口 → task 表 → worker → 结果页），任务卡见 `tasks-starter.md`。

## 2. 别踩的坑

1. **ASR 已建好**：`infra/asr/`（`python:3.12-slim` + `av==13.1.0` + `faster-whisper==1.2.1`）。
   ❌ 别换成远程镜像（Hub 不通）；❌ 别把 `av` 升到 19（`metadata_errors` TypeError）。
2. **中文用 `initial_prompt="以下是普通话的句子，请用简体中文输出。"`**，否则可能输出繁体。
3. **容器内固定听 8000**，宿主机走 `${ASR_PORT}:8000`；本机 8000 已被别的项目占用 → 测试用 8001。
4. **第三方镜像**（pg/redis/minio）要设 `REGISTRY_PREFIX=docker.m.daocloud.io/`；ASR 是本地 build 不需要。
5. 别跳过"先确认理解"（上轮因此返工）；别用 30 分钟上限（45% 真实文件超它，已改 60，D-028）。
6. 别做平台页面链接抽取（D-027R 只做 L1/L2/BYOF）；手机上传是 **P1**（D-029）。
7. 本机 SAC 拦未签名二进制，媒体处理全容器内（D-010）；硬编码 / 砍测试 / 改备份语义 / 编数字一律不可。
8. 没有可抄的 Java 全栈同类项目（0 仓库 / 最高 9 星 demo）。

## 3. 关键文件

`docs/项目/录音纪要平台/`：`00-先看这个` → `需求确认单` → `真实数据-文件与耗时实测` → `T0.1-ASR验证结果` →
`spec` → `plan` → `tasks` / `tasks-starter` → `decisions`（35 条，带 R 为修订条）→ `接入指南` / `配置清单` / `环境搭建说明`。

代码仓 `D:\new\projects\meeting-notes`：`AGENT.md`（写代码 AI 入口）· `infra/asr/`（已验证的 ASR 服务）·
`tools/save.ps1`（提交助手）· 已推 GitHub `plus-Liang/meeting-notes`。

## 4. 未决

- **真实音频语料**：原 `Videos` 里的 mp3 已被本人删除 → 已归档 `D:\datasets\meeting-notes-samples`（11 文件 / 4.93h），见 `测试语料-清单.md`。
- T0.5 云 LLM 实测值（智谱 base_url / 模型名）待填。
- `small` 模型专有名词准确率一般；是否下载 medium/large-v3 完整模型待定。
- 你实际整理单文件耗时、手机里"短视频"数量。
