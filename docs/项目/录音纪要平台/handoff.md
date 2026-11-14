# 交接：录音纪要平台（更新 2026-11-14 20:35，给下一个会话 / WorkBuddy）

## 1. 下一步

1. **T3.7 导出 Markdown + PASTE 入口**（含 srt/ass 跳过转写）：`GET /api/v1/tasks/{id}/export?format=md`（三节 + 待办 done 状态）+ `POST /api/v1/tasks` 支持 JSON 文本 / srt / ass（跳过 ASR 直接结构化）；**新增端点/请求体属公共面 → 开工确认先交契约草案，批准后再写**。**配额只剩 1 个任务额度（49/50）**：实机只跑 1 条 PASTE，导出用已有任务 123，字幕解析靠单测。
2. **T3.11 本地档预算已上**（D-081）；遗留 **F-14**：`ai_tier=LOCAL` 仍未达（1.5B 输出冗长、512 必截断）→ T3.8 前评估；**F-15**：EXTRACTIVE 档不读词表（无 key 用户改词不生效）→ T3.8 评估"确定性替换"。
3. 之后：T3.9/T3.10 → T3.8 周验收 → 第 4 周 T4.x → 第 5 周。

## 2. 别踩的坑

1. 对外 JSON 一律 snake_case（D-041）；业务码 7 + 通用码 5；`transcript:""`=无语音仍 DONE（D-048）；`todos` 的 null vs `[]`（D-072）；`ai_tier` 必须诚实（D-020/D-075）。
2. **DONE 最后写**（D-069）：transcript → 结构化（一次调用）→ 最后 DONE。
3. **三档是级联**（D-075）：CLOUD→LOCAL→EXTRACTIVE，同档不重试；`OFFLINE_MODE=true` 是云档硬门禁（D-077，WARN `reason=offline_mode`）。
4. **本地档预算独立**（D-081）：`LLM_LOCAL_MAX_TOKENS=512` / `LLM_LOCAL_TIMEOUT_SEC=180`，别改回继承云档；云档 8192/120s 与 ASR 900s 不动。
5. **待办依据校验**（D-076）：owner/deadline 无依据→null；质量类结果要量化判据（D-080/K-34），不能只看字数/测试绿。
6. **`.env` 只写纯值**（K-35）：行内注释会被 compose `env_file` 当成值；改前备份、改后哈希校验（D-070）。
7. **allowlist 只能减不能增**：现为 **0 条技术债**；新豁免要先改检查器（如 M9 只扫注释）而不是塞清单（D-082）。
8. 测试入口 `scripts/test.ps1`（当前 **133/133**）；**不准改断言/编数字**；**证据行不删**（D-073）。
9. **今日配额 100/120 分钟、47/50 任务**——新卡实机只用 1 条 ≤25s 短样本，否则撞任务上限；`TranscriptionWorker` 295 行（≤300）；**一个仓同一时刻只允许一个写者**（D-079）。

## 3. 关键文件

`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md` · **`踩坑与复盘-面试用.md`（K-01~K-35）** · `spec.md`/`plan.md`（§5.1 三档）/`tasks.md`/`tasks-starter.md` · `decisions.md`（**85 条**，最新 D-083 T3.6 验收 + F-15）· `接入指南.md` · `review/2026-11-14-T3.6验收.md`（最新，20 份）· `配置清单.md` · `scripts/backup.ps1`/`restore.ps1` · `rules/dev.md`（§4.1）。

## 4. 未决

T3.7 导出/PASTE 契约（三节格式、字幕解析、跳过 ASR 的 source_type 口径）· **F-14** 本地档输出长度（T3.8 前）· **F-15** 抽取式不读词表（T3.8 评估）· 认证 T4.6 · 真机 UI T4.13 · 发布硬门禁 + 回滚 · 死配置盘点 T4.12 · LLM 真 120s 超时/长音频耗时未测（T3.8）。
