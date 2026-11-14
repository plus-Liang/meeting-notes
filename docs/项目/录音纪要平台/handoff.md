# 交接：录音纪要平台（更新 2026-11-14 18:40，给下一个会话 / WorkBuddy）

## 1. 下一步

1. **T3.4 抽取式提质**（卡在 `tasks-starter.md` T3.3 之后）：T3.3 已交付**最小可用**抽取式（分句/词频/规则待办/无分词库）与三档**级联**降级；T3.4 只做质量——分词或 n-gram 选型、关键句更稳、规则待办更准、**LOCAL 档预算**（F-11：独立 max_tokens/timeout 属新增配置键，须用户批准）+ JSON 抽取容错。
2. 之后：T3.5/T3.6 回流（glossary/术语注入）→ T3.7/T3.9/T3.10 → T3.8 周验收 → 第 4 周 T4.x → 第 5 周。

## 2. 别踩的坑

1. 对外 JSON 一律 **snake_case**；业务码 **7**（D-047）+ 通用码 **5**（D-057）；`transcript:""`=无语音但仍 DONE（D-048）；`todos` 的 `null`=没生成 / `[]`=确实没待办（D-072）；`ai_tier` 必须诚实（D-020/D-075）。
2. **DONE 必须最后写**（D-069）：transcript → 结构化（**一次**调用，摘要+关键词+待办）→ 最后 DONE；别改回先 DONE 或两次调用。
3. **LLM 是推理模型**（D-071）：`max_tokens` 含思考，默认 **8192**（F-8 已改 yml）；本地 1.5B 用 8192 会超 120s → 当前降级 EXTRACTIVE（F-11）。
4. **三档是级联**（D-075）：CLOUD→LOCAL→EXTRACTIVE，失败才降一档、**同档不重试**；`OFFLINE_MODE=true` 是云档硬门禁（D-077，会 WARN `reason=offline_mode`）。
5. **待办字段回原文校验**（D-076）：owner/deadline 无依据→null、相对时间不换算绝对日期、action 二元组命中<0.5 整条丢。
6. **ASR 900s / LLM 120s 按 URI 路由**（D-058/D-068）；路由测试要断言方向，写反必须变红（F-2 教训）。
7. **改 `.env`/配置**（D-070）：先备份 → **绝对路径**（.NET 用进程 CWD）→ 禁 BOM；中文控制台 GBK，管道按 GBK 解码；默认值三处一致（`.env`/`.env.example`/yml）。
8. 测试入口 `scripts/test.ps1`：跳过容器地址、shell 已设不覆盖；**不准改断言/编数字**；**验收样本任务行不删**（D-073）；数字口径（D-074）。
9. `TranscriptionWorker` **295 行**（上限 300）；commit 前 `check-maintainability` → exit 0；**禁止手改代码仓 `docs/` 与 allowlist**；**一个仓同一时刻只允许一个写者**（D-079/K-31）。

## 3. 关键文件

`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md` · **`踩坑与复盘-面试用.md`（K-01~K-33；G 节=AI 写码方 7 坑 + 7 闸门）** · `spec.md`/`plan.md`（§5.1 三档）/`tasks.md`/`tasks-starter.md` · `decisions.md`（**81 条**，最新 D-077/078/079）· `接入指南.md` · `review/2026-11-14-T3.3验收.md`（最新，17 份）· `配置清单.md` · `scripts/backup.ps1`/`restore.ps1` · `rules/dev.md`（§4.1 配置/写盘铁律）。

## 4. 未决

T3.4 抽取式质量 + F-11 本地档预算（需批准新键）· 认证 T4.6 · 真机 UI T4.13 · 发布硬门禁 + 回滚 · 技术债：详情 API 每行 `new ObjectMapper()`（F-10）+ M5 2 条 · 死配置（T4.12；`OLLAMA_*` 已被 T3.3 消化，剩平台/INBOX 等）· LLM 真 120s 超时/长音频耗时未测（T3.8）· `finish_reason=length` 实机未触发（F-12）。
