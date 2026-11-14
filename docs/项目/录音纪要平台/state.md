# 状态：录音纪要平台

**更新**: 2026-11-14 11:20｜**阶段**: **阶段 1 完成（T1.1–T1.8 ✅）** → 下一步 T1.9 → 第 2 周

## 目标与排期（D-030）

给别人用的开源自托管「录音/网课/短视频 → 纪要 + 待办」Web 应用（我也是第一用户），用于 2026 寒假实习。
交付 **2026-12-16**｜简历 **12-15**｜**5 周 / 53 任务 / 约 85h**（+2h 验证与工具，D-054）｜仓库 `plus-Liang/meeting-notes`（ahead/behind 0/0）

## 实测基线

- ASR RTF **0.105 / 0.113**；DeepSeek 跑通；无 key 可启动（`ChatModel` Bean=0）；全栈 **5/5 healthy**
- 端到端：397s 中文音频 → `DONE` **31s** / 1853 字；停 ASR → **9s** `RETRYING×2 → DEAD`
- **T1.6 真机**：`GET /` **200/3399B**；三页共用 `style.css`、0 处硬编码地址；6s 正弦波 → `DONE` + `transcript:""`；
  停 ASR → `DEAD` + `error_code=ASR_ERROR`
- **T1.7 复验**：命令1 **0 命中**；命令2 **10 行**均可辩护；yml **53** ↔ `.env.example` **68** 差集 **0**；compose config **0 告警**；
  魔法阈值扫描 **0**；反例 `DB_URL→${DB_URL_TYPO}` 启动即炸（`'url' must start with "jdbc"`）
- **T1.8 复验**：`mvn test` **23/23**、exit 0（主代码零改动、0 行断言被删）；`check-maintainability.ps1` exit 0
- 语料 `D:\datasets\meeting-notes-samples`（4.93h）；JDK21+Maven3.9.9（`E:\tools`）

## 进度

- 阶段 0 全 ✅；阶段 1 **T1.1–T1.8 ✅**（验收报告 7 份，最新 `review/2026-11-14-T1.7-T1.8验收.md`）
- 治理基线（11/14 音频，D-050/D-051）：五类腐化 + **review 四目标**（`rules/review.md` §2.0）+ 测试分层/文档巡检（`rules/dev.md`）
  + 模板 13/14 + 样式统一（T1.6 已落）+ 开源协议门槛 + **配置必须有消费方**（D-054）
- 可维护性：宪法 **VII（v1.1.0）** + `可维护性要求.md`（M-1~M-10）+ 脚本 exit 0；allowlist **3 条真债**
- 库里有 T1.6 演示数据：task 4（DONE 空 transcript）、task 5（DEAD ASR_ERROR）；dead_letter 1 条

## 待办（按顺序）

1. **T1.9 `scripts/test.ps1`**（0.5h，测试入口：注入 `.env`+`ASR_BASE_URL`，`-UnitOnly`）
2. T2.1 幂等（`content_hash` 唯一约束 + **D-049 DEAD 重传重投**）→ T2.2 重试/死信 → **T2.10 服务端列表 API（D-053）**
3. T4.12 死配置体检（18 个"计划中"键清零）+ T4.13 真机 UI 验收（浏览器/手机/无痕）；T4.1/T4.2 真机与 E2E

## 阻塞 / 风险

- 无阻塞。风险：`content_hash` 无唯一约束（T2.1）；认证匿名（T4.6）；真机 UI 未验（T4.13）；
  DEAD 重传原样返回（D-049，T2.1 修）；状态机单测白盒、`process()→loop()` 接线靠真机兜（F-2）；
  **18 个配置键暂无消费方**（D-054，T4.12 清零）；**余量 0**（85h/53 任务，加功能必须"砍一个换一个"）

## 关键约束

目标给别人用（D-003R）→ README「陌生人 30 分钟跑通」；配置一个不落（含"不能有死配置"）；不填 key 也能跑；
上限 60min / 批量 20 / 手机上传 P1 / 链接 L1+L2+BYOF；对外 JSON 一律 snake_case（D-041）；
错误码固定 **7 个**（D-047）；空 transcript = 未识别到语音（D-048）；二开前先看开源协议（D-051）；
**禁止手改代码仓里的 `docs/` 生成副本与 allowlist**（T1.6 已踩，见验收报告 F-1/F-2）
