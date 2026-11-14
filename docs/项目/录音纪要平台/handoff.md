# 交接：录音纪要平台（更新 2026-11-14 21:00，给下一个会话 / 新会话 WorkBuddy）

## 1. 下一步

1. **T3.9 摘要模板机制（5 模板，US-12）**：卡在 `tasks-starter.md`（✅ 契约已在 D-085 预批准，**不用再报草案**）；先抽 `web/TaskExportController`（controller 已 294 行），再做 `TemplatePrompts`/`TemplateResolver`/落库/详情第 12 键。
2. **配额今日 50/50 已满**：写码 + 单测可立即做；实机（同语音 hash 变体 × meeting/course + 文件名自动推荐）**等次日 00:00 重置**，最多 3 条任务。
3. 之后：T3.10 格式扩展（amr/opus）→ T3.8 周验收（F-14/F-15/F-16/F-17/F-18 + 长音频/120s 超时）→ 第 4 周 T4.x → 第 5 周。

## 2. 别踩的坑

1. 对外 JSON 一律 snake_case（D-041）；业务码 7 + 通用码 5；`transcript:""`=无语音仍 DONE（D-048）；`todos` 的 null vs `[]`（D-072）；`ai_tier` 必须诚实（D-020/D-075）。
2. **DONE 最后写**（D-069）：transcript → 结构化（一次调用）→ 最后 DONE；PASTE 无音频分支同守此序（T3.7）。
3. **三档是级联**（D-075）；`OFFLINE_MODE=true` 云档硬门禁（D-077）；本地档预算 `LLM_LOCAL_MAX_TOKENS=512`/`LLM_LOCAL_TIMEOUT_SEC=180` 独立（D-081），别改回继承。
4. **`task_result` 早写不能盖掉**（T3.9）：提交时先写 `template`；worker/结构化只更新各自列，别整行覆盖/`SET template`。
5. **幂等键不含 template**（F-17）：同音频换模板会 reused 返旧任务；实机取证必须用 hash 变体样本（加 10ms 静音/丢 1~3 采样点）。
6. **`.env` 只写纯值**（K-35）：改前备份、改后哈希校验（D-070）；当前 hash `2BCC440F1C9E`。
7. **allowlist 只能减不能增**（现 0 条）；**行数 300/文件**：controller 294、worker 286、StructuringService 266、TaskService 253——新逻辑进独立小类。
8. 测试入口 `scripts/test.ps1`（现 **160/160**，ExecutionPolicy 需 `powershell -ExecutionPolicy Bypass -File`）；**证据行不删**（D-073）；**回报数字必须复算**（K-20）。
9. **一个仓同一时刻只允许一个写者**（D-079）；新会话开工先 `git pull` 确认 HEAD 含 `2027406` 之后的 docs 提交。

## 3. 关键文件

`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md` · `踩坑与复盘-面试用.md`（K-01~K-35）· `spec.md`/`plan.md`/`tasks.md`/`tasks-starter.md`（T3.9 卡）· `decisions.md`（**86 条**，最新 D-085）· `接入指南.md` · `review/2026-11-14-T3.7验收.md`（最新，21 份）· `配置清单.md` · `scripts/backup.ps1`/`restore.ps1` · `rules/dev.md`（§4.1）。

## 4. 未决

**F-14** 本地档输出长度（T3.8 前评估）· **F-15** 抽取式不读词表（T3.8）· **F-16** 字幕编码/纯数字行边界（P3）· **F-17** 幂等 vs 换模板重跑（T4.1）· **F-18** EXTRACTIVE 模板分化（T3.8 判 spec §6.1 是否必须）· `/result` 不拆（D-085 已决）· 认证 T4.6 · 真机 UI T4.13 · 长音频/LLM 真 120s 超时未测（T3.8）。
