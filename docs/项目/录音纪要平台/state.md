# 状态：录音纪要平台

**更新**: 2026-11-13 22:20｜**阶段**: 阶段 1（T1.1–T1.5 ✅｜下一步 T1.6）

## 目标与排期（D-030）

给别人用的开源自托管「录音/网课/短视频 → 纪要 + 待办」Web 应用（我也是第一用户），用于 2026 寒假实习。
交付 **2026-12-16**｜简历 **12-15**｜**83h / 50 任务 / 5 周**｜仓库 `plus-Liang/meeting-notes`（最新 `48b4b74`）

## 实测基线

- ASR RTF **0.105 / 0.113**（8.44h ≈ 57 分钟）；DeepSeek 跑通；无 key 可启动（`ChatModel` Bean=0）
- 全栈 5/5 healthy；app 启动即健康；Flyway 真实 PG16 建 7 表；容器内 ffmpeg 8.0.1
- **端到端真机**：397s 中文音频 → `DONE` **31s**、transcript **1853 字**；失败路径（停 ASR）**9s** 走完 `RETRYING×2 → DEAD`
- `mvn test` **14 用例全绿**（前提：注入 `.env` + `ASR_BASE_URL=http://localhost:8001`）
- 语料 `D:\datasets\meeting-notes-samples`（4.93h）；镜像 `REGISTRY_PREFIX=docker.m.daocloud.io/`（D-038）
- Java 21.0.2 + Maven 3.9.9（`E:\tools`）；Docker 当前 **5/5 healthy**

## 进度

- 阶段 0 全部 ✅；阶段 1：T1.1 ✅ T1.2 ✅ T1.3 ✅ T1.4 ✅ **T1.5 ✅**（`7c90e9a`+`48b4b74`，验收报告 2026-11-13-T1.5）
- 可维护性：宪法 **VII（v1.1.0）** + `可维护性要求.md`（M-1~M-10）+ `check-maintainability.ps1` **exit 0**（债务 4 条，只减不增）
- 独立验收报告 **5 份**：T0.4 / T0.5 / T1.1T1.2 / T1.4 / **T1.5**
- 材料：项目文档 25 份 + 协作文档 14 份；`docs/协作/开工准备清单.md` G1–G8 全过（D-043）

## 待办（按顺序）

1. **T1.6 三页面**（卡已发 `tasks-starter.md`）+ 前置 D-048（空文本改 `DONE` + 空 transcript，带单测）
2. T1.7 配置自检 → T1.8 上传校验单测（S-1：worker 状态机测试排这里或 T2.x）
3. T3.3 补 `scripts/test.ps1`（固化 `.env` + `ASR_BASE_URL`，消灭"我这边绿"）

## 阻塞 / 风险

- 无阻塞。风险：`content_hash` 无唯一约束（T2.1）；认证匿名（T4.6，D-040）；60min 长音频端到端未验（T4.1）
- 已知限制（D-049）：DEAD 任务重传原样返回 DEAD，T2.1 修；已写进 `接入指南.md` §3.3

## 关键约束

目标给别人用（D-003R）→ README「陌生人 30 分钟跑通」；配置一个不落；不填 key 也能跑；
上限 60min / 批量 20 / 手机上传 P1 / 链接 L1+L2+BYOF；对外 JSON 一律 snake_case（D-041）；
错误码固定 **7 个**（D-047）；空 transcript = 未识别到语音，不是失败（D-048）
