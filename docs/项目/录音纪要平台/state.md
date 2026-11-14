# 状态：录音纪要平台

**更新**: 2026-11-14 13:20 ｜ **阶段**: 第 2 周收尾（**T2.1–T2.12 全 ✅**）→ 下一步 **T2.9**
**目标**: 开源自托管「录音/网课/短视频 → 纪要 + 待办」Web 应用（我给别人的，我也是第一用户）；交付 **2026-12-16**，简版 **12-15**；
**5 周 / 55 任务 / ~86h**；仓库 `plus-Liang/meeting-notes`（0/0，56 commits）。

## 实测基线

- 全栈 **5/5 healthy**；health 四项依赖齐（db/redis/minio/asr）；单测 **50/50** exit 0；可维护性 exit 0。
- **T2.7**：task 45/46 各 **5 条** taskId JSON 行（10 条逐行解析 10/10、不串号）；`LOG_JSON=false` 探针 **0 行 JSON**。
- **T2.8**：**10 个 DONE 任务** → 备份 → `down -v` → 恢复 → `id/status/hash/transcript md5/长度` **10/10 逐字一致**；
  恢复后重入队 task 43 → DONE（对象真能读）；`-Keep 2` 生效；无 `-Force` exit 10；备份包不含 `.env`。
- `backups/` 现有 3 份（`-Keep 7` 默认）；最大文件 `TranscriptionWorker` **290 行**（上限 300，余 10）；
  语料 `D:\datasets\meeting-notes-samples`；JDK21 + Maven3.9.9；测试入口 `scripts/test.ps1`。

## 进度

- 阶段 0 全 ✅；阶段 1 **T1.1–T1.9 全 ✅**；第 2 周 **T2.1–T2.12 全 ✅**（T2.5 由幂等覆盖 D-060、T2.6 拆解 D-061）。
- 验收报告 **13 份**（最新 `review/2026-11-14-T2.7-T2.8验收.md`）；决策 **68 条**（新增 D-062/063/064）。
- 治理基线：review 四目标 + 测试分层 + 错误码分层（D-057）+ 配置必须有人用（D-054，死配置 18→**16**）
  + 启动期副作用尽力而为（rules/dev.md）+ 健康口径（D-059）+ 并发 v1 单线程（D-061）+ 结构化日志（D-062）+ 备份口径（D-063）。

## 待办（按顺序）

1. **T2.9 本周单测/验收 + 三处收口**：429+`Retry-After` 真 HTTP 复验、F-1 测试日志一行修（`test` 配置 `app.log-json:false`）、
   F-4 `restore.ps1` preflight exit 20（卡已写，`tasks-starter.md`）。
2. 第 3 周 **T3.x** 结构化纪要 + 三档 AI + 模板 → 第 4 周 **T4.x** 交付（T4.12 死配置 16 键 / T4.13 真机 UI）→ 第 5 周。
3. 我这边：README 补「备份/恢复 + 宿主 cron 示例」与「按 taskId 查日志」两节（D-063 要求）。

## 阻塞 / 风险

- **无阻塞**。风险：自动备份依赖用户配宿主 cron（D-063）；纯 DNS 挂起理论上不被 connectTimeout 兜住（接受）；
  `SimpleClientHttpRequestFactory` 缓冲请求体 + 弃用（T3 前换 JDK 工厂）；依赖挂 → 容器 ~30s `unhealthy`（预期）；
  真机 UI 未验（T4.13）；认证仍匿名（T4.6）；死配置 16 个（T4.12）；**余量 0**。

## 关键约束

目标给别人用（D-003R）→ README「陌生人 30 分钟跑通」；配置一个不落、不许有死配置；不填 key 也能跑；
上限 60min / 批量 20 / 手机上传 P1 / 链接 L1+L2+BYOF；对外 JSON 一律 snake_case（D-041）；业务码固定 **7 个**（D-047）+ 通用码分层（D-057）；
空 transcript = 未识别到语音（D-048）；备份**不含 `.env`**、恢复必须 `-Force`（D-023/D-063）；**禁止手改代码仓里的 `docs/` 生成副本与 allowlist**（T1.6 已踩）。
