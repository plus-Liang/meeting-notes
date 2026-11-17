# 任务清单：录音纪要平台（v1，**5 周** / 约 **86 小时**）

**对应**: `spec.md` v6 + `plan.md` + `接入指南.md` + `配置清单.md` + `链接处理方案.md`
**Created**: 2026-11-13（v6：按用户决定**延到第 5 周**；加链接导入 L1/L2/BYOF）
**时间预算**: 5 周，累计 **86h**（83h 基线 + 5 张追加验证卡 3h，见文末"工期影响"）
**交付**: 2026-12-16 可演示（原 12-11 后移 5 天）；**简历 12.15 写**（原 12.10 后移）
**规则**: 每个任务必须带 DoD 与验证命令；**无证据的"完成"视为未完成**。
**超时规则**: 单任务连续投入 > 8h 未跑通 → 降级为最小可用版或移出 v1（D-015）。

---

## 测试要求（每周都必须满足，不是最后一周才补）

> 来源：`docs\AI做APP-工作流.md` 阶段 7「每阶段加并运行测试 + 失败先定位修复 + **禁止删测试/跳测试/降标准作弊** + 全部完成跑全测」。

| 周 | 必须新增的测试 | 覆盖 |
|---|---|---|
| W1 | 上传校验单测（magic number / 大小 / 时长 / 格式） | N6、US-1 场景 2 |
| W2 | 幂等单测、状态机迁移单测、健康检查集成测试 | N1、N2、N3、N5 |
| W3 | JSON Schema 解析单测（含畸形输出）、三档降级选择单测、模板选择单测 | N15、US-3、US-12 |
| W4 | 端到端测试（E2ETest）+ 备份/恢复验证脚本 | N14、N18–N20 |

**禁止的作弊行为**（一旦发现，该任务判为未完成）：
1. 删除测试来让构建通过；2. 跳过测试（`-DskipTests`、`@Disabled`）当作通过；
3. 降低断言标准（`assertNotNull` → `assertTrue(true)`）；4. mock 掉被测对象本身来"证明"它工作。

**每周收工硬动作**：`mvn test` 全绿，结果（BUILD SUCCESS + 用例数）写进 `state.md`。

---

## 阶段 0：底座验证（约 8h，**不通过不进第 1 周**）—— ✅ **全部完成（2026-11-13）**

| ID | 任务 | DoD | 结果 / 证据 |
|---|---|---|---|
| **T0.1** | 起 ASR 服务（**改为自建容器**，D-008R） | 容器 healthy，接口可访问 | ✅ `infra/asr/`，`docker compose up -d asr` → healthy |
| **T0.2** | curl 转写真实音频 | 返回中文、有标点 | ✅ `POST /v1/audio/transcriptions` → 简体带标点 |
| **T0.3** | 记实测 RTF 与资源占用 | 写入 `state.md` | ✅ RTF **0.105 / 0.113**（8.44h ≈ 57min），见 `T0.1-ASR验证结果.md` |
| **T0.4** | Spring AI 最小转写跑通 | 独立复跑 exit 0 | ✅ `25528b9` + `review/2026-11-13-T0.4验收.md` |
| **T0.5** | 云 LLM 通道 + **无 key 可启动** | 真实摘要 + 清空 key 仍能启动 | ✅ `483d6f2` + `review/2026-11-13-T0.5验收.md`（DeepSeek 实测 3 条摘要；无 key 时 ChatModel=0、health UP） |

**下一步**：**第 4 周 T4.x**（首卡建议：T4.1 列表筛选+批量重试+设置页，含 **F-15** 抽取式读词表 ≤30 行；写法见 `tasks-starter.md` 新卡）→ 第 5 周。**阶段 1 全 ✅；W2 全 ✅；W3 全 ✅（含 T3.8 周验收，W3 门禁 2026-11-15 关闭，D-089）**。
**T1.5 开工前**（已完成）：先修 T1.4 审查的 P1/P2（snake_case 契约 + 幂等真实状态 + `user_id` 过滤，D-040/D-041），一个 commit（`7c90e9a`）。
**已处理**：`glossary."right"` 改名 `right_text`（D-037，用户拍板）；列名已在真实 PG16 空卷验证。

**失败预案**：①自建 faster-whisper ASR 起不来 → 云 ASR（OpenAI 兼容）②转写太慢 → 换 `small` 模型 / 降并发 ③全不行 → 先做 PASTE 入口不阻塞。

---

## 第 1 周：骨架 + 配置基线 + 单文件闭环（约 13h）

| ID | 任务 | DoD | 验证命令 / 期望 |
|---|---|---|---|
| **T1.1** | 代码仓骨架 + 首个 commit + `.gitignore` 生效 | `.env` 不在版本库 | ✅ `git ls-files` 无 `.env`；`git grep 'sk-'` 工作区无命中（历史命中均为文档自查命令文本）；`6b30779` + 审查报告 |
| **T1.2** | `docker compose up` 起 pg/redis/minio/asr/app | 干净目录一次成功 | ✅ `docker compose down -v` → `up -d --build` → **5/5 healthy**；卷创建 11:21:25Z、Flyway 11:21:40Z（干净卷实锤）；`fb45588` |
| **T1.3** | Flyway `V1__init.sql` 生效（7 张表） | 迁移可重复执行 | ✅ 真实 PG16.16：8 表（7 业务 + `flyway_schema_history`）；重启 → `Schema "public" is up to date`（幂等） |
| **T1.4** | 上传接口：magic number / 大小 / 时长 / **格式**校验 → MinIO → task(PENDING) | 坏文件被拒、好文件入 PENDING | ✅ `3ce9386` + `review/2026-11-13-T1.4验收.md`（5 场景真机：400/201/幂等/413/400/429；hash 与 ffprobe 时长我独立复算一致）。**遗留 P1/P2 在 T1.5 前修：snake_case 契约、幂等状态失真、user_id 过滤（D-040/D-041）** |
| **T1.5** | worker 消费：抽音轨 → ASR → 写结果 → DONE | 5 分钟 mp3 端到端出全文 | ✅ `7c90e9a`（契约）+ `48b4b74`（worker）+ `review/2026-11-13-T1.5验收.md`。真机：397s 中文音频 → `DONE` **31s**、transcript 1853 字；失败路径 **9s** 走完 `RUNNING→RETRYING×2→DEAD`（attempts=3、`ASR_ERROR`、dead_letter 有原因）；`GET /tasks/999999` → `404 TASK_NOT_FOUND`；重传已 DONE 文件 → `reused:true` + 真实状态；`mvn test` **14/14 绿**（需 `ASR_BASE_URL=http://localhost:8001`）。**遗留**：空文本语义改 DONE（D-048，T1.6 前置）／DEAD 重传重投（D-049，T2.1）／内部重试已收敛（D-046） |
| **T1.6** | 三页面（提交/列表/详情）静态 HTML，结构按 spec §5 | 可用、无 JS 报错 | ✅ `264def4` + `review/2026-11-14-T1.6验收.md`。真机：`GET /` **200/3399B**；三页共用 `style.css`、**0 处硬编码地址**；6s 正弦波 → `DONE` + `transcript:""`（D-048）；停 ASR → `DEAD` + `error_code=ASR_ERROR`（S-3）；`mvn test` **17/17 绿**（新增 `TranscriptionWorkerTest` 3 例）。越界已修：手改生成副本（接入指南）已移植权威版；allowlist 误登记 TranscriptionWorker 已删除 |
| **T1.7** | 配置外置基线 | `配置清单.md` §2 三条命令无异常 | ✅ 只读卡，证据在 `review/2026-11-14-T1.7-T1.8验收.md`（无 commit）。我逐条复跑：命令1 **0 命中**；命令2 **10 行**均可辩护（`/inbox` 属 §3 例外）；命令3/4 yml **53** ↔ `.env.example` **68**，白名单外差集 **0**；`docker compose config` **0 告警**；魔法阈值扫描 **0**；**反例** `DB_URL`→`${DB_URL_TYPO}` → 启动即 `'url' must start with "jdbc"` exit 1（已还原）；脚本 exit 0 |
| **T1.8** | 上传校验单测 | `mvn test` 全绿 | ✅ `a352e5f` + 同上报告。2 个测试文件 **+164/−0**、**主代码零改动**、**0 行断言被删**；`mvn test` **23/23** exit 0（MediaMagic 11 / 新增 `TaskServiceValidationTest` 4 / `TranscriptionWorkerTest` 5 / NoKeyStartup 1 / 冒烟 2）；新增用例：MZ 改名→400 `INVALID_MEDIA`、超大小→413 `SIZE_EXCEEDED`（消息带上限）、90s>60s→400 `DURATION_EXCEEDED`（消息带实际时长）、正向入队+配额、失败 1/3→`RETRYING`+重入队、3/3→`DEAD`+`dead_letter` 带原因；脚本 exit 0。**缺口**：状态机用例白盒调私有 `markFailure`，`process()→loop()` 接线靠 T1.5/T1.6 真机证据兜（F-2） |
| **T1.9** | **`scripts/test.ps1` 测试入口** | 裸 shell 一条命令跑绿 | ✅ `24abd45` + `review/2026-11-14-T1.9-T2.1验收.md`。我复跑：裸 shell **26/26 exit 0**；`-UnitOnly` **24/24 exit 0**（他报 21 是 T2.1 之前的 23 例口径）；反例 `ASR_BASE_URL=:59999` → **exit 1**（冒烟真红）；BOM `239,187,191`；只打印 key 名不打印 value；脚本 exit 0 |

**W1 过关**：US-1 场景 1 通过；N11 有机器判据；测试全绿。

---

## 第 2 周：任务系统可靠性 + 备份（约 14h）

| ID | 任务 | DoD | 验证命令 / 期望 |
|---|---|---|---|
| **T2.1** | 幂等：`content_hash` 唯一 + 命中返回 `reused:true` + **命中 DEAD 时重新投递**（D-049） | 同文件两次 → 1 任务；DEAD 文件重传 → 重新入列 | ✅ `300db82` + `review/2026-11-14-T1.9-T2.1验收.md`。V2 迁移（查重删除 + 撤全局 `ux_task_hash` + `UNIQUE(user_id, content_hash)`）+ `ON CONFLICT DO NOTHING` 幂等（**D-055 批准偏离卡片的 catch 方案**）+ 复活重投（attempts=0/PENDING/error_code=NULL）。我复跑：`down -v` 后 Flyway V1+V2 全 success、真实 PG 唯一索引在；连传两次同 id（第二次 `reused:true`+真实 `DONE`）；**并发**两请求同 id、只 1 行；DEAD → 重传 → PENDING → **0.1s DONE**；复活不重复扣配额；**429 与 MinIO 故障都不留孤儿 PENDING 行**（事务回滚生效）；`scripts/test.ps1` **26/26**。**遗留**：复活后 `dead_letter` 残留（F-1）、存储故障 500 无 `code`（F-2）→ **T2.11** |
| **T2.2** | 重试 + 超时 + 死信 | 人为失败 → RETRYING → DEAD；**失败发现要快** | ✅ `d9417e5` + `review/2026-11-14-T2.2验收.md`。新增 `config/AsrHttpClientConfig`（`RestClientCustomizer`：connect 5s / read 900s，值全走 `ASR_CONNECT_TIMEOUT_SEC`+`ASR_TIMEOUT_SEC`）、`.env.example` 加键、删 worker 残留 import。我复跑：停 ASR → **首次失败 3.5s、DEAD 7.7s**（基线 78s）；**黑洞 IP 10.255.255.1 实验 DEAD 15.9s（3 次 ×~5s）= connect 超时真的生效**；启 ASR 重传复活 → DONE 9.8s；**103.8MB / 56.7 分钟长音频 → DONE 334.6s、内存 735→740MiB 无 OOM**；新键有消费方 + 进 example；孤儿键 18→17；单测 **42/42**。**观察**：`SimpleClientHttpRequestFactory` 默认缓冲请求体、`setBufferRequestBody` 在 Spring 6.2 已弃用 → T2.6 换 `JdkClientHttpRequestFactory` 更稳（F-1） |
| **T2.3** | 崩溃恢复：启动回收 RUNNING 僵尸 | 重启不永久卡住 | ✅ `11744ee` + `review/2026-11-14-T2.12-T2.3验收.md`。新增 `worker/StartupRecovery`（`ApplicationRunner`，与消费 worker 解耦）：`UPDATE task SET status='PENDING' WHERE status='RUNNING' RETURNING id` → 逐个 `leftPush`，0 也打日志；单实例假设写进注释；H2 空库 `DataAccessException` → WARN 跳过（保 D-035）。我复跑：`probe.wav` 处理中 `docker restart app` → 重启日志「回收 1 个 RUNNING 僵尸」→ 12s 内 `DONE`，**不停 RUNNING**；`TranscriptionWorker` 拆后 271 行 |
| **T2.4** | 健康检查四项（DB/Redis/MinIO/ASR） | 任一不可用整体 DOWN | ✅ `74c0ed8` + `review/2026-11-14-T2.4验收.md`。新增 `health/MinioHealthIndicator`（headBucket + apiCallTimeout 2s）与 `health/AsrHealthIndicator`（GET /health + 独立 2s 探活工厂，不复用 900s 业务超时）；readiness 组 = `readinessState,db,redis,minio,asr`；H2 测试用 `management.health.minio/asr.enabled=false` 关闭。我复跑：基线 four-UP；**停 MinIO → DOWN(503, 1.42s)**；**停 ASR → DOWN(503, 2.02s)**；**停 Redis → DOWN**；恢复全 UP；`/actuator/health/readiness` 组内四项齐全；上传仍 DONE；单测 **47/47** |
| **T2.5** | 结果缓存：同 hash 复用 | 不重复转写 | ✅ **由 T2.1 幂等覆盖（D-060）**：同文件二次上传 `reused:true`，ASR 转写请求计数 **4 → 4（无新增）**，见 `review/2026-11-14-T2.4验收.md` §1.5。不单独写代码 |
| **T2.6** | 配额 + 并发控制（ASR 并发可配） | 超限 429 + `Retry-After` | ✅ **按 D-061 拆解**：v1 单线程消费 → 天然串行（"顺序执行不死锁"自动成立）；配额 429 + `Retry-After` 已在 T1.4 实现（`ApiExceptionHandler`），并入 **T2.9** 统一验证；`app.asr.max-concurrency` 登记 P2 |
| **T2.7** | 结构化 JSON 日志，taskId 贯穿 | 一条命令捞全链路 | ✅ `ae982d8` + `review/2026-11-14-T2.7-T2.8验收.md`（D-062）。`config/JsonLoggingEnvironmentPostProcessor`（`app.log-json=true` → `logging.structured.format.console=logstash`，Boot 内建零依赖；false → 属性缺席回退文本）；worker 处理期 `MDC.put("taskId")` + `finally remove`，流水线 5 条 INFO 的 taskId 为**独立字段**；`StartupRecovery` 改 fluent `addKeyValue`；+3 单测。我复跑：task 45/46 各 **5 条** taskId 行、逐行 `ConvertFrom-Json` **10/10**、两任务不串号；`LOG_JSON=false` 探针 **0 行 JSON**；**50/50** exit 0 |
| **T2.8** | **备份与恢复**（US-11）：`backup.ps1` + `restore.ps1` + 宿主定时 | 备份可核对；删卷能恢复 | ✅ `6ebcdef` + 同上报告（D-063/D-064）。`backup.ps1`：`pg_dump -Fc`（容器内落盘 + `docker cp`）+ MinIO volume tar（`python:3.12-slim`）+ `manifest.json`（5 表行数/对象数/大小/sha256）+ `-Keep 7`；`restore.ps1`：`-Force` 闸门（exit 10）、校验（11）、停 app、`pg_restore --clean --if-exists`、volume 恢复、按 manifest 自检（14）；`backups/` gitignore；不含 `.env`。我复跑：**10 个 DONE 任务** → 备份 → `down -v` → `up`（API `[]`）→ 恢复 → **id/status/hash/transcript md5/长度 10/10 逐字一致**、自检全 OK、health UP；恢复后重入队 task 43 → `DONE`（对象真能读）；恢复后可上传新任务；`-Keep 2` 生效；无 `-Force` exit 10。**自动备份＝宿主 cron/任务计划（README 给示例），不做内置调度** |
| **T2.9** | 本周单测/验收 + 三处收口 | `mvn test` 全绿 + 429 真 HTTP | ✅ `c9ad68c` + `review/2026-11-14-T2.9验收.md`。429 真 HTTP（`Retry-After: 64852` + `QUOTA_EXCEEDED`）、restore preflight 两分支（**exit 20** / 恢复 10/10 自检 OK）、回归四连全过；**但 3 处遗留**：F-5 干净 shell 全量必红（`.env` 容器地址进测试进程）、F-1b 中文乱码仍在（只给 fork JVM 设了编码）、F-6 配额日切按 UTC → 全部进 **T3.0** |
| **T3.0** | **W3 前置小修**（0.5h，W2 关账条件） | 干净 shell 全量 50/50 + 中文可读 + 配额按本地日切 | ✅ `0072fdd` + `review/2026-11-14-T3.0-T3.1验收.md`（D-066/D-067/D-070）。`test.ps1` 跳过 `.env` 的 `ASR_BASE_URL` + "shell 已设不覆盖" + `MAVEN_OPTS` 编码；compose app/postgres `TZ`；`.env.example` 加 `TZ`。我复跑：干净 shell **57/57**、`ASR_PORT=9999` 必红 exit 1、两边 **CST**、**`Retry-After` 33491 = 距本地零点 33491s**（逐秒吻合） |
| **T2.10** | **服务端任务列表 API**（D-053） | 换浏览器/清缓存仍能看到历史任务 | ✅ `08e862d` + `review/2026-11-14-T2.11-T2.10验收.md`。`GET /api/v1/tasks?status=&limit=&before_id=`：keyset（`id<before_id ORDER BY id DESC LIMIT n+1`，禁 OFFSET）、`LIMIT+1` 探测下一页、`status` 过滤、`original_name` 从 `source_uri` 解析、snake_case。我复跑：默认列表 `[23,22,21]`+`next_before_id:null`；`limit=2`→`[23,22]`、游标 22→`[21]` **不重不漏**；`status=DONE` 过滤正确；`limit=51/0/abc`、`before_id=xyz` 全 400；`tasks.html` 200/3503B、只调服务端 API、localStorage 仅兜底、3s 轮询仅在有未完成任务时；截图（无痕 4 条历史）已核对；`style.css`/`app.js` 未动。**缺陷**：参数错误码借用 `INVALID_MEDIA`（F-2）、`status=BOGUS` 静默 200（F-3）→ **T2.12** |
| **T2.11** | **契约与死信收口**（D-056） | 死信列表里不出现 DONE 任务；500 响应体带 `code` | ✅ `08fbfd6` + 同上报告。复活时删 `dead_letter`；存储故障 → `500 STORAGE_ERROR`；加 `@ExceptionHandler(Exception)` 兜底 `500 INTERNAL_ERROR`（不泄细节）；`test.ps1` 去掉本机路径兜底（未设 `ASR_SMOKE_AUDIO` → exit 3）。我复跑：DEAD 复活后死信 **1→0 行**；停 MinIO 上传 → **500 + `code:"STORAGE_ERROR"`** 且无孤儿行；单测 **33/33** exit 0；脚本 exit 0。**回归缺陷**：兜底把框架 4xx 全吞成 500（F-1）→ **T2.12** |
| **T2.12** | **错误语义收口**（D-057） | 404 仍 404、405 仍 405、415 仍 415、类型错 400 | ✅ `9bc93e4` + `review/2026-11-14-T2.12-T2.3验收.md`。兜底 handler 改为「`ErrorResponse` 按原状态码 + 通用码，真未知才 500」；参数码改 `INVALID_PARAM`；`status` 白名单严格校验。我复跑七连击全对：`/api/v1/nope` 404 `NOT_FOUND`、`/nope.html` 404、`/tasks/abc` 400 `INVALID_PARAM`、`DELETE` 405、JSON POST 415、`?limit=abc` 400、`?status=BOGUS` 400；回归：`/tasks/999999` 仍 404 `TASK_NOT_FOUND`、`?status=DONE` 仍 200、`GET /` 200；单测 **40/40** |

**W2 过关**：US-2 三场景全过；N1–N5、N8、N9、N18–N20 有证据。

---

## 第 3 周：结构化 + 三档 AI + 模板 + 闭环回流（约 17h）

| ID | 任务 | DoD | 验证命令 / 期望 |
|---|---|---|---|
| **T3.1** | 摘要生成：①云 `ChatModel` | ≥3 条摘要且不编造 | ✅ `ffbc95c` + `review/2026-11-14-T3.0-T3.1验收.md`（D-068/D-069）。`ai/SummaryService`：严格 JSON 提示词（严禁编造）+ 解析失败 WARN 截 200 字不重试 + 无 ChatModel 跳过；`AsrHttpClientConfig` 按 URI 路由超时（转写 900s / chat `LLM_TIMEOUT_SEC` 120s）；worker +5 行、详情 API 只增 `summary/keywords/ai_tier`；单测 50→**57**。我复跑：3 个不同 hash 样本 → `ai_tier=CLOUD`、摘要 160~170 字、keywords 8 个、要点全部可在 transcript 找到；F-1（DONE 写早于摘要 3.3s）→ **T3.2 收口** |
| **T3.2** | 待办抽取：JSON Schema + 解析失败重试 | 含 `action/owner/deadline` | ✅ `cd35a6b` + `review/2026-11-14-T3.2验收.md`（D-069 落地/D-071～D-073）。`ai/TodoService`：严格 JSON + 严禁猜 owner/deadline（未知 null）+ 空 action 丢弃 + 解析失败 WARN 截 200 字不重试 + 无 ChatModel 跳过；worker 改为 transcript → 摘要 → 待办 → **最后** DONE（`finalizeResult`，**298 行**）；详情 API 只增 `todos`；F-2 路由断言换 `assertSame` 双向 + customizer 端到端。我复跑：干净 shell **68/68**、维护性 exit 0；F-2 两层变异各造一次红；task 85 首次 DONE 即 summary 172/8 keywords/2 todos、日志四步顺序正确；无 key task 86 = DONE+全 null 无 500；22 条待办可追溯 0 编造。遗留 F-5～F-10 → **T3.3** |
| **T3.3** | **三档级联降级 + `ai_tier` 落库** + T3.2 遗留收口 | 按 ①→②→③ 判定并级联降档；`ai_tier` 诚实落实际档 | ✅ `991f429` + `review/2026-11-14-T3.3验收.md`（D-075/D-076/D-077 落地）。`ai/AiStructuringRouter`（首选档 + 级联 + D-077 离线硬门禁 WARN）、`ai/StructuringService`（**一次**调用出 summary/keywords/todos、失败降档不重试、⑦预算代码层截断）、`ai/ExtractiveStructurer`、`ai/TodoGrounding`（D-076 三档统一依据校验）、`config/OllamaChatModelConfig`（LOCAL 独立 ChatModel）、`TodoService` 收编为 todos 解析器、删除 `SummaryService`；worker **295 行**、详情 API 零改动（`ai_tier` 已有）。F-5/F-6/F-7/F-8 收口。我复跑：97/97 exit 0、维护性 exit 0、双变异各造一次红、三档实机 **95 CLOUD / 96 EXTRACTIVE / 97 离线门禁 EXTRACTIVE+WARN / 98+101 LOCAL 失败→降级 EXTRACTIVE+WARN / 100 空音频四字段 null**、错误四连与详情 11 键不变。**遗留 F-11**：LOCAL 档未产出 `ai_tier=LOCAL`（1.5B + max_tokens 8192 触顶 124.9s；512 时输出带散文致 JSON 解析失败）→ T3.4/T3.8 |
| **T3.4** | **抽取式提质 + 结构化输出容错** | EXTRACTIVE 摘要非"前 N 字截断"、kw ≥5、todos ≥1；模型输出带散文/围栏也能解析 | ✅ `27d7ed8` + `review/2026-11-14-T3.4验收.md`（D-080）。四级分句 fallback（`ai/ExtractiveSegmenter`：句末标点→逗号/空格→连接词边界→50 字窗口）、打分加**结尾加分**、拼接**丢句不截句**、keywords 阈值 3→2 + 口头禅表 + 数字过滤、todos 触发词扩充 + 口头前缀剥离、`ai/JsonExtractor`（```json 围栏 + 括号配对 + 转义处理）接入 `parse`。**同时抓出并修复 T3.3 遗留 bug**（`summaryOf` 循环上界用 `indexes.size()` 被 remove 缩小 → 少选一半句子，实机 104 暴露）。我复跑：120/120 exit 0、维护性 exit 0、双变异各造一次红（17/3F、28/8F）、**我独立实机 108/109 EXTRACTIVE（181/156 字、kw 10、todos 4/3、非前缀）**、110 CLOUD 141 字、112 空音频四字段 null、错误四连与详情 11 键不变 |
| **T3.5** | 改词 → 写 `glossary`；待办可勾选/删除 | 改一次即入库；勾选持久 | ✅ `9a34fcf` + 复核 `9f05c69` + `review/2026-11-14-T3.5-T3.11验收.md`。三个写端点（corrections / PATCH todos / DELETE todos）+ `glossary` upsert（hits 递增、priority 保原值）+ `done` 放 jsonb（视图 record，`TodoItem` 不动）；**M5/M9 按纪律清账**：`ApiException`/`TaskCreatedResponse` 迁 `com.meetingnotes.common`，M5 零容忍、M9 只扫注释，allowlist **技术债清零（净减 5 行）**。我独立实测（task 119）：改词 `replaced_count=1`、重复改 0 且 hits 1→2、勾选/删除刷新后持久、反例 400/404/415 全对、详情 11 键不变；133/133 exit 0；M9 闸门我独立造红（真 TODO 被抓）+ 还原绿 |
| **T3.6** | 术语注入下次转写（US-3 闭环） | 第二次输出正确 | ✅ `a8845b4` + `review/2026-11-14-T3.6验收.md`（D-083）。`GlossaryTermSource`（hits desc/priority desc 取 ≤20 条、总长 ≤400 字、空表返回空串 → prompt 逐字节不变）→ `StructuringPrompt` 注入段 → 日志 `术语注入：N 条/长度 M 字`（不泄露词条）；CLOUD/LOCAL 生效、EXTRACTIVE 不调 LLM（F-15）。我独立复跑：139/139 exit 0、变异造红后绿；**独立实机 task 123**（同段语音去 3 采样点 → 新 hash）：transcript 仍含 ASR 错词"工作副盘"，summary/keywords 全部输出"工作复盘/AI Coding"；写码方 task 122 结论一致 |
| **T3.7** | 导出 Markdown + `PASTE` 入口（含 ass） | 文件含三节；srt 秒出纪要 | ✅ `2027406` + `review/2026-11-14-T3.7验收.md`（D-084/D-085）。`web/TaskMarkdownExporter`（三节/done/（无））+ 导出端点；`task/TextTaskService`（PASTE/字幕共用：解析后文本 hash、`source_type=PASTE`、`duration_sec=null`、幂等同序 + DEAD 复活）+ `upload/SubtitleParser`（srt/ass、UTF-8→GBK→400、5MB→413、>10 万字符→400）+ worker PASTE 无音频分支（抽 `PasteTextReader`/`MonoWavExtractor` 保行数闸 286）；`durationSec`→`Long`。我独立复跑：160/160 exit 0、可维护性 exit 0、**我另做变异红（4 run/3 failures）→还原绿**；实机 task 124（PASTE，2.57s DONE、跳过 ASR、不变量 hash==sha256(对象字节) 成立、复用不扣配额）、导出 123/124 三节、负例 404/400/413/415/429 全对；配额 50/50、102/120（回报 103 已更正）。**F-16** 字幕编码边界、**F-17** 幂等不含 template → T3.9/T3.8 |
| **T3.8** | 本周单测（Schema 畸形、三档选择、模板选择）+ 周验收 | `mvn test` 全绿 + 长音频/超时/降级有实物证据 | ✅ `3b80f02` + `review/2026-11-15-T3.8验收.md`（D-089）。**主代码零改动**（1 文件 +78/-2 仅 `StructuringServiceTest`）：+4 条畸形 JSON 矩阵（summary 非字符串 / keywords 混合类型 / todos 缺 action 或非对象 / JSON 截断）+ LOCAL 异常→EXTRACTIVE（`times(1)` 不重试同档）+ T3.9 遗留断言**加固**（钉"恰好 1 次只读 query + 零其它交互"）。我独立复跑 **192/192 exit 0**、可维护性 exit 0、**三向变异**（并纠正"varargs 假绿"归因不成立，K-40）。实机：**134** 56.7min 长音频 DONE（`duration_sec=3401`、transcript 15272 字、todos 7、服务端 361s → RTF **0.106**）、**135** 真 2s 读超时 2s 内 DONE+EXTRACTIVE、**136/137** F-14（1024 畸形 / 2048 截断）、**138 我重放超时实验**（2.87s，`stack_trace` 含字面 `Read timed out`，D-046 重试收敛）。**F-14 v1 已知限制**；**F-15 排 T4.x**；F-16 写文档 / F-17 留 T4.1 / F-18 不修 / F-19 P3。配额 65/120 分钟、10/50 任务（含我 138） |
| **T3.9** | **摘要模板机制**（US-12）：5 模板（会议/课程/访谈/播客/短视频）+ 提交时可选 + 按文件名自动推荐 + 落库 | 同音频换模板 → 结构不同且都可用 | ✅ `f880b6a` + `review/2026-11-14-T3.9验收.md`（D-085/D-086）。`ai/TemplatePrompts`（5 套文案，meeting 与旧提示词逐字节一致）+ `task/TemplateResolver`（显式优先/文件名推荐/默认，非法 400）+ 提交事务内落 `task_result.template`（零迁移）+ 详情第 12 键 `template` + 导出抽 `web/TaskExportController`（与 T3.7 逐字节一致）。我独立复跑：185/185、可维护性 exit 0、**我另做两处变异红→绿**；零配额实机（12 键/旧任务 null/导出逐字节/非法 400/幂等不覆盖/429 无脏行）；**实机 129 meeting（文件名自动）+ 130 course（显式）+ 131 PASTE interview**：同段语音 summary 结构明显不同、`template` 正确落库、`ai_tier=CLOUD`、日志来源可追溯。**F-18** EXTRACTIVE 模板分化、**F-19** 模板只读查询 → T3.8 |
| **T3.10** | **格式扩展校验**：amr / opus / ass 可解可转 | 三种格式各传一个真实样本 → DONE | ✅ `0abbe0e` + `review/2026-11-15-T3.10验收.md`（D-087）。`TaskService` 魔数拒绝文案补"若仍失败，请先把音频转成 mp3 再上传"（`INVALID_MEDIA` 码不变）；`MediaMagicTest` 补 amr 窄带/OggS/未知容器，`TaskServiceValidationTest` 补文案断言。我独立复跑 187/187 exit 0、可维护性 exit 0、**我另做一处变异红→绿**；实机 amr=**132**（62s/96038B/CLOUD）、opus=**133**（61s/244460B/CLOUD）均 DONE 且 transcript 490/495 字；未知容器 400 新文案实测、零任务零配额；配额 8 分钟/5 任务逐条复算吻合（容器重建导致 132/133 原始日志不可回溯，已用 DB+API 替代） |
| **T3.11** | **本地档独立预算（F-11，D-081 已批）** | LOCAL 档真实产出 `ai_tier=LOCAL`（或明确记录失败降级） | ✅ `64434bb` + `review/2026-11-14-T3.5-T3.11验收.md`。新增 `LLM_LOCAL_MAX_TOKENS=512` / `LLM_LOCAL_TIMEOUT_SEC=180`（三处一致），`OllamaChatModelConfig` 建**独立超时通道**（不碰云的 120s/ASR 900s 路由）。我独立实测：启动日志 `max_tokens=512 / timeout=180s`；task 120 → LOCAL 调用 `finish_reason=length, completion=512`（预算精确生效，不再是 120s 超时误诊）→ 降级 EXTRACTIVE、33s 完成、无 500；task 121 云档回归 `ai_tier=CLOUD`；变异 512→8192 单测造红后还原绿。**遗留 F-14**：`ai_tier=LOCAL` 未达成（1.5B 输出冗长），需约束提示词输出长度或调大本地预算 → T3.8 前评估 |

**W3 过关**：US-3 Independent Test 通过（闭环成立）；N15 三档有证据（CLOUD 大量 / EXTRACTIVE 兜底 / LOCAL 不可用有实物证据，F-14 记已知限制）；US-12 四场景有证据（T3.9）。**门禁已关闭（2026-11-15，D-089）；下一步第 4 周 T4.x（含已批准的 F-15）**。

---

## 第 4 周：交付、三类场景验证、开源发布（约 14h）

| ID | 任务 | DoD | 验证命令 / 期望 |
|---|---|---|---|
| **T4.1** | 列表筛选 + 批量重试 + 设置页（术语表/配额/当前 AI 档位）+ **F-15 抽取式读词表** | 无 JS 报错；筛"失败"→ 批量重试成功 | ✅ `690b644` + `review/2026-11-15-T4.1验收.md`（D-090）。`task/TaskRetryService`（DEAD-only，复活 SQL 与 D-049 逐字同口径 + 清死信 + 重入队 + **不扣配额**，`retried/skipped` 不整体失败，1..20 去重）+ `POST /tasks/retry` + `GET /glossary` + `web/SettingsController`（配额/档位**单一判定来源**）+ `ai/ExtractiveGlossaryApplier`（F-15：**依据校验之后**替换三字段、空表逐字节不变、只记替换数）+ 前端筛选/批量重试/`settings.html`（原生 JS）。我独立复跑 **204/204 exit 0**、可维护性 exit 0（Java 55 文件）、**我另做一处变异红→绿**；实机 **139**（造 DEAD→retry→3s DONE、`dead_letter` 清空）、**140**（F-15 `术语替换：4 处`，summary/todos 纠正、transcript 保原文）；`/settings`、`/glossary`、筛选反例、导出逐字节一致；配额 66/120 分钟、12/50 任务（retry 不重复计费）。**已知限制**：F-15 只对 EXTRACTIVE；keywords bigram 残留；"进行中"合并视图无游标续页；并发重试非原子；**浏览器真机渲染未验**（交 T4.13） |
| **T4.2** | 端到端自动化测试（N14） | 一条命令跑通 | ✅ `e9649d2` + `review/2026-11-15-T4.2验收.md`（D-091）。`e2e/E2ETest`（180 行，`java.net.http` 零新依赖）5 场景：A 主链路（PASTE→DONE→**详情 12 键精确序列**/transcript 一致/summary 非空/ai_tier 三选一）、B 幂等 `reused`+同 id、C 导出三节顺序+`format=pdf` 400、D retry `NOT_DEAD` 跳过、E 400 逐字+415；`test.ps1 -UnitOnly` 排除 `,!E2ETest`。我独立复跑 **209/209**、`-UnitOnly` **202/202 且无 E2E 报告**、单跑 **5/5**；**停 app 反例明确红（0 skip）**；**我另做导出变异→精确红在 C→还原绿**。额外修复 `NoKeyStartupTest` **测试上下文抢真实 Redis 队列**（`@MockBean TranscriptionWorker`，K-42）；保留意见转 T4.12 评估"独立队列键"更忠实做法。配额 66/120 分钟、20/50 任务（E2E PASTE 0 分钟） |
| **T4.3** | `FETCH` 直链 + SSRF 防护（N7） | 内网地址被拒 | `{"url":"http://169.254.169.254/"}` → 400 `URL_BLOCKED` |
| **T4.4** | **零成本验证（N15）** | 清空 key → P1 故事仍走通 | ✅ **无 commit（零代码改动，只交证据）** + `review/2026-11-15-T4.4验收.md`（D-092）。**外部 override 清空四键（不改 `.env`）** → 启动日志 `首选 EXTRACTIVE` + `inspect` 四键空/false + `/settings` `preferred=EXTRACTIVE`（**不拿 health 200 当证据**）；**真音频 task 157**（UPLOAD/DONE/EXTRACTIVE/transcript 80/summary 80/kw 10/template meeting/`error_code`=null/`todos=[]` 按 D-072 判据）+ 导出 md 三节 200；**我重放零 key E2E 5/5（task 162 EXTRACTIVE）**，对照全量跑的 160/164 为 CLOUD；**我重放反例**（空 key + `LLM_ENABLED=true` → 首选 CLOUD 后 `openAiApi` 崩、HTTP 000，**矛盾未静默**，记 F-21）；恢复：5 healthy、`preferred=CLOUD`、`.env` `2BCC440F1C9E`、**209/209 exit 0**、可维护性 exit 0。配额 **67/120 分钟、25/50 任务**（本卡 +5 任务，全 DONE，DEAD=0）。已知限制：F-21、抽取式质量线（碎词/原句拼接）、F-17 换模板重跑仍未做 |
| **T4.5** | **离线验证（US-9 / N16）** | 断外网 + 无 key → US-1 成功且无出站 | ✅ **无 commit（零代码改动，只交证据）** + `review/2026-11-15-T4.5验收.md`（D-093）。`networks.default.internal: true`（**先 `down` 再 `up`**，K-44）+ `OFFLINE_MODE=true` 且 **`.env` 真 key 在位** → 三判据：`/settings` `EXTRACTIVE + cloud_configured=true + offline_mode=true`、`/proc/net/route` 无默认路由、容器内 curl 外网 `Could not resolve host` exit=6；门禁 WARN 原文可见。真音频 **166**（40s：DONE/EXTRACTIVE/transcript 99/summary 99/kw 5/导出三节 200/零云档调用）；**反例 167**（`OFFLINE_MODE=false`+断网 → CLOUD `UnknownHostException` → LOCAL → EXTRACTIVE，任务仍 DONE，D-075 级联实证）。恢复：5 healthy、`internal=false`、`preferred=CLOUD`、`.env` `2BCC440F1C9E`、**209/209 exit 0**、可维护性 exit 0。配额 **68/120 分钟、28/50 任务**（任务 166/167/168，DEAD=0）；**K-45**：写码方回报数字再次采样偏早 |
| **T4.6** | **接入面（US-10 / N17）** | API Token + OpenAPI + Webhook + 验签 | ✅ `1c2aa73` + `review/2026-11-15-T4.6验收.md`（D-094）。`web/ApiTokenFilter`（`/api/v1/**` Bearer，401 `UNAUTHORIZED` + `WWW-Authenticate`，常量时间比较，空 token fail-closed；豁免 actuator/静态页/OpenAPI）+ `static/auth.js` `apiFetch`（localStorage + 401 prompt 重试一次）+ springdoc 2.8.17（`/v3/api-docs` + Swagger UI，bearer securityScheme）+ `webhook/WebhookDeliveryService`（终态 task.done/failed、HMAC-SHA256 原始字节签名、`webhook_delivery` 记账、@Scheduled 1s/10s/60s 重试、原子认领防双投、OFFLINE_MODE 跳过、delivery_id 由行 id 派生 UUIDv3）。我复跑 **220/220 + -UnitOnly 212/212 + 可维护性 0**；**自建接收端重放**：验签通过（HMAC 独立重算命中）、九键齐、**500→1s 重试成功**（attempts=2）、**4 次全败留表**（attempts=4/last_status=500/next_retry=NULL）、delivery_id 独立推导命中。配额 **68/120 分钟、36/50 任务**（它 4 + 我 4，全 PASTE 0 分钟） |
| **T4.7** | **批量提交（US-8）**：一次 10 个文件互不影响 | 10 个全部终态 | ✅ `265b534` + `review/2026-11-15-T4.7验收.md`（D-095）。`task/BatchTaskService`（逐项复用单文件路径、失败隔离、纯函数预估）+ `POST /api/v1/tasks/batch`（`files` 可重复 ≤20 + 可选 `template`；请求合法即 200 + 逐项结果/汇总）+ 上传页多选（1 个仍走单文件旧路径）+ 3 新配置键（`BATCH_MAX_FILES`/`TASK_BATCH_MAX_SIZE_BYTES`/`BATCH_ESTIMATE_RTF`，全有消费方）+ E2E 场景 G（2 静音 wav + 1 坏文件隔离 + 21 超限 + api-docs）。我复跑 **227/227**（E2E 8 条）+ `-UnitOnly` **217/217** + 可维护性 0；**自造样本实机**：2 好 1 坏 200 逐项隔离、原样重发 `reused` 同 id 且**配额不变**、21/0 文件 400、匿名 401、api-docs/列表齐。配额 **76/120 分钟、46/50 任务**（**今日只剩 4 个任务配额**） |
| **T4.8** | 重启不丢验证（N20） | `down`（不删卷）→ `up` → 数据在 | ✅ **无 commit（零代码改动，只交证据）** + `review/2026-11-15-T4.8验收.md`（D-096）。写码方：`down`（**无 `-v`**）→ `up` → detail 177 零差异、导出 md sha256 `2d2fe8ea…ef88` 逐字节同、四表计数 90/90/0/4 与配额 76/46 不变；**RUNNING 僵尸回收**（237s 音频 task **202**：`restart app` 后日志 `recovered:1` → 回 PENDING → DONE，attempts=0 / transcript 1413 字 / 配额只扣一次）；**Webhook 重启续跑**（插到期行 + 重启 → 投递成功 attempts=1/last_status=200）。**我**：①自己又 `down`（无 `-v`）/`up` 一次（0 任务），重新取数断言 detail 零差异、导出 sha256 一致、91/91/0/5 计数与 80/120·47/50 不变、四个读端点 200；②用 **.NET + Python 两套独立实现**重算 HMAC 命中、错密钥不等、`delivery_id` 由行 id=5 独立推导命中（K-47）；③**独立复现 F-22**（空 `WEBHOOK_SECRET` + 手工到期行 → 空烧 4 次 attempts=4/last_status=0、日志 `HMAC-SHA256 不可用` 误导，0 任务消耗）；④`-UnitOnly` 复跑两遍 **217/217 exit 0**，全量未跑（配额只剩 3，零代码卡按卡规用 `-UnitOnly`，理由已写报告）。配额 **80/120 分钟、47/50 任务**（本卡 +4 分钟/+1 任务，预算内） |
| **T4.9** | **开源发布最小集（D-026）**：一键脚本 / 示例数据 / 3 截图 + 1 GIF / LICENSE + CONTRIBUTING + issue 模板 / CHANGELOG + `v0.1.0` tag | 陌生人照 README 30 分钟跑通 | 临时目录照做一遍（含 `git tag` 检查） |
| **T4.10** | README（含"你不该用本项目的场景"、三档 AI 差异、四个模板示例）+ 3 分钟录屏 | 内容齐全 | 逐项目视检查 |
| **T4.11** | 简历条目 + 面试材料定稿（**只填实测值**） | 无目标值冒充实测 | 对照 `面试材料.md` 数字台账 |
| **T4.12** | **发布前死配置体检**（D-054）：`app.*` 每个键必须有消费方；被裁功能的配置项 + `.env` 键**连带删** | 无"填了没反应"的配置 | ✅ `3c650ca`（6 文件 +277/−12）+ `review/2026-11-15-T4.12验收.md`（D-098）。**审计**：yml 叶子 `app.*` **40 键**，死配置 **5**（`asr.max-concurrency`/`max-segment-sec`、`inbox.*`×3）**全是 §3 计划中 P2**，**未登记孤儿 0**；3 个 `BATCH_*` 键非死键；`$appKeys` 快照 36→40、`$allowed` 加 `TZ`、`app.base-url` 过时行更正。**修复三件**：**F-22**（`scanDue` 空 secret 短路：不投递/不烧 attempts/`next_retry` 原样/WARN 一次；我独立复现行 id=8 `attempts=0`、WARN 1 条、`HMAC-SHA256 不可用` 0 条）+ **F-21**（`EnvironmentPostProcessor` fail-fast，点名缺键+两种修法；我复现 `ExitCode=1` 可读文案）+ **F-23**（`TimezoneConsistencyCheck` 启动 WARN，判据 `SHOW timezone`；我另起临时空卷验证新装 `Asia/Shanghai`）。**队列键评估=不改**（`@MockBean` 隔离更强）。测试：我复跑 `-UnitOnly` **225/225** exit 0、可维护性 exit 0；全量 **235/235**（E2E 8/8，我未重跑：配额已 50/50）。配额 **82/120 分钟、50/50 任务**（**今日任务配额归零**） |
| **T4.14** | **前端视觉重设计**（用户 2026-11-15 新增，D-099）：用 `redesign-existing-projects` 技能审计+就地升级 4 个原生页面 | 专业、一致、有状态感；技术栈/契约不动 | ✅ **条件通过**（`f42dc85` 重设计 + `508de4c` oiloil 方向换皮，2 commit / 7 文件 +811/−132 全在 `static/`）+ `review/2026-11-15-T4.14验收.md`（D-100）：暖纸底+方格纹理/墨黑排印（h1 30px）/单一暖黄 `#eab308`/无边框 16px 白卡/黑主按钮；**我独立验证**：4 页桌面+手机 390 **0 console error、0 横向溢出**、`aria-current` 生效、**导出 md 真下载**（三节齐）、token 对话框/拖拽区/单箭头/`:focus-visible` 全过、`-UnitOnly` 225/225、可维护性 0/0；**唯 P1 = `.hint` 对比度 3.16:1 < 4.5**（必修）；**全量 235/235 + 上传冒烟未执行**（已排期 00:10 自动化 id 52235e59，排期≠执行 K-55）；发布门禁 = 全量 + P1 + T4.13 真机。**T4.14b 已通过（`663ddfe`，CSS-only 2 token；我复测 `.hint` 5.28/4.88、badge 6.49，`-UnitOnly` 225/225、可维护性 0/0）；T4.14c 四项打磨 ✅ 通过（`643ed90`，D-102：纹理 `.038→.03`、行 hover `.035`、手机两行重排行高 107px、列表补「时长 · 提交时间」；我复测 hover 16.24:1 / 次要行 4.93:1（alpha 合成 K-57）、mock 验 null→`—`/UTC+8/DEAD 复选框/长名省略、无新增任务；00:10 自动化已在 WorkBuddy DB 查实绑定 `643ed90`，执行仍待核验）**；**HEAD 今晚冻结在 `643ed90`**（自动化跑完前不新增 commit）。原始计划： 卡已写进 `tasks-starter.md`（W4 第 10 张，插在 T4.13 之前）：先诊断清单（每类问题/严重度/改法/是否修）→ 按 Fix Priority 修；设计值**适配离线中文**（系统字体栈、禁外链/CDN、单一 accent、禁背景渐变、Tailwind 刻度间距/圆角/字号、hover/active/focus、骨架/空态/行内错误、`prefers-reduced-motion`、640/900/1200 断点）；参考 Otter/Fireflies/tl;dv/Notta/飞书妙记/通义听悟 的信息结构（不抄资产）；验收 = 前后 Playwright 截图（1440+390、5 类状态、无 console error、手机无横向滚动）+ 功能冒烟 + 全量 235/235 + 可维护性 0；预算 ≤4 任务（今日配额 0 → 等 00:00 日切）；工期 +3~4h（余量 0，超时按 D-032 砍 P2，不砍测试与材料） |
| **T4.13** | **真机 UI 验收**：浏览器上传交互（文件选择器）/ 移动端上传 / 无痕窗口列表 | 三条真机点过并截图 | ✅ **通过（2026-11-16，第 42 份报告，D-114）**：用户本人真机点完 9 条路径（含新增 UI 速看），反馈"清单上面的都没问题"；消耗 2 任务（**264** 桌面 120s、**266** 手机 60s）；发现 5 个新问题 → 开 **T4.20**（上传交互修复）/ **T4.21**（辨识+删除）。我逐条在代码里核实机制，未替用户伪造"点过" |
| **T4.15** | **第 0 档：信息设计 + AI 可见度**（用户 2026-11-15 新增，D-103/D-104）：首页最近任务/三步/示例结果、列表搜索+状态计数+多选导出、设置页系统状态卡、详情页依据高亮+待办真勾选+复制全文、页脚 | 页面"不空"且 AI 可核对；**纯 `static/`、零后端、0 任务配额** | ✅ **通过**（`e95e7e2` 6 文件 +607/−31；`fb93469` P2 修复 token +1/−1）+ `review/2026-11-15-T4.15验收.md`（D-105）。**我的独立复核**：首页最近 3 条=206/205/203 且**失败整块隐藏**、示例卡展开 **0 新增请求**、配额 0 时按钮禁用；搜索 `paste`→9 行 + 空态 + 清空恢复；计数 **94/0/94/0** 与 psql 一致；多选导出**真下载 2 份 md（三节齐全）**、空转写 206 服务端 400 正确；状态卡 5 服务与 `/actuator/health` 一致；203 依据命中率 **1.0** + 点击高亮 + 7 关键词可定位；**勾选真写库**（DB `done`，已复原 203 行）、复制全文与 API transcript **全等**；console/pageerror **0**、外部请求 **0**、手机 390 四页溢出 **0**；`-UnitOnly` **225/225 exit 0**（真子进程码）、可维护性 **0/0**；**0 新增任务**（94/配额 50/50）。**P2 对比度**：`--accent-deep` on tint **4.48→6.45**（三处使用点，`.chip`/`.badge.busy` 为 T4.14 遗留、我上次漏量 → **K-59**）。**遗留 P3**：待办删除无二次确认、依据整句匹配、示例真跑未实测、`.ground-miss`/`.status-down` 无实例 → 并入 T7.6。**00:10 自动化已重绑 `fb93469`，执行待核验**（K-55） |

| **T4.15c** | **详情页空态文案修正**（用户 2026-11-15 截图反馈）：空转写被显示成"AI 未启用或本次未生成"，误导 | 三种情况分清（D-072 语义） | ✅ **完成 `9d2641e`**（2026-11-16，第 36 份验收报告）：`transcript` 空 → 「这段音频未识别到语音（可能是纯音乐、静音或无人声），所以没有摘要、关键词和待办。」；有转写但 `ai_tier` null → 保留原 AI 文案；`[]` 仍按"确实没有内容"渲染（D-072）。我独立复核 **28/28 断言**（含 Playwright route 拦截构造的 AI 失败/`[]` 两个边界）、`-UnitOnly` 225/225 exit 0、可维护性 0/0、diff 仅 `static/task.html` +11/−2、0 配额 |
| **T4.16** | **真内容质量验收 + ASR 模型对比**（用户 2026-11-15 指定） | 有内容文件下三节输出可用；模型对比有结论 | ✅ **完成（2026-11-16，第 37 份报告，D-107）**：6 个真实任务 small `223/224/225` + medium `229/230/231`（全 DONE/CLOUD；本卡 6 任务/约 45 分钟）；转写 medium 更准（对参考 0.874/0.838/0.926 → 0.956/0.884/0.983）但引入新错+1 处重复幻觉；**默认 ASR 保持 small**（RTF 0.160→0.381、模型 461→1457MB）；发现 3 个 LLM 层问题：temperature 未固定（同转写两次待办 0 vs 5）、summary 200 字硬截断腰斩、todos 把演示动作当待办 → 新卡 **T4.16b**；另补充：上传按 content_hash 幂等复用、medium 模型需自备（发布文档必须写） |
| **T4.16b** | **LLM 结构化质量三修**（T4.16 暴露，D-107） | 同样内容两次结果稳定、摘要不腰斩、待办不误判演示动作 | ✅ **完成 `885038b`**（2026-11-16，第 38 份报告，D-108）：①云档 `temperature=${LLM_TEMPERATURE:0.0}`；②`truncateSummary()` 句边界截断（≤200 原样/超长回退到最后一个 `。！？`/无标点才硬截）；③公共 `promptSection()` 收紧（只列听众行动项、演示动作不算、教程类 0–8 条）。我独立跑 `-UnitOnly` **231/231 exit 0**、可维护性 **0/0**、**三次变异测试全造红**；真实任务 232 摘要 134 字完整句、待办 0 无演示动作。残余：截断无最小长度保护（入 T7.x）、interview 场景未重跑 |
| **T4.17** | **音频要点（keypoints）**（用户 2026-11-16 要求） | 详情页/导出有编号要点，内容不编造、条数自适应 | ✅ **完成 `66af59b`**（2026-11-16，第 39 份报告，D-111）：Flyway V3 `task_result.keypoints jsonb`（真实 PG16 生效）；同次 LLM 调用多产 `keypoints[{title,detail}]`（3–15 自适应、title≤20、detail 40–120、禁编造），解析容错=缺失/畸形→null 且**不整条降级**、代码层截 15；`GET /tasks/{id}` 第 13 键；详情页「要点」编号+加粗小标题（390 宽无溢出）；导出四节 摘要/关键词/要点/待办（null/空整节省略）；待办零改动。我独立 `-UnitOnly` 240/240、**全量 250/250 exit 0（含 E2E 13 键）**、可维护性 0/0、**两处变异造红**；真实任务 237 要点 6 条依据可查。残余：LOCAL 档未真机抽、观点型内容仍可能被抽成待办（→T4.19） |
| **T4.18** | **转写一句一行 + 导出勾选/全选**（用户 2026-11-16 要求） | 转写按句换行；导出可勾选摘要/关键词/要点/转写/待办，一键全选 | ✅ **完成 `abf4649`**（2026-11-16，第 40 份报告，D-112）：Flyway V4 `transcript_punctuated`；`TranscriptPunctuator` 结构化成功后**独立**一次 LLM 标点（`LLM_PUNCTUATE_MAX_CHARS` 6000、0=关闭、失败/超限/长度异常→null 不降级、ai_tier 不动）；详情第 14 键；前端一句一行 + 复制/定位用展示文本；导出 `include`（5 值/未知·空 400/省略默认不含待办/固定节序）+ 5 复选框+全选。我独立 **全量 275/275 exit 0（含 E2E 14 键）**、可维护性 0/0、**变异造红**；task 256 标点"只加不改"复算通过（去标点 1664==1664、标点 0→145）、四象限导出实拉。残余 P3：导出切句口径与页面不完全一致、只勾要点且无要点时导出空文件、标点失败真机未验 |
| **T4.19** | **会议待办语义 + medium 提示**（会议实测后的三条改进） | 会议模板区分已议定/未定/知识；设置页与 README 提示会议建议 medium | ✅ **完成 `48df935`**（2026-11-16，第 41 份报告，D-113）：`TodoService.promptSection()` 公共段追加"已议定待办→列入；讨论中未定/纯知识/观点/资讯→不列入；owner 未点名一律 null"（原规则一字未删、未复制进模板）；`settings.html` AI 档位卡加静态 medium 提示（无交互无请求）；README 新增「模型选择建议（ASR）」+ 接入指南 §8.4 对齐。我独立 `-UnitOnly` 265/265 exit 0、可维护性 0/0、`git diff` 证默认值未动、实拉 settings.html 与工作区逐字节一致、1280/390 截图提示可见无溢出（对比度 5.28）。**0 配额**；未跑全量（本卡不动契约，K-68 条件不成立）。诚实边界：提示词降噪效果未实测 → T4.13 看已有任务页面表现 + T5.5 批量回归统计 |
| **T4.20** | **上传交互修复**（T4.13 真机发现，D-114） | 分批选文件不覆盖；已选列表在框内可移除；详情新标签页 | ✅ **完成 `5be3a4e`**（2026-11-16，第 43 份报告，D-116）：`selected[]`（key=name|size|lastModified）**追加不覆盖**、去重、上限 20、>200MB 跳过、提交以 selected 为准、成功清空/失败保留；已选列表移入拖拽框内 + 每行「移除」；`tasks.html` 两处详情 + 首页最近 3 条加 `target=_blank rel=noopener`。**写码方自查挖出并修掉一个冒泡真 bug**（键盘 Enter 被容器吞 / detached 元素穿透 `closest()` guard，K-71）；我独立 `-UnitOnly` 265/265、可维护性 0/0、实拉静态文件逐字节相等、**自写 Playwright 14/14**（含移除不误开 filechooser）、真机新增任务 270/271 DONE；0 后端改动、未跑全量（契约未变） |
| **T4.21** | **任务辨识信息 + 删除任务**（T4.13 真机发现，D-114/D-115） | 列表能看关键词/摘要首句/模板；可删单条/多选/全选/清空失败任务 | ✅ **通过 `de031b2`**（2026-11-16，第 44 份报告，D-117）：列表接口只增 `keywords`/`summary_head`/`template`（`LEFT JOIN task_result` 一对一、坏 JSON→`[]`、分页不动）、详情 14 键未动；`DELETE /tasks/{id}`（404/409 TASK_BUSY/200）+ `POST /tasks/delete`（1–100 去重逐条结果）+ `POST /tasks/delete-failed`（只删 DEAD）；四表同事务、MinIO best-effort、配额不退；前端删除所选/全选/清空失败任务/详情删除 + 二次确认。**我独立**：`-UnitOnly` **273/273**、**全量 283/283** exit 0（最终镜像上）、可维护性 0/0、静态文件逐字节相等、**自写接口 49 项 48 PASS**（409→DONE→删→404、四表 0 行、MinIO 目录消失、批量逐条、RETRYING 409、清空 DEAD、配额不退）、**自写 Playwright 31/31**（chips/摘要/模板徽章/删除入口/confirm/390 零溢出）；真机任务 284/285/286。**遗留 P2 缺陷 → T4.21b**：`delete-failed` 漏清 MinIO 对象（真实对象探针实锤）；另有 P3：无 controller 单测、状态读未加 `FOR UPDATE`、短摘要也加省略号 |
| **T4.21b** | **清空失败任务补 MinIO 清理 + 防回归测试**（T4.21 验收发现的 P2，D-117） | 清空 DEAD 后对象也删掉；S3 异常不回滚 DB；补 controller 测试 | ✅ **通过 `a1cc93f`**（2026-11-16，第 45 份报告，D-118）：`deleteAllFailed()` 对每个成功删除的任务在**事务外**调 `purgeObjectBestEffort`（口径同单条/批量）、`deleteOneInTx()` 状态读加 `FOR UPDATE`、新增 `TaskDeleteControllerTest` 4 例（404/409/成功+purge/清空计数）。**我独立**：`-UnitOnly` **279/279** exit 0、可维护性 0/0、自写真机探针 **19/19 PASS**（任务 259 置 DEAD → 清空 → 四表 0 行 + **MinIO 目录消失**；任务 253 单条删除回归；RUNNING 409/批量逐条/404 不变；配额 160/48 不变；证据行全在），**0 新建任务**。过程发现 6 条旧 E2E 任务消失但旧容器日志随重建丢失（审计断链→K-74，不影响结论）；桶内 6 个早于删除功能的历史孤儿对象（74–79）记录待清理 |
| **T4.22** | **上传页模板选择器**（D-119"已有能力没摆出来"） | 上传时可选 5 套模板；默认自动识别不改变后端行为 | ✅ **通过 `92abadc`**（2026-11-16，第 46 份报告，D-120）：`index.html` 加 `#templateSelect`（`""`=自动识别默认/会议/课程/访谈/播客/短视频），单文件 `form.append('template',v)` + 批量 `batchForm.append('template',v)`（仅非空），提交后保留选择；`style.css` 8 行 `.tpl-pick`（44px 点按区/focus 环/390 换行）。**我独立**：自写 Playwright **18/18**（0 配额 route 拦截：自动→无 template 字段、课程→course、播客批量→podcast+2 files、键盘、1280/390 溢出 0、console 0）、可维护性 0/0、实拉静态文件逐字节相等、配额 160/48 不变；纯前端未跑全量（契约未变）。我的第一版脚本 glob 没拦住 `/tasks/batch`（K-75）→ 真后端幂等 reused、0 配额；任务数 128→126 两条旧 E2E 任务消失（非证据行，K-74 同类） |
| **T4.23** | **摘要级依据**（T7.16 提前，D-121；补功能清单"部分做"的 1 项） | 摘要每句能给"在原文中匹配到的句子"，匹配不到如实写"未找到" | ✅ **通过 `01f1b71`**（2026-11-16，第 48 份报告，D-125）：纯 static——`app.js` 加 `mnGroundSummaryWindow/groundWindowBest`（摘要逐句在原文按连续 2–3 句窗口算二元组覆盖率，≥0.5 命中，窗口不中退单句；复用 `mnNormalize/mnBigrams/mnSplitSentences`），`task.html` 摘要段下加「摘要依据 · 找到 X/Y 句（在原文中匹配到的句子，浏览器内重算）」+ 命中行可点（复用 `jumpToSentence/data-sent/sent-highlight`）、未命中写「未找到直接依据」、summary/transcript 空整块隐藏，`style.css` +7 行。**我独立**：自写 Playwright **27/27**（真实 237→0/5、256→0/3 如实；route 注入合成摘要命中 6/6、点击高亮/滚动/唯一/data-sent 全过；编造句如实未命中；空态隐藏；无额外请求；1280/390 零溢出）；我独立复算覆盖率 237=0.27–0.47、256=0.17–0.23 → **D-125 维持 ≥0.5 不降阈**。**P3-1**：空归一化（仅标点）摘要句被误判命中（`indexOf("")===0`）→ 立 **T4.23b** 前置微修（K-78） |
| **T4.24** | **1.1 服务端搜索 + 真计数**（D-122） | 搜索能搜到没加载的行；计数与 DB 一致 | ✅ **通过 `7a4fc30`**（2026-11-16，第 47 份报告，D-123）：`GET /tasks` 只增可选 `q`（`TaskQueryService.normalizeQ` 去空格/空白忽略/>100 字 400 `INVALID_PARAM`；SQL 参数化 `source_uri ILIKE ? ESCAPE '\'`，`%`/`_`/`\` 字面；纯数字 `OR t.id = ?`；与 status/before_id 可组合，分页与响应结构不动）+ 新 `GET /tasks/counts`（独立 `TaskCountsController`，`GROUP BY` 真计数无上限，`{total,active,done,dead}`，active 三态合并）；前端 300ms 防抖服务端搜索、提示"服务端搜索 · 匹配 N(+/0) 条"、计数切 counts 删 200 上限。**我独立**：全量 **296/296**、可维护性 0/0、自写 API **31/31**、自写 Playwright **29/29**（全 exit 0，0 配额）；counts 135 == psql 135 == 逐页合计；中文文件名 #129/#88（第一页外）可搜到；`%`/`_`/`\`/注入串全部字面 200。**P3-1**：`q` 超长纯数字（>Long.MAX）→ 500 → 立 **T4.24b** 前置小修（D-123/K-77）；另 P3-2/3/4 记录 |
| **T4.24b** | **q 超长数字 500 收口**（T4.24 验收 P3-1，D-123） | 20 位数字 q 不再 500；搜索框不再能触发误导 fallback | ✅ **通过 `fda7df0`**（2026-11-16，第 48 份报告，D-124）：`TaskQueryService.parseableAsId`（位数 <19 或 19 位字典序 ≤ Long.MAX 字面量）→ 只有能装进 long 才加 `OR t.id = ?`，超范围按普通关键词 ILIKE 保持 200；`tasks.html` 搜索框 `maxlength=100`；文档 §3.3 补口径；单测 +2、E2E +1。**我独立**：API **25/25**（9 个数字边界 + 25 组随机 fuzz 全 200、101 字仍 400、T4.24 契约零回归）、全量 **298/298** exit 0（我先 commit→build/up→全量，K-68）、可维护性 0/0、键盘 110 字截断到 100；写码方 K-77 顺序已合规 |
| **T4.23b** | **摘要依据微修：空归一化误判命中**（T4.23 验收 P3-1，D-125） | 仅标点摘要句不再显示假依据 | ✅ **通过 `cbcf00c`**（2026-11-16，第 49 份报告，D-128）：`groundWindowBest` 加 `if (!norm) return null;`（K-78：`indexOf("")===0` 会把「。。」/「……」判成完全匹配），该摘要句 hit=false 写「未找到直接依据」、行数口径不变。**我独立**：Playwright **21/21**——`'。。'`→2 行全 false、`'……'`→1 行 false、`'   '`→0 行、256 原文句正例仍 true、真实 256 仍 0/3、注入渲染 2 行 0 个假依据链接；容器 app.js sha256==工作区；`-UnitOnly` 287/287 |
| **T4.25** | **1.2 统计摘要**（D-122） | 设置页「我的使用」卡 | ✅ **通过 `70c5eeb`**（2026-11-16，第 49 份报告，D-129）：新 `GET /api/v1/stats`（五键固定、null 不省略；`tasks` 复用 counts 口径；`duration_sec_total`；`success_rate=done/(done+dead)` 3 位、分母 0→null；`avg_processing_ratio` 只 DONE 且 duration>0〔含排队，非 RTF〕3 位；`tiers` DONE 按 ai_tier 分组、null→"null"）+ 新 `StatsController` + 设置页「我的使用」五行卡（不改既有卡）。**我独立**：全量 **302/302** exit 0（我重建镜像后跑，K-68）、可维护性 0/0、API **22/22**（与我 psql 复算 5 组数字逐项一致：147/147/0、14171、1.0、0.335、CLOUD77/EXTRACTIVE30/null40）、UI **21/21**（五行=API 真值、诚实文案在、RTF 仅否定句、1280/390 零溢出、只读三端点）；0 配额（174/69 前后不变） |
| **T4.26** | **1.4 换模板重跑 F-17**（D-122） | 详情页选模板重新生成摘要 | ✅ **通过（附条件）`75d72b7`**（2026-11-16，第 50 份报告，D-131）：`POST /tasks/{id}/restructure`（TransactionTemplate + `FOR UPDATE NOWAIT` 锁行；只复用已存 transcript 重跑结构化、不重跑 ASR、不新建任务、不扣配额；`persist` 一并写 template〔T3.9 旧不变量有意变更〕；非法模板 400 D-085 文案、缺失 400、404、非 DONE 409、空转写 400、失败 500 不写半截；详情页「按模板重新生成」+ 二次确认）。**我独立**：全量 **313/313**、可维护性 0/0、API 14 项 13 PASS、UI **15/15**（默认当前模板/确认三要素/POST body/失败「原结果未被修改」/DEAD 无入口/1280+390）；真机 237 meeting→course 摘要与导出口径变化、**0 配额**（178/75 不变）、任务数/max id 不变。**P2-1**：真并发第二请求实测 **500 而非 409**（`UncategorizedSQLException(55P03)` 未被 catch，单测 mock 错异常类→假绿 K-79）→ 立 **T4.26b** 前置必修 |
| **T4.26b** | **T4.26 并发锁冲突 409 收口**（D-131 P2-1，D-132） | 并发第二请求返回 409 TASK_BUSY 而非 500 | ✅ **通过 `f2c2301`**（2026-11-16，第 51 份报告，D-133）：`catch DataAccessException` → most specific cause 的 `SQLException.getSQLState()`；`55P03/40001/40P01` → 409；其余原样抛（禁止 catch-all）；单测用生产真实形状 `UncategorizedSQLException(55P03)` + 42P01 反例。**我独立**：真机**两轮**双并发均 **200+409**（500 消失）、全量 **324/324** exit 0、可维护性 0/0、0 配额；237 恢复 course |
| **T4.27** | **1.3 批量导出 zip**（D-122） | 列表多选导出 zip | ✅ **通过（附 P3-1）`08033c0`**（2026-11-16，第 51 份报告，D-134）：新 `TaskZipExportController`（ids≤20 去重保序、流式 ZipOutputStream、条名净化、缺失/不可导跳过附 `_errors.txt`、全不可导 400、Content-Disposition 文件名）；`render()` 抽共用保证**与单条导出逐字节一致**；前端导出所选改单请求下载 zip。**我独立**：API **19/19**（zip 合法/逐字节一致/_errors.txt/400 矩阵/去重/401/0 配额）+ UI **12/12**（真下载 zip 魔数、失败就地提示、1280/390）；全量 **324/324**、可维护性 0/0。**P3-1**：`ids` 超长数字 → 500（K-76 第二次复发）→ 立 **T4.27b** 前置修 + 规则进 `rules/dev.md` |
| **T4.27b** | **T4.27 收尾：ids 解析边界**（D-134 P3-1） | ids 超长数字返回 400 而非 500 | ✅ **通过 `7c908fc`**（2026-11-17，第 52 份报告，D-135）：`parseIds` 改用共用 `TaskQueryService.parseableAsId`（与 T4.24b 的 q 溢出同一判定），溢出/超长（含前导零超长整串）→ 400 `INVALID_PARAM`「任务 id 超出范围」，不再 500；单测补 `20位/9*20/0*30+42/MAX+1/混合` 矩阵。**我独立**：6 边界全 400（无 500）、`ids=MAX/0` → 400「没有可导出」、正常 zip 200 解包 2 条；全量 **337/337**、可维护性 0/0 |
| **T4.27** | **1.3 批量导出 zip**（D-122） | 列表多选导出 zip | 待做：`GET /tasks/export?ids=`（≤20，流式 ZipOutputStream，逐条 md，缺失附 _errors.txt）；前端改 zip；全量 |
| **T4.28** | **1.5 只读分享链接**（D-122） | 生成只读链接给别人看单条 | ✅ **通过 `aa872f1`**（2026-11-17，第 52 份报告，D-136）：V5 `share_link`（token_hash SHA256/expires/revoked + 双索引 + 级联删）；`POST/DELETE /tasks/{id}/share` + 匿名 `GET /api/v1/share/{token}` 12 键只读；`ApiTokenFilter` 只豁免 `/api/v1/share/`；`share.html` 只读页；`SHARE_TTL_DAYS:7`。**我独立**：API **16/16**（token 的 SHA256 == DB hash、匿名 12 键、伪造/撤销/过期统一 404、其它端点 401、0 配额）+ UI **12/12**（匿名无 token 弹窗/无写入口/不链内部页/1280+390）+ 安全探针（`../` 401 无穿越绕过、`%2e%2e` 404、`/shareX` 401）；V5 结构实查；P3-1 文档缺口（接入指南/配置清单）由我补齐 |
| **T4.29** | **1.6 标签/收藏**（D-122） | 打标签标星、列表可筛 | ✅ **通过（经 T4.29b 收尾）`ad5db85` + `e348160`**（2026-11-17，第 53/54 份报告，D-138/D-139）：V6 `labels jsonb`/`favorite`/partial 索引、11 键只增列表、`PATCH/GET /tasks/{id}/meta`、label/favorite 筛选（我独立 API **44/44**、详情仍 14 键、可维护性 0/0、全量 **347/347**）；首验 **P1-1** 详情页写路径发 POST → 405（`postJson` 写死 POST 打 PATCH 端点）+ P2-1 下拉滞后 ≤30s + P3-1 E2E 精确条数依赖全库 → 由 **T4.29b** 修复并复验通过；K-80/K-81 入坑表 |
| **T4.29b** | **T4.29 收尾：UI 写路径 405 + 下拉刷新 + E2E 断言**（D-138） | 详情页标签/星标真正可改；下拉及时；全量稳定绿 | ✅ **通过 `e348160`**（2026-11-17，第 54 份报告，D-139）：`postJson`→`sendJson(...,'PATCH',...)`；`refreshLabelOptions()` 由 `render()` 末尾调用 + 筛选值占位 option；E2E `h` 断言改契约口径（必含 target + 每条 `id==taskId || 文件名含数字串`）。**我独立**：自写「点击→回读」探针 **14/14**（星标翻转/加标签/删 chip 每步回读 + `/meta` 写请求全 PATCH 无 POST + 下拉加载完即含标签 + 1280/390 零溢出）、`q=420` 旧失败场景在新断言下绿（修口径非藏红）、全量 **347/347** exit 0、可维护性 0/0、静态 sha256 一致；366 探针残留由我清理 |
| **T4.30** | **2.2 浏览器内录音**（D-122） | 点开始录制，说完自动转写 | ✅ **通过（经 T4.30b 收尾）`54e3be6` + `efae735`**（2026-11-17，第 55/56 份报告，D-140/D-141）：`static/index.html` +222 / `style.css` +17（MediaRecorder 状态机、1s 分片、60min/200MB 自动停、失败保 Blob 重试、权限拒绝提示；后端契约 0 改动）。**我独立**：fake-mic 真录探针 15/15（录音中/计时/停止面板/名称预填/防覆盖/失败提示+Blob 可重试/权限拒绝无未捕获/1280+390 零溢出）；首验 P1-1 webm 提交 500（时长解析）由 **T4.30b** 修复后，端到端 8/8（录 3.5s → 提交 → 自动跳详情 → DONE，task 476） |
| **T4.30b** | **T4.30 收尾：webm 时长解析兜底**（D-140） | webm 上传 201→DONE，不再 500 | ✅ **通过 `efae735`**（2026-11-17，第 56 份报告，D-141）：主路径 `N/A`/空/本地化小数/多行/非有限 → 不抛 → 兜底末音频包 `pts_time(+duration_time)` → 两路空才 400 `INVALID_MEDIA`；抽 3 个纯函数 + **20 例单测**；E2E 场景 M（webm fixture 24KB 字节截断、`format=duration=N/A` 实查）断言 201→DONE→`duration_sec` 1–3；`接入指南` 补 webm/opus。**我独立**：全量 **368/368**、可维护性 0/0、静态 sha256 一致；API **13/13**（fixture 201→DONE dur=2、真录 8s→DONE transcript 非空 dur=8、同字节二次上传同 task_id+reused、wav 回归 dur=1、**坏 EBML → 400 非 500**）；端到端 UI **8/8**。新坑 K-83（重构漏程序名 → 全格式 500，E2E 抓出） |
| ~~**T4.31**~~ | ~~2.5 PWA / 手机安装~~ | — | **不做（D-126：用户 2026-11-16 明确取消）** |
| **T4.32a** | **2.1 前置：FETCH 直链 + SSRF**（原 T4.3，D-122） | 内网地址被拒 | 待做（**D-127：保留单列卡**）：内网/回环/链路本地/元数据拒绝 400 `URL_BLOCKED`、重定向每跳校验且上限、连接/读超时、响应体上限；`MediaFetcher` 接口 |
| **T4.32b** | **2.1 链接导入 L1/L2/BYOF**（D-122） | 粘链接就能转写 | 待做：L1 og:title/时长、L2 Content-Type 探测走下载管线、BYOF `FETCHER_CMD`（不配返回 `FETCHER_UNAVAILABLE`）；`POST /tasks/link`；**依赖 T4.32a** |
| ~~**T4.33**~~ | ~~2.3 INBOX 目录收取~~ | — | **不做（D-126）**；`app.inbox.*` 保持死配置并登记已知限制 |
| ~~**T4.34**~~ | ~~2.4 RSS 播客订阅~~ | — | **不做（D-126）** |
| **T4.35a** | **2.6 前置：ASR segments**（D-122） | 句子级时间戳入库 | 待做：`server.py` 返回 `segments[{start,end,text}]` 且**旧三键逐字兼容**；迁移 V7 `task_result.segments jsonb`；旧任务无 segments 隐藏播放器 |
| **T4.35b** | **2.6 播放器 API**（D-122） | 详情能拿音频地址+句时间 | 待做：预签名/流式音频接口 + 详情返回 segments；越权防护；依赖 T4.35a |
| **T4.35c** | **2.6 前端播放器跟随**（D-122） | 点句跳音频、播放高亮 | 待做：`<audio>` + 点句 seek + 当前句高亮；无 segments 降级；依赖 T4.35b；390 可操作 |
| **T4.36** | **2.7 说话人（先手工）**（D-122） | 转写按说话人分组 | 待做：前端句子打 speaker 标签 → `speaker_map`；纪要/转写分组显示；自动分离先 spike 再定（不承诺） |
| **T4.37** | **2.8 多用户 / 登录**（D-122） | 各看各的、配额按人 | 待做：登录/注册/会话 + 替换 27 处 `DEFAULT_USER_ID` + 数据隔离 + 配额按人；最后做 |

**W4 过关**：spec §9 的 11 条 DoD 全满足 → v1 交付。

---

## 移出 v1（P2/P3 backlog，D-025）

> **砍序是条件式的（D-032）**：**时间不够才砍，时间充足不需要砍。**
> 砍的顺序（**只砍功能**）：**INBOX → 浏览器录制 → 只读分享链接** → 之后才是 RSS → OCR → MCP → 指标。
> **绝不砍**：测试与证据（DoD 里的命令与输出）、交付材料（README / 截图 / GIF / CHANGELOG / Release）、
> 三类场景验证（T4.2 / T4.4 / T4.5 / T4.6）、备份恢复（T2.8）。
> **加回顺序同理**：第 5 周若提前完成，按 INBOX → 录制 → 分享链接 依次加回。

| 项 | 为什么不做 | 以后做的话 |
|---|---|---|
| INBOX 目录自动收取 | 2h，但非三类用户主场景必需（批量上传已覆盖 A） | P2 加 `@Scheduled` 轮询 |
| RECORD 浏览器录制（US-6） | 4h，仅桌面 Chrome/Edge，DRM 录不出 | P2 |
| RSS 播客订阅 | 3h，需要 feed 解析与去重 | P2 |
| 只读分享链接（US-7） | 1h，但非闭环必需 | P3 |
| MCP server | 4h，协议演进风险 | P3 |
| OCR 补充（PPT/板书） | 6h，需图文对齐 | P3 |
| 指标暴露（Prometheus） | 2h，单机自托管用不上 | P3 |
| 说话人分离（US-6 相关） | ASR 侧能力不确定 | P2 |

---

## v1.1 / v1.2 backlog（**交付后**；明细唯一来源，plan.md §11 只放顺序，D-103）

> 用户 2026-11-15 "这些都加上"。**不进 v1**（12-16 交付只剩 T4.15；v1 余量 0，D-032）。
> 口径：每项独立卡 + 独立 commit + 独立验收报告；每项收尾跑一次全量（**3 任务/次**）；凡依赖 LLM 的能力必须有"离线/无 key/EXTRACTIVE 档"的诚实降级。
> 顺序已按"依赖 + 价值/成本比"排好，**按序做，不跳**（跳序会把测试与文档拖散）。

| 编号 | 项 | 成本 | 依赖 | 判据要点（验收时才展开成卡） |
|---|---|---|---|---|
| **T7.1** | 统计摘要 `GET /api/v1/stats`（任务数/总时长/平均 RTF/成功率/档位分布） | 3–4h | — | 数字与 `task`/`task_result` 复算一致；口径写清楚（RTF 定义） |
| **T7.2** | 服务端搜索 + 列表排序（`q` 参数） | 3h | — | 参数化查询防注入；空结果 200；与前端搜索文案一致 |
| **T7.3** | AI 自动标题（搭现有单次结构化调用，不加额外调用） | 3–4h | T7.1 无、但需改输出契约 | 旧数据回退文件名；EXTRACTIVE 用摘要首句并标注来源；导出/列表/详情一致 |
| **T7.4** | 换模板重生成（F-17）`POST /tasks/{id}/restructure?template=x` | 3–4h | T7.3 解析改造可复用 | 不重跑 ASR、不扣任务配额；覆盖前保留旧结果；并发点击幂等 |
| **T7.5** | 中英双语（摘要/关键词/待办） | 2–3h | — | 离线/无 key 显示"翻译不可用"，**不得假装已翻译**；逐字稿只做按段按需 |
| **T7.6** | 纪要问答（检索 = 二元组打分 Top-N 句；LLM 只依据检索句回答 + 引用） | 1.5–2d | 复用 T4.15 高亮选区 | 问原文没有的事必须答"无法依据原文回答"；引用可跳转高亮 |
| **T7.7** | 批量导出 zip | 3h | T4.15 多选 | 流式打包不爆内存；部分失败语义 |
| **T7.8** | 只读分享链接（令牌化、可撤销/过期） | 3–4h | — | 匿名只能访问令牌对应那一条；不泄漏其他端点（K-46） |
| **T7.9** | 浏览器内录音（`getUserMedia` + `MediaRecorder`） | 约 1d | — | 长录音内存/失败重传；Safari 兼容；真机验证 |
| **T7.10** | PWA / 手机安装（manifest + service worker 离线壳） | 4–6h | — | 离线壳不缓存 API 结果；无外链资源 |
| **T7.11** | 任务标签/收藏（`task` 加列 + 迁移） | 4–5h | — | 迁移前备份；旧数据默认值；列表可筛 |
| **T7.12** | 章节/时间轴 + 音频播放（改 ASR 返回 `segments` + 存储 + 预签名播放） | 2–3d | — | 向后兼容旧"只有 text"契约；长音频 JSON 体积；越权访问防护 |
| **T7.13** | 手工说话人标注（`speaker_profile`/`speaker_map` 落库） | 约 1d | **T7.12** | 无 segments 的旧任务隐藏该功能 |
| **T7.14** | 自动说话人分离（pyannote 可行性 spike 先行） | 1–3d（**不确定**） | T7.12 | spike 结论=可做才排；否则明确"不做"并写进 README 限制 |
| **T7.15** | 自定义模板（提示词编辑） | 约 1d | — | 输入校验/长度限制；不得拼进系统级指令造成注入 |
| **T7.16** | **摘要级依据**（前端重算：摘要逐句 ↔ 原文句，匹配不到如实写"未找到"；口径同 T4.15⑧ 关键词/待办依据 D-104） | 1–2h | — | **提前为 T4.23（D-121）**；纯 static、不新增 LLM/请求；诚实文案 |

**RSS 播客订阅 / INBOX 目录收取**：仍属 `移出 v1` 的 P2/P3 backlog（D-025/D-032），本次"都加上"未包含，保持原状。

---

## 依赖顺序（不可打乱）

```text
T0.1 → T0.2 → T0.4 → T1.1 → T1.2 → T1.3 → T1.4 → T1.5 → T1.6/T1.7/T1.8/T1.9
                                                    ↓
                                          T2.1..T2.9
                                                    ↓
                                T3.1..T3.10（T3.3 依赖 T3.1+T3.4；T3.9 依赖 T3.1）
                                                    ↓
              T4.1..T4.11（T4.6 依赖 T2.2；T4.5 依赖 T3.3；T4.8 依赖 T2.8；T4.9 依赖 T4.10 素材）
```

---

## 计数

| 阶段 | 任务数 | 预估 |
|---|---|---|
| 阶段 0 | 5 | 8h |
| 第 1 周 | 9 | 13.5h |
| 第 2 周 | 11 | 15h |
| 第 3 周 | 10 | 17h |
| 第 4 周 | 13 | 16h |
| **合计（不含第 5 周）** | **48** | 约 69.5h |

> 含第 5 周（+17h / 7 个任务）→ **总计 55 个任务 / 约 86h**，见文末「计数（5 周版）」（D-030/D-054/D-056/D-057）。

---

## 由真实数据引发的增量（2026-11-13 实测，D-027–D-029）

实测：`C:\Users\ljh\Videos` = **20 文件 / 8.44 小时 / 0.9 GB**，其中 9 组 mp4 与 mp3 时长完全一致
（证明手工剪映转换过 9 次）。详见 `真实数据-文件与耗时实测.md`。带来 4 处增量：

| 增量 | 落到哪个任务 | 额外成本 |
|---|---|---|
| 单任务上限 **30 → 60 分钟**（可配 120），拒绝文案同步改 | **T1.4** | 0 |
| **批量上限 10 → 20**，提交后显示**总时长 + 预计耗时** | **T4.7**、§5.2 | +0.5h |
| **手机浏览器上传（P1）**：iOS Safari / Android Chrome 能选文件并上传 | **T1.6**（页面）+ **T4.1**（验证） | +1h |
| 第 **5** 个模板 `shortvideo`（核心观点/论据/可执行动作/是否值得收藏） | **T3.9** | +0.5h |
| README 写清容量预期（8.44h 音频 ≈ 多久转写，按实测 RTF） | **T4.10** | 0 |
| **验收新增**：用 5 个真实长文件（>30 min）跑一遍，全部 DONE | **T4.7** | 0 |

**总量**：66h → **83h（含链接导入，见第 5 周）**，50 个任务。
**回收原则（D-032，用户 2026-11-13 确认）**：**不砍交付材料**（截图/GIF/CHANGELOG/Release 属质量与证据）；
需要腾时间时只砍**功能**，顺序见「移出 v1」一节。

> ⚠️ **T5.5 是本项目最有说服力的一条验收**：用真实录音跑通，而不是 3 个漂亮小样本。
> **原 `Videos` 里的 mp3 已被本人删除**（2026-11-13 晚），据此把仍存在的真实音频归档到
> `D:\datasets\meeting-notes-samples`（**11 文件 / 4.93 小时**，含 5 个 >30min、一对同内容 mp4/wav），
> 详见 `测试语料-清单.md`。
> **T5.5 定义**：用该语料批量跑完 → 全部 DONE、失败 0、记录总耗时与平均 RTF、最长 56.7min 不被拒。

---

## 新增：链接导入（L1 + L2 + BYOF，D-027R，+2 天）

> 由你「为什么不能直接粘视频链接 + 让用户确认」的追问触发，方案见 `链接处理方案.md`。
> **明确不做的仍是 L3（内置全平台解析器）**：合规风险 + 维护黑洞，理由见该文档 §3。

| ID | 任务 | DoD | 验证命令 / 期望 |
|---|---|---|---|
| **T6.1（L1）** | 粘链接 → 抓公开元信息（`og:title` / `og:image` / 公开时长）→ 返回确认卡片 | 贴 3 个不同平台的链接，都能返回标题 + 封面，且**不伪造 UA 签名、不带 cookie** | `POST /api/v1/link/preview {"url":"..."} ` → 返回 `{title, cover, duration?}`；失败返回明确错误码 |
| **T6.2（L2）** | 直链探测：普通 GET 跟随公开重定向，`Content-Type: media/*` → 直接建任务 | 直链平台能"粘链接就开跑"；页面链接优雅降级 | 贴一个分享链接（最终指向媒体文件）→ 任务直接进入 PENDING；贴抖音页面链接 → 返回"需导出上传"提示 |
| **T6.3（BYOF）** | `MediaFetcher` 接口 + `FETCHER_CMD` 配置；仓库不含任何平台解析器；README 声明责任边界 | 配置 `FETCHER_CMD` 后能调用外部命令取文件；不配置时该路径不可用 | 不设 `FETCHER_CMD` → 接口返回 `FETCHER_UNAVAILABLE`；设置 `echo` 假命令 → 能被调用（证明接缝存在） |
| **T6.4** | 链接预览的安全校验：SSRF（L1/L2 都要）、重定向次数上限、超时、响应体大小上限 | 内网地址被拒；重定向成环不死循环 | `{"url":"http://169.254.169.254/"}` → 400 `URL_BLOCKED`；构造重定向环 → 超时退出 |

**工期影响**：67h → **83h**（+16h）。**已定：延到第 5 周（D-030）**，T6.1–T6.4 由第 5 周的 T5.1–T5.4 承担。

**依赖**：T6.x 依赖 T2.1（幂等）与 T4.3（SSRF 基础设施，可共用代码）。

---

## 第 5 周排期（**已定：延第 5 周**，D-030）

> 交付 2026-12-16；简历 2026-12-15。W1–W4 按上文不变，第 5 周补交付收尾。

| ID | 任务 | DoD | 验证命令 / 期望 |
|---|---|---|---|
| **T5.1** | 链接导入 L1（确认卡片） | 3 个平台链接都能返回标题+封面 | `POST /api/v1/link/preview` → `{title,cover}` |
| **T5.2** | 链接导入 L2（直链探测） | 命中直链直接建任务；页面链接降级提示 | 见 T6.2 判据 |
| **T5.3** | BYOF：`MediaFetcher` + `FETCHER_CMD` | 未配置时明确不可用；配置后能被调用 | 见 T6.3 判据 |
| **T5.4** | 链接安全：SSRF / 重定向上限 / 超时 / 响应体上限 | 内网被拒、重定向成环不死循环 | 见 T6.4 判据 |
| **T5.5** | 真实库全量回归：11 个真实内容（8.44h）批量跑完 | 全部 DONE，记录 RTF 与总耗时 | 用 `C:\Users\ljh\Videos` 真实文件 |
| **T5.6** | 收尾：CHANGELOG + `v0.1.0` tag + 发布（D-026 最小集） | 陌生人照 README 30 分钟跑通 | 临时目录照做一遍 |
| **T5.7** | 简历定稿 + 面试材料定稿（只填实测值） | 无 `【TBD】` 残留 | 逐项核对数字台账 |

---

## 计数（5 周版）

| 阶段 | 任务数 | 预估 |
|---|---|---|
| 阶段 0（底座验证） | 5 | 8h |
| 第 1 周 | 9 | 13.5h |
| 第 2 周 | 11 | 15h |
| 第 3 周 | 10 | 17h |
| 第 4 周 | 13 | 16h |
| **第 5 周** | **7** | **17h** |
| **合计** | **55** | **约 86h** |

> 原 T6.1–T6.4（链接导入）合并进第 5 周的 T5.1–T5.4，编号以第 5 周为准。
> **+3h 说明**（83h → 86h）：T1.9 测试入口 0.5h + T4.12 死配置体检 0.5h + T4.13 真机 UI 验收 1h + T2.11 契约收口 0.5h + T2.12 错误语义收口 0.5h，**全是验证/工具/收口，不是功能**（D-054/D-056/D-057）；
> 功能清单与砍序（D-032）不变，但余量已经为 0：**再想加功能必须走"砍一个才加一个"**。
