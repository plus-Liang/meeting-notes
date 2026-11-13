# 状态：录音纪要平台

**更新**: 2026-11-14 00:40｜**阶段**: 阶段 1（T1.1–T1.5 ✅｜T1.6 卡已发 WorkBuddy，待回报）

## 目标与排期（D-030）

给别人用的开源自托管「录音/网课/短视频 → 纪要 + 待办」Web 应用（我也是第一用户），用于 2026 寒假实习。
交付 **2026-12-16**｜简历 **12-15**｜**83h / 50 任务 / 5 周**｜仓库 `plus-Liang/meeting-notes`（最新 `bcaf7eb`，代码最新 `48b4b74`）

## 实测基线

- ASR RTF **0.105 / 0.113**；DeepSeek 跑通；无 key 可启动（`ChatModel` Bean=0）；全栈 **5/5 healthy**
- **端到端**：397s 中文音频 → `DONE` **31s**、transcript **1853 字**；停 ASR → **9s** 走完 `RETRYING×2 → DEAD`
- `mvn test` **14/14**（须 `.env` + `ASR_BASE_URL=http://localhost:8001`，坑 #13）；可维护性脚本 **exit 0**
- 语料 `D:\datasets\meeting-notes-samples`（4.93h）；镜像前缀 `docker.m.daocloud.io/`（D-038）；JDK21+Maven3.9.9（`E:\tools`）

## 进度

- 阶段 0 全 ✅；阶段 1：T1.1 ✅ T1.2 ✅ T1.3 ✅ T1.4 ✅ T1.5 ✅（验收报告 5 份，最新 T1.5）
- **T1.6 三页面**（含 D-048 空文本→DONE）卡已发，等 WorkBuddy 回报 → 我独立验收
- 治理基线（11/14 音频，D-050/D-051）：五类腐化清单 + **review 四目标**（已进 `rules/review.md`）+
  测试分层/清废弃代码/文档巡检（`rules/dev.md`）+ 模板 13/14 + 样式统一（T1.6 硬要求）+ 开源协议门槛
- 可维护性：宪法 **VII（v1.1.0）** + `可维护性要求.md`（M-1~M-10）+ `check-maintainability.ps1` exit 0（债务 4 条只减不增）

## 待办（按顺序）

1. 验收 T1.6（真机点 5 条验收 + 14→≥15 用例 + 脚本 exit 0 + `git diff --stat` 查越界）→ 出 T1.6 验收报告
2. T1.7 配置自检 → T1.8 上传校验单测 + worker 状态机测试（S-1）→ T3.3 `scripts/test.ps1`（测试分层入口）
3. T2.1 幂等（含 D-049 DEAD 重投）→ T4.x 发布硬门禁（D-050 第 5 条）

## 阻塞 / 风险

- 无阻塞。风险：`content_hash` 无唯一约束（T2.1）；认证匿名（T4.6）；60min 长音频端到端未验（T4.1）；
  DEAD 重传原样返回（D-049，T2.1 修）；worker 无单测（S-1）；页面样式腐化（D-050 第 1 类，T1.6 已加约束）

## 关键约束

目标给别人用（D-003R）→ README「陌生人 30 分钟跑通」；配置一个不落；不填 key 也能跑；
上限 60min / 批量 20 / 手机上传 P1 / 链接 L1+L2+BYOF；对外 JSON 一律 snake_case（D-041）；
错误码固定 **7 个**（D-047）；空 transcript = 未识别到语音（D-048）；二开前先看开源协议（D-051）
