# 交接：录音纪要平台（更新 2026-11-14 14:55，给下一个会话 / WorkBuddy）

## 1. 下一步

1. **T3.2 待办抽取 + 两处前置收口**（卡在 `tasks-starter.md`）：前置 ① **F-1**：现在"转写落库并置 DONE"早于摘要（实测 3.3s 窗口，违反 D-069）→ 改成摘要后再置 DONE；
   前置 ② **F-2**：`AsrHttpClientConfigTest` 路由方向没真断言（映射写反仍绿）→ 改方向断言；主任务：提示词输出 `{"todos":[{action,owner,deadline}]}`，未知就 null、不编造，落 `task_result.todos`，详情 API 只增 `todos`，单测 ≥61。
   → 之后：T3.3 三档 → T3.4 抽取式 → T3.5/T3.6 回流 → T3.7/T3.9/T3.10 → T3.8 周验收 → 第 4 周 T4.x 交付 → 第 5 周。

## 2. 别踩的坑

1. 对外 JSON 一律 **snake_case**；业务码 **7 个**（D-047）+ 通用码 **5 个**（D-057）；`transcript: ""` = 未识别到语音但仍 DONE（D-048）。
2. **DONE 必须在结果全部就绪之后写**（D-069）：摘要/待办先写，最后置 DONE；失败也 DONE，但**不许先 DONE 后补**（Webhook/轮询会读到半成品）。
3. **ASR 900s / LLM 120s 按 URI 路由**（D-058/D-068）：**不许给 LLM 复用它 900s**（会拖死单线程 worker）；路由测试要断言**方向**，别写自比较。
4. 幂等权威 = 数据库唯一约束（D-055）；DEAD 重传 = 复活（重置 attempts/status/error_code + 重入队 + 删 `dead_letter`）；兜底 handler 敬畏框架语义（D-057）。
5. 启动期副作用尽力而为（rules/dev.md）；健康四项进 readiness，依赖挂 → 503 DOWN（D-059/D-060）；启动恢复只动 RUNNING（D-061 单实例假设）。
6. **改 `.env` / 关键文件**（D-070）：先备份 → 用**绝对路径**（PowerShell 的 .NET 用进程 CWD，`Set-Location` 不管用）→ **禁 BOM**；中文 Windows 控制台是 GBK，管道捕获按 GBK 解码（否则自己造"乱码"假象）。
7. 测试入口 `scripts/test.ps1`：会跳过 `.env` 的容器地址、shell 已设的键不覆盖；全量需 `ASR_SMOKE_AUDIO`（未设 → exit 3），`-UnitOnly` 跳过冒烟；**不准改断言/编数字**。
8. `TranscriptionWorker` **285 行**（上限 300）：别往里塞逻辑，要加先抽类；commit 前 `tools\check-maintainability.ps1` → exit 0。本机坑：`SERVER__PORT` 注入 / curl 先清 proxy / `docker run -v D:\...` 卡 `Created` / 连库 `-U mn` / 中文 `.ps1` 必须 UTF-8 带 BOM。
10. **禁止手改代码仓 `docs/`（生成副本）与 allowlist（只减不增）**；配置项必须有人用（D-054，死配置 16 / yml 55 键）。

## 3. 关键文件

`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md` · `spec.md`/`plan.md`/`tasks.md`/`tasks-starter.md` · `decisions.md`（**74 条**，最新 D-070）· `接入指南.md` · `review/2026-11-14-T3.0-T3.1验收.md`（最新）· `配置清单.md`（yml 55 / `.env.example` 71 / 死配置 16）· `scripts/backup.ps1`/`restore.ps1` · `rules/dev.md`（§4.1 配置文件铁律）。

## 4. 未决

T3.2 起 W3 剩余 · 认证 T4.6 · 真机 UI T4.13 · 发布硬门禁 + 回滚 · 可维护性技术债 2 条（M5）· 死配置 16 键（T4.12）· LLM 真超时/长音频摘要耗时未实测（T3.8 补）。
