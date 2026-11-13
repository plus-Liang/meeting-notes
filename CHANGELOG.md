# Changelog

本文件记录所有值得注意的变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- 项目骨架：Spring Boot 3 + Spring AI + PostgreSQL + Redis + MinIO + Docker Compose
- 数据库初始化（Flyway V1）：`task` / `task_result` / `glossary` / `speaker_profile` / `quota` / `dead_letter` / `webhook_delivery`
- 配置基线：`.env.example` 全量配置，应用侧全部 `${ENV:default}`，零硬编码
- 文档：规格 / 技术方案 / 任务清单 / 决策记录 / 接入指南 / 配置清单 / 环境搭建 / 面试材料

### Planned

- [ ] 阶段 0：自建 OpenAI 兼容 ASR 容器 + 真实音频转写 + RTF 实测
- [ ] 单文件端到端：上传 → 转写 → 摘要 → 待办
- [ ] 任务可靠性：幂等 / 重试退避 / 死信 / 崩溃恢复 / 健康检查 / 结构化日志
- [ ] 摘要模板 5 种（会议 / 课程 / 访谈 / 播客 / 短视频）
- [ ] 三档 AI 降级（云 API → 本地 Ollama → 抽取式）
- [ ] 闭环回流：术语表修正与生效
- [ ] 接入面：REST API + Webhook（HMAC）+ OpenAPI
- [ ] 链接导入：L1 元信息确认 + L2 直链探测 + BYOF 接口
- [ ] 备份 / 恢复 / 重启不丢
- [ ] `v0.1.0` 首个可演示版本

## [0.0.1] - 2026-11-13

### Added

- 初始提交：仓库骨架、`docker-compose.yml`、Flyway 迁移、`.env.example`、`AGENT.md`、项目文档副本
