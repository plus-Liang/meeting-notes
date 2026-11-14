# 状态：录音纪要平台

**更新**: 2026-11-14 11:45｜**阶段**: 阶段 1 全 ✅（T1.1–T1.9）+ **T2.1 ✅** → 下一步 T2.11

## 目标与排期（D-030）

给别人用的开源自托管「录音/网课/短视频 → 纪要 + 待办」Web 应用（我也是第一用户），用于 2026 寒假实习。
交付 **2026-12-16**｜简历 **12-15**｜**5 周 / 54 任务 / 约 85.5h**｜仓库 `plus-Liang/meeting-notes`（ahead/behind 0/0）

## 实测基线

- ASR RTF **0.105 / 0.113**；DeepSeek 跑通；无 key 可启动（`ChatModel` Bean=0）；全栈 **5/5 healthy**
- 端到端：397s 中文音频 → `DONE` **31s** / 1853 字；停 ASR → `RETRYING×2 → DEAD`（本次观测 **78s**，原因见风险）
- **T1.9/T2.1 复验（我跑）**：`scripts/test.ps1` 裸 shell **26/26**；`-UnitOnly` **24/24**；反例 **exit 1**；
  连传两次同 id（第二次 `reused:true`+真实 `DONE`）；**并发**两请求同 id、只 1 行；**DEAD 重传 → PENDING → 0.1s DONE**（复活不扣配额）；
  **429 与停 MinIO 都不留孤儿 PENDING 行**（事务回滚生效）；`check-maintainability.ps1` exit 0
- 数据库：`flyway_schema_history` V1+V2 success；唯一索引 `ux_task_user_hash (user_id, content_hash)`（全局 `ux_task_hash` 已撤，D-055）
- 语料 `D:\datasets\meeting-notes-samples`（4.93h）；JDK21+Maven3.9.9（`E:\tools`）；测试统一入口 `scripts/test.ps1`

## 进度

- 阶段 0 全 ✅；阶段 1 **T1.1–T1.9 ✅**；第 2 周 **T2.1 ✅**（验收报告 8 份，最新 `review/2026-11-14-T1.9-T2.1验收.md`）
- 治理基线（D-050/D-051/D-054/D-056）：review 四目标 + 测试分层/文档巡检 + 模板 13/14 + 开源协议门槛 +
  配置必须有消费方 + **幂等权威 = 数据库唯一约束**（D-055）+ 错误码只增不改
- 可维护性：宪法 **VII（v1.1.0）** + `可维护性要求.md`（M-1~M-10）+ 脚本 exit 0；allowlist **3 条真债**

## 待办（按顺序）

1. **T2.11 契约与死信收口**（0.5h，T2.1 验收发现 F-1/F-2：死信残留 + 500 无 `code`）
2. **T2.10 服务端列表 API（D-053）** → T2.2 重试/超时/死信（含 `app.asr.timeout-sec` 落地，F-3）→ T2.3 崩溃恢复
3. T2.8 备份恢复 → T3.x 结构化/三档 → T4.x 交付（含 T4.12 死配置体检 / T4.13 真机 UI）→ 第 5 周

## 阻塞 / 风险

- 无阻塞。风险：真机 UI 未验（T4.13）；DEAD 复活后死信残留（T2.11 修，F-1）；存储故障 500 无 `code`（T2.11 修，F-2）；
  **停 ASR 后首次失败可拖 78s**（Docker DNS 解析超时；`app.asr.timeout-sec` 无消费方 → T2.2，F-3）；
  V2 去重遇到"重复任务已 DEAD"会因 `dead_letter` 无级联失败（F-4，接受，首发全新库）；
  事务内做对象上传 → MinIO 慢时行锁变长（T2.x 并发时复看）；认证匿名（T4.6）；18 个"计划中"配置键（T4.12 清零）；**余量 0**

## 关键约束

目标给别人用（D-003R）→ README「陌生人 30 分钟跑通」；配置一个不落（不能有死配置）；不填 key 也能跑；
上限 60min / 批量 20 / 手机上传 P1 / 链接 L1+L2+BYOF；对外 JSON 一律 snake_case（D-041）；
错误码固定 **7 个**（D-047，只增不改）；空 transcript = 未识别到语音（D-048）；二开前先看开源协议（D-051）；
**禁止手改代码仓里的 `docs/` 生成副本与 allowlist**（T1.6 已踩，见验收报告 F-1/F-2）
