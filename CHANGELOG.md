# Changelog

本文件记录所有值得注意的变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.1.0] - 2026-11-19

首个可演示、可自托管的版本：**本地上传 / 浏览器录音 / 直链 → 转写 → 结构化纪要（摘要 / 要点 / 待办 / 关键词）**，带 Web 界面、REST API 与 Webhook。
一个 `docker compose up -d` 起 5 个容器（app / asr / postgres / minio / redis），默认不填任何 API key 也能完整跑通。

### Added

**输入**

- 文件上传：mp3 / wav / m4a / flac / ogg / opus / amr / mp4 / mov / mkv / webm 等；批量最多 20 个，逐个独立转写、失败互不影响
- 粘贴文本、srt / vtt 字幕（REST API `POST /api/v1/tasks`，JSON 或 multipart）、http(s) 直链（L2 直链探测 + SSRF 防护）、浏览器内录音（标签页 / 屏幕音频）
- 链接导入的边界处理：平台页面链接只读公开元信息做预览，不下载视频、不绕技术措施、不存 cookie；预留 `FETCHER_CMD`（BYOF）

**转写**

- 自建 faster-whisper ASR 容器（OpenAI 兼容 `/v1/audio/transcriptions`），带标点与句子级时间戳；`small` / `medium` 等模型可换档
- 单任务上限 60 分钟 / 200 MB、日配额 200 分钟 / 100 个任务，均可配；超限在提交时即被拒并说明原因
- 幂等（`user_id + content_hash` 唯一）、失败重试退避、死信队列、重启不丢、结构化 JSON 日志（`taskId` 为独立字段）

**结构化**

- 5 套摘要模板：会议 / 课程 / 访谈 / 播客 / 短视频（默认按文件名自动识别，也可在详情页换模板重跑）
- 三档 AI 自动降级：CLOUD（自备 OpenAI 兼容 API）→ LOCAL（Ollama 等）→ EXTRACTIVE（零依赖规则抽取）；`ai_tier` 如实记录实际档位
- 摘要级原文依据（每句摘要给出在原文中匹配到的句子，匹配不到如实写"未找到"）
- 编号要点、待办（会议模板只收已议定事项，owner 未点名不编造）、关键词

**校对与整理**

- 全文分句 + 点句跳转播放；标点版 / 无标点版切换；一句一行
- 术语表闭环：改错词沉淀为术语，后续转写生效
- 手工说话人分组；行内重命名；标签（多选筛选）与收藏

**检索与导出**

- 服务端检索：关键词（文件名 / 标题 / 内容）、时间范围、状态、标签多选；真实计数；按日期分组；批量操作
- 导出 Markdown 单条 / 批量 zip（可选导出摘要 / 要点 / 关键词 / 待办 / 转写，勾选或全选）

**分享与接入**

- 只读分享链接（7 天过期、可撤销、匿名可读）
- REST API（Bearer Token，含 OpenAPI / Swagger UI）、Webhook（HMAC-SHA256 签名 + 退避重试 + 死信）

**运维与账号**

- 健康检查、备份 / 恢复脚本（DB + MinIO 音频 + sha256 清单，默认保留 7 份，备份包不含 `.env`）
- 一键启动脚本：`scripts/first-run.ps1`（Windows）/ `scripts/first-run.sh`（Linux/macOS）——没有 `.env` 就生成、下载 ASR 模型到 `./models`、`docker compose up -d --build`、轮询到 5 个容器 healthy；也可单独用 `scripts/download-model.ps1` 下载模型
- Flyway 迁移 V1–V9，升级自动迁移；设置页展示系统状态、今日配额与"我的使用"统计
- 首次创建管理员、登录 / 注册开关、单实例多账号数据隔离；全局 `API_TOKEN` 等价于 1 号管理员，便于脚本接入

**文档**

- 面向陌生人的 README（定位 / 快速开始 / 实测性能与资源 / 成本对比 / 边界 / 运维 / FAQ）
- 10 张真实界面截图 + 30 秒演示 GIF；`docs/项目/录音纪要平台/` 下需求 / 决策 / 验收 / 接入 / 架构文档，`review/` 含 70 份带可复现命令的验收报告

### 实测（本机 CPU，2026-11）

- ASR 速度 RTF **0.10–0.11**（90 s 音频 0.105；783.8 s 音频 0.113）；56.7 分钟音频 **334.6 s** 出转写
- 8.44 小时 / 20 个真实文件 / 0.90 GB：整体约 **50–60 分钟**
- 空闲常驻内存约 **1.7 GB**（app ≈0.77 GB + asr ≈0.70 GB + 其它 ≈0.19 GB）；镜像 app 1.24 GB + asr 819 MB

### 已知限制

- 只做 Web（桌面优先，390 / 768 不回归），无原生 App；移动端只在 Chromium 内核验证，Safari 未专项测试
- 不做实时入会（腾讯会议 / 飞书 / Zoom 实时转写）、不做平台页面下载解析、不做多租户 SaaS
- 说话人分离为手工标注，不做自动 diarization；分享链接无密码保护
- 单机单实例定位，无集群 / 高可用；长音频按 RTF≈0.1 线性耗时，不适合"秒级出稿"场景

详见 [README](README.md) 的"适合谁 / 不适合谁"与"限制与已知问题"。

## [0.0.1] - 2026-11-13

### Added

- 初始提交：仓库骨架、`docker-compose.yml`、Flyway 迁移、`.env.example`、`AGENT.md`、项目文档副本
