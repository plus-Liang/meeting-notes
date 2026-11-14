# 状态：录音纪要平台

**更新**: 2026-11-14 13:00｜**阶段**: 第 2 周：T2.1–T2.5 / T2.10–T2.12 ✅ → 下一步 **T2.7 JSON 日志** + **T2.8 备份恢复**

## 目标与排期（D-030）

给别人用的开源自托管「录音/网课/短视频 → 纪要 + 待办」Web 应用（我也是第一用户），用于 2026 寒假实习。
交付 **2026-12-16**｜简历 **12-15**｜**5 周 / 55 任务 / 约 86h**｜仓库 `plus-Liang/meeting-notes`（ahead/behind 0/0）

## 实测基线

- ASR RTF 0.105/0.113；DeepSeek 跑通；无 key 可启动；全栈 **5/5 healthy**；`/actuator/health` **四项依赖齐**（db/redis/minio/asr）
- **T2.2**：停 ASR → 首次失败 3.5s、**DEAD 7.7s**（基线 78s）；黑洞实验证明 5s connect 超时真生效；56.7min 长音频 → DONE 334.6s
- **T2.4 复验（我跑）**：停 MinIO → DOWN(503, 1.42s)；停 ASR → DOWN(503, 2.02s)；停 Redis → DOWN；恢复全 UP；
  `readiness` 组内四项齐全；上传回归 DONE；**T2.5 结果缓存验掉**（二次上传 ASR 请求 4→4）
- 数据库：Flyway V1+V2 success；唯一索引 `ux_task_user_hash`；错误码分层（业务 7 + 通用码，D-057）
- 测试 `scripts/test.ps1` **47/47**；可维护性 exit 0；最大文件 `TranscriptionWorker` 271 行
- 语料 `D:\datasets\meeting-notes-samples`；JDK21+Maven3.9.9；测试入口 `scripts/test.ps1`

## 进度

- 阶段 0 全 ✅；阶段 1 **T1.1–T1.9 ✅**；第 2 周 **T2.1 / T2.2 / T2.3 / T2.4 / T2.5 / T2.10 / T2.11 / T2.12 ✅**
  （验收报告 **12 份**，最新 `review/2026-11-14-T2.4验收.md`）
- 治理基线：review 四目标 + 测试分层 + 错误码分层（D-057）+ 配置必须有消费方（D-054）+ 启动期副作用尽力而为（rules/dev.md）
  + 健康检查口径（D-059）+ 并发 v1 单线程（D-061）+ T2.5 由幂等覆盖（D-060）
- 可维护性：宪法 **VII（v1.1.0）** + 脚本 exit 0；allowlist **3 条真债**

## 待办（按顺序）

1. **T2.7 结构化 JSON 日志**（消费 `app.log-json`：MDC taskId + Boot 内建结构化日志，`LOG_JSON=false` 可回退）
2. **T2.8 备份与恢复**（US-11：`backup.ps1`/`restore.ps1`，`-Force` 保护，`-Keep` 保留，不含 `.env`，D-023）——**P1 用户 B 的买点**
3. T2.9 本周单测/验收（含 429+`Retry-After` 复验）→ T3.x 结构化/三档 → T4.x 交付（T4.12 死配置 / T4.13 真机）→ 第 5 周

## 阻塞 / 风险

- 无阻塞。风险：**没有备份**（T2.8 之前 `down -v` 就全没，最高优先级）；纯 DNS 挂起理论上不被 connectTimeout 兜住（接受）；
  `SimpleClientHttpRequestFactory` 缓冲请求体 + 弃用（T2.7/T2.8 之后在 T3 前找机会换 JDK 工厂）；
  依赖挂掉 → 容器 ~30s 后 `unhealthy`（D-060 预期，无重启风暴）；浏览器/手机真机未验（T4.13）；认证匿名（T4.6）；17 个"计划中"配置键（T4.12）；**余量 0**

## 关键约束

目标给别人用（D-003R）→ README「陌生人 30 分钟跑通」；配置一个不落（不能有死配置）；不填 key 也能跑；
上限 60min / 批量 20 / 手机上传 P1 / 链接 L1+L2+BYOF；对外 JSON 一律 snake_case（D-041）；
业务码固定 **7 个**（D-047）+ 通用码分层（D-057）；空 transcript = 未识别到语音（D-048）；二开前先看开源协议（D-051）；
备份**不含 `.env`**、恢复必须 `-Force`（D-023）；**禁止手改代码仓里的 `docs/` 生成副本与 allowlist**（T1.6 已踩）
