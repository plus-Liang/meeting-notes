# 交接：录音纪要平台（更新 2026-11-14 15:45，给下一个会话 / WorkBuddy）

## 1. 下一步

1. **T3.3 三档降级 + `ai_tier` 落库**（卡在 `tasks-starter.md` 末尾）：前置 F-8 `application.yml` 的 `max-tokens` 默认 1024→**8192**（用户已批）、F-5 待办提示词"最多 5 条"改 20 + 代码层截断、F-6 "不重试"断言改 `times(1)`；主任务：CLOUD/LOCAL(ollama)/EXTRACTIVE 判定 + 同构 DTO + `ai_tier` 诚实落库 + 零依赖也能产出。
2. 之后：T3.4 抽取式提质 → T3.5/T3.6 回流 → T3.7/T3.9/T3.10 → T3.8 周验收 → 第 4 周 T4.x → 第 5 周。

## 2. 别踩的坑

1. 对外 JSON 一律 **snake_case**；业务码 **7**（D-047）+ 通用码 **5**（D-057）；`transcript:""`=无语音但仍 DONE（D-048）；`todos` 的 `null`=没生成 / `[]`=确实没待办（D-072）。
2. **DONE 必须最后写**（D-069）：transcript → 摘要 → 待办 → 最后 DONE；已实测"首次 DONE 即就绪"（task 85），别再改回先 DONE。
3. **LLM 是推理模型**（D-071）：`max_tokens` 含思考，1024 必空 content、4096 不稳、8192 才稳；换模型/换路由都要重验。
4. **ASR 900s / LLM 120s 按 URI 路由**（D-058/D-068）；路由测试要断言方向，且"写反必须变红"（F-2 教训）。
5. 幂等权威 = DB 唯一约束（D-055）；DEAD 重传 = 复活；兜底 handler 敬畏框架语义（D-057）。
6. 启动期副作用尽力而为（rules/dev.md）；健康四项进 readiness（D-059）；ollama 探活同样不许拖垮启动（T3.3）。
7. **改 `.env`/配置**（D-070）：先备份 → **绝对路径**（.NET 用进程 CWD）→ 禁 BOM；中文控制台 GBK，管道按 GBK 解码。
8. 测试入口 `scripts/test.ps1`：跳过容器地址、shell 已设不覆盖；**不准改断言/编数字**；**验收样本任务行不删**（D-073）。
9. `TranscriptionWorker` **288 行**（上限 300）；commit 前 `check-maintainability` → exit 0；**禁止手改代码仓 `docs/` 与 allowlist**；死配置 16 / yml 55 键（D-054）。
## 3. 关键文件

`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md` · **`踩坑与复盘-面试用.md`（K-01~K-30；G 节=AI 写码方 7 坑 + 7 闸门）** · `spec.md`/`plan.md`（§5.1 三档）/`tasks.md`/`tasks-starter.md` · `decisions.md`（**77 条**，最新 D-071/072/073）· `接入指南.md` · `review/2026-11-14-T3.2验收.md`（最新，16 份）· `配置清单.md` · `scripts/backup.ps1`/`restore.ps1` · `rules/dev.md`（§4.1 配置文件铁律）。

## 4. 未决

T3.3 三档 + F-5/F-6/F-8 · T3.4 抽取式质量（分词库选型）· F-7 DONE 前最长等待（建议合并两次 LLM 调用）· 认证 T4.6 · 真机 UI T4.13 · 发布硬门禁 + 回滚 · 技术债：详情 API 每行 `new ObjectMapper()`（F-10）+ M5 2 条 · 死配置 16（T4.12）· LLM 真 120s 超时/长音频耗时未测（T3.8）。
