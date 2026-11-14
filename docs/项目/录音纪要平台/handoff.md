# 交接：录音纪要平台（更新 2026-11-14 19:25，给下一个会话 / WorkBuddy）

## 1. 下一步

1. **T3.5 改词回流（glossary）+ 待办勾选**（卡在 `tasks-starter.md` T3.4 之后）：用户改错词 → 替换该任务文本 + 写 `glossary`（wrong_text/right_text/hits）；待办勾选/删除持久化（`todos` jsonb 元素加 `done`，**无 DDL**）；**新增端点属公共面 → 开工确认先给 API 契约草案，批准后再写码**。
2. 之后：T3.6 术语注入（US-3 闭环）→ T3.7/T3.9/T3.10 → T3.8 周验收 → 第 4 周 T4.x → 第 5 周。
3. **待用户拍板**：F-11 本地档独立 `LLM_LOCAL_MAX_TOKENS` / `LLM_LOCAL_TIMEOUT_SEC`（新增配置键；不批则 LOCAL 档永远降级 EXTRACTIVE）。

## 2. 别踩的坑

1. 对外 JSON 一律 **snake_case**；业务码 7（D-047）+ 通用码 5（D-057）；`transcript:""`=无语音但仍 DONE（D-048）；`todos` 的 `null`=没生成 / `[]`=确实没待办（D-072）；`ai_tier` 必须诚实（D-020/D-075）。
2. **DONE 必须最后写**（D-069）：transcript → 结构化（**一次**调用）→ 最后 DONE；别改回先 DONE 或两次调用。
3. **LLM 是推理模型**（D-071）：默认 `max_tokens=8192`（F-8 已改 yml）；本地 1.5B 用 8192 会超 120s → 当前降级 EXTRACTIVE（F-11 待批）。
4. **三档是级联**（D-075）：CLOUD→LOCAL→EXTRACTIVE，失败才降一档、同档不重试；`OFFLINE_MODE=true` 是云档硬门禁（D-077，WARN `reason=offline_mode`）。
5. **待办字段回原文校验**（D-076）：owner/deadline 无依据→null、相对时间不换算、action 命中<0.5 整条丢。
6. **质量类判据要量化**（D-080/K-34）：不能只看字数/测试绿——用 `exactPrefix` 等可复算判据；样本要覆盖"句子少/极短/无标点"边界。
7. **改 `.env`/配置**（D-070）：先备份、绝对路径（.NET 用进程 CWD）、禁 BOM；默认值三处一致；中文控制台 GBK。
8. 测试入口 `scripts/test.ps1`（当前 **120/120**）：跳过容器地址、shell 已设不覆盖；**不准改断言/编数字**；**证据行不删**（D-073）；数字口径（D-074）。
9. `TranscriptionWorker` **295 行**（上限 300）；commit 前 `check-maintainability` exit 0；**禁止手改代码仓 `docs/` 与 allowlist**；**一个仓同一时刻只允许一个写者**（D-079/K-31）。

## 3. 关键文件

`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md` · **`踩坑与复盘-面试用.md`（K-01~K-34；G 节=AI 写码方 7 坑 + 7 闸门）** · `spec.md`/`plan.md`（§5.1 三档 / §5.4 详情页）/`tasks.md`/`tasks-starter.md` · `decisions.md`（**82 条**，最新 D-078/079/080）· `接入指南.md` · `review/2026-11-14-T3.4验收.md`（最新，18 份）· `配置清单.md` · `scripts/backup.ps1`/`restore.ps1` · `rules/dev.md`（§4.1）。

## 4. 未决

T3.5/T3.6 回流契约（端点设计待批）· F-11 本地档预算（待批配置键）· 认证 T4.6 · 真机 UI T4.13 · 发布硬门禁 + 回滚 · 技术债：详情 API 每行 `new ObjectMapper()`（F-10）· 死配置盘点 T4.12 · LLM 真 120s 超时/长音频耗时未测（T3.8）· `finish_reason=length` 实机未触发（F-12）。
