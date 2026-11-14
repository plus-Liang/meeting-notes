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

**下一步**：**T2.9 本周单测/验收 + 三处收口**（429+`Retry-After` 真 HTTP 复验、F-1 测试日志一行修、F-4 restore preflight exit 20）→ 第 3 周 T3.x 结构化/三档 → T4.x 交付。**阶段 1 全 ✅；第 2 周 T2.1–T2.8 / T2.10–T2.12 ✅，仅 T2.9 未做**。
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
| **T2.9** | 本周单测/验收 + 三处收口 | `mvn test` 全绿 + 429 真 HTTP | **卡已写**：429+`Retry-After` 真 HTTP 复验（D-061 并入）、F-1 测试日志一行修（`test` 配置 `app.log-json:false`）、F-4 restore preflight exit 20（D-064） |
| **T2.10** | **服务端任务列表 API**（D-053） | 换浏览器/清缓存仍能看到历史任务 | ✅ `08e862d` + `review/2026-11-14-T2.11-T2.10验收.md`。`GET /api/v1/tasks?status=&limit=&before_id=`：keyset（`id<before_id ORDER BY id DESC LIMIT n+1`，禁 OFFSET）、`LIMIT+1` 探测下一页、`status` 过滤、`original_name` 从 `source_uri` 解析、snake_case。我复跑：默认列表 `[23,22,21]`+`next_before_id:null`；`limit=2`→`[23,22]`、游标 22→`[21]` **不重不漏**；`status=DONE` 过滤正确；`limit=51/0/abc`、`before_id=xyz` 全 400；`tasks.html` 200/3503B、只调服务端 API、localStorage 仅兜底、3s 轮询仅在有未完成任务时；截图（无痕 4 条历史）已核对；`style.css`/`app.js` 未动。**缺陷**：参数错误码借用 `INVALID_MEDIA`（F-2）、`status=BOGUS` 静默 200（F-3）→ **T2.12** |
| **T2.11** | **契约与死信收口**（D-056） | 死信列表里不出现 DONE 任务；500 响应体带 `code` | ✅ `08fbfd6` + 同上报告。复活时删 `dead_letter`；存储故障 → `500 STORAGE_ERROR`；加 `@ExceptionHandler(Exception)` 兜底 `500 INTERNAL_ERROR`（不泄细节）；`test.ps1` 去掉本机路径兜底（未设 `ASR_SMOKE_AUDIO` → exit 3）。我复跑：DEAD 复活后死信 **1→0 行**；停 MinIO 上传 → **500 + `code:"STORAGE_ERROR"`** 且无孤儿行；单测 **33/33** exit 0；脚本 exit 0。**回归缺陷**：兜底把框架 4xx 全吞成 500（F-1）→ **T2.12** |
| **T2.12** | **错误语义收口**（D-057） | 404 仍 404、405 仍 405、415 仍 415、类型错 400 | ✅ `9bc93e4` + `review/2026-11-14-T2.12-T2.3验收.md`。兜底 handler 改为「`ErrorResponse` 按原状态码 + 通用码，真未知才 500」；参数码改 `INVALID_PARAM`；`status` 白名单严格校验。我复跑七连击全对：`/api/v1/nope` 404 `NOT_FOUND`、`/nope.html` 404、`/tasks/abc` 400 `INVALID_PARAM`、`DELETE` 405、JSON POST 415、`?limit=abc` 400、`?status=BOGUS` 400；回归：`/tasks/999999` 仍 404 `TASK_NOT_FOUND`、`?status=DONE` 仍 200、`GET /` 200；单测 **40/40** |

**W2 过关**：US-2 三场景全过；N1–N5、N8、N9、N18–N20 有证据。

---

## 第 3 周：结构化 + 三档 AI + 模板 + 闭环回流（约 17h）

| ID | 任务 | DoD | 验证命令 / 期望 |
|---|---|---|---|
| **T3.1** | 摘要生成：①云 `ChatModel` | ≥3 条摘要且不编造 | 人工比对 3 样本无幻觉 |
| **T3.2** | 待办抽取：JSON Schema + 解析失败重试 | 含 `action/owner/deadline` | Jackson 反序列化成功 |
| **T3.3** | **三档降级 + `ai_tier` 落库** | 按 ①→②→③ 选择并记录 | 删 key → 降 EXTRACTIVE 仍产出 |
| **T3.4** | 抽取式兜底（分句 + 关键句 + 规则待办） | 零依赖出 ≥3 摘要 + ≥1 待办 | 关掉全部 LLM 配置 → 走通 US-1 |
| **T3.5** | 改词 → 写 `glossary`；待办可勾选 | 改一次即入库；勾选持久 | 改「章总」→「张总」→ DB 有记录；刷新后仍勾选 |
| **T3.6** | 术语注入下次转写（US-3 闭环） | 第二次输出正确 | 按 US-3 Independent Test 走一遍 |
| **T3.7** | 导出 Markdown + `PASTE` 入口（含 ass） | 文件含三节；srt 秒出纪要 | 下载确认；传 srt 跳过转写 |
| **T3.8** | 本周单测（Schema 畸形、三档选择、模板选择） | `mvn test` 全绿 | BUILD SUCCESS + 用例数 |
| **T3.9** | **摘要模板机制**（US-12）：会议/课程/访谈/播客 4 模板 + 提交时可选 + 按文件名自动推荐 + 落库 | 同音频换模板 → 结构不同且都可用 | 用"腾讯会议"命名的文件 → 自动选会议模板；手工选课程 → 输出含知识点/考点 |
| **T3.10** | **格式扩展校验**：amr / opus / ass 可解可转 | 三种格式各传一个真实样本 → DONE | 各格式转写成功，失败时给出"请转 mp3"提示 |

**W3 过关**：US-3 Independent Test 通过（闭环成立）；N15 三档有证据；US-12 四场景有证据。

---

## 第 4 周：交付、三类场景验证、开源发布（约 14h）

| ID | 任务 | DoD | 验证命令 / 期望 |
|---|---|---|---|
| **T4.1** | 列表筛选 + 批量重试 + 设置页（术语表/配额/当前 AI 档位） | 无 JS 报错 | 筛"失败"→ 批量重试成功 |
| **T4.2** | 端到端自动化测试（N14） | 一条命令跑通 | `mvn test -Dtest=E2ETest` → BUILD SUCCESS |
| **T4.3** | `FETCH` 直链 + SSRF 防护（N7） | 内网地址被拒 | `{"url":"http://169.254.169.254/"}` → 400 `URL_BLOCKED` |
| **T4.4** | **零成本验证（N15）** | 清空 key → P1 故事仍走通 | 注释全部 key 重启 → US-1 成功 |
| **T4.5** | **离线验证（US-9 / N16）** | 断外网 + 无 key → US-1 成功且无出站 | 断网走完 US-1；`ai_tier≠CLOUD` |
| **T4.6** | **接入面（US-10 / N17）** | API Token + OpenAPI + Webhook + 验签 | 三条 curl 全流程；20 行接收端验签通过 |
| **T4.7** | **批量提交（US-8）**：一次 10 个文件互不影响 | 10 个全部终态 | 一次选 10 个 → 全部完成，失败可单独重试 |
| **T4.8** | 重启不丢验证（N20） | `down`（不删卷）→ `up` → 数据在 | 任务与结果均可查 |
| **T4.9** | **开源发布最小集（D-026）**：一键脚本 / 示例数据 / 3 截图 + 1 GIF / LICENSE + CONTRIBUTING + issue 模板 / CHANGELOG + `v0.1.0` tag | 陌生人照 README 30 分钟跑通 | 临时目录照做一遍（含 `git tag` 检查） |
| **T4.10** | README（含"你不该用本项目的场景"、三档 AI 差异、四个模板示例）+ 3 分钟录屏 | 内容齐全 | 逐项目视检查 |
| **T4.11** | 简历条目 + 面试材料定稿（**只填实测值**） | 无目标值冒充实测 | 对照 `面试材料.md` 数字台账 |
| **T4.12** | **发布前死配置体检**（D-054）：`app.*` 每个键必须有消费方；被裁功能的配置项 + `.env` 键**连带删** | 无"填了没反应"的配置 | 孤儿配置 = 0（或逐条登记为"计划中"）；核对 `配置清单.md` §2 的 `$appKeys` 快照（当前 **34 键**）与 yml 一致；INBOX 若被裁 → `app.inbox.*` + `INBOX_*` 一起删 |
| **T4.13** | **真机 UI 验收**：浏览器上传交互（文件选择器）/ 移动端上传 / 无痕窗口列表 | 三条真机点过并截图 | 手机真实上传 1 个文件 → 列表可见；上传页拖拽 + 选择器各一次 |

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

> ⚠️ **T4.12 是本项目最有说服力的一条验收**：用真实录音跑通，而不是 3 个漂亮小样本。
> **原 `Videos` 里的 mp3 已被本人删除**（2026-11-13 晚），据此把仍存在的真实音频归档到
> `D:\datasets\meeting-notes-samples`（**11 文件 / 4.93 小时**，含 5 个 >30min、一对同内容 mp4/wav），
> 详见 `测试语料-清单.md`。
> **T4.12 定义**：用该语料批量跑完 → 全部 DONE、失败 0、记录总耗时与平均 RTF、最长 56.7min 不被拒。

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
