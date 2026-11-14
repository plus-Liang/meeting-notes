# 任务卡（给写代码的 AI）—— 阶段 0 + 第 1 周

> 用法：**一次只做一张卡**。做完贴证据（命令 + 输出 + exit code），等人确认后再做下一张。
> 权威任务总表是 `tasks.md`；本文件是"可直接执行版"，字段：目标 / 步骤 / 验证 / 通过标准 / 失败预案。
> 全部命令在 `D:\new\projects\meeting-notes` 下执行，并先设好环境（见 `AGENT.md` §4）。
> **提示词方法出处**：`D:\new\docs\协作\11月13日-音频总结.md`；可复制模板见 `D:\new\docs\协作\开发提示词模板.md`。

## 每张卡的固定四段（缺一段不算开工）

```text
【规划四问】改哪里（文件路径列全）｜复用什么（已有类/配置/脚本）｜影响哪些功能（含对外接口、DB、配置）｜怎么验证（命令+期望+exit code）
【范围声明】本次只改 <目录/文件>；不动 <明确列表>。要动公共组件（application.yml 配置键 / docker-compose / Flyway 迁移 /
            /api/v1 响应字段 / AGENT.md 规则）或扩大范围 → 先写"原因+影响+替代方案"等人确认。
【验收】反例必须进：坏输入要被拒（例：exe 改名 .mp3 → 400 INVALID_MEDIA）
【可维护性】commit 前必跑 `powershell -File tools\check-maintainability.ps1` → 必须 exit 0
            （宪法 VII；细则 M-1~M-10 见 `可维护性要求.md`；允许清单只减不增）
【回报·测试三问】检查了哪些场景 ｜ 实际运行了哪些测试（命令+输出+exit code）｜ 还有哪些没验证（不许留空）
```

> "范围限制不能只靠提示词"：改完必须自己 `git diff --stat` 自查 + 一卡一 commit（可回滚）；公共面的实际改动由 Lead 收口复看。

---

## T0.1 起 ASR 服务（自建 OpenAI 兼容容器）

**目标**：有一个本地 ASR 服务，暴露 OpenAI 兼容接口，供 Java 侧调用。

**为什么自建**：Docker Hub 不通、公共加速源白名单拒绝了现成镜像；本机已有 `python:3.12-slim` 镜像
和 `D:\huggingface` 里 4.21GB faster-whisper 模型 → 自建最稳。

**要新建的文件**
```text
infra/asr/
├─ Dockerfile          # FROM python:3.12-slim；装 ffmpeg（apt）+ pip 装 faster-whisper（清华源）
├─ requirements.txt    # faster-whisper, fastapi, uvicorn, python-multipart
├─ server.py           # OpenAI 兼容服务（约 100 行）
└─ README.md           # 接口说明 + 启动方式
```

**接口契约（必须严格按这个，Java 侧只认它）**

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/health` | 返回 `{"status":"ok"}` |
| `GET` | `/v1/models` | 返回 `{"data":[{"id":"<ASR_MODEL_NAME>"}]}` |
| `POST` | `/v1/audio/transcriptions` | multipart：`file`（音频/视频）、`model`、可选 `language`；返回 `{"text":"…"}` |

**环境变量（不许硬编码）**：`ASR_PORT`、`ASR_MODEL_DIR`（挂载 `/models`）、`ASR_MODEL_NAME`（默认 `small`）、
`ASR_MAX_CONCURRENCY`、`ASR_LANGUAGE`（默认 `zh`）、`HF_HOME`（指向挂载目录，离线加载）。

**步骤**
1. 写 `server.py`：启动时按 `ASR_MODEL_DIR` + `ASR_MODEL_NAME` 加载模型（用 `faster_whisper.WhisperModel`），
   音频用 `ffmpeg`（容器内）先转 16k 单声道 wav，再 `model.transcribe()`。
2. 写 `Dockerfile`，在 `docker-compose.yml` 里加 `asr` 服务：挂载 `D:\huggingface:/models:ro`，暴露 `ASR_PORT`，带 healthcheck（打 `/health`）。
3. `.env.example` 里补上 ASR 相关变量（`ASR_IMAGE` 改成自建 build，不再用远程镜像）。

**验证命令**
```powershell
docker compose up -d asr
docker compose ps                       # asr 应为 healthy
curl.exe -s http://localhost:8000/health
curl.exe -s http://localhost:8000/v1/models
```

**通过标准**：`/health` 与 `/v1/models` 均 200 且有内容；`docker compose ps` 显示 healthy。

**失败预案**（按顺序，别硬扛）
1. `pip install` 失败 → 换 `https://pypi.tuna.tsinghua.edu.cn/simple`；仍失败 → 换阿里云 pip 源；
2. 模型加载失败 → 检查挂载路径与 `HF_HOME`；改用 `ASR_MODEL_NAME=small`；
3. 容器起不来 → 先跑 `docker run --rm -v D:\huggingface:/models:ro python:3.12-slim python -c "print(1)"` 验证挂载；
4. 全部失败 → 停下来报告（不要偷偷换成云 ASR）。

---

## T0.2 用 curl 转写真实 mp3

**目标**：证明这个 ASR 服务能转作者的真实文件。

**验证命令**
```powershell
curl.exe -s -X POST http://localhost:8000/v1/audio/transcriptions `
  -F "file=@C:\Users\ljh\Videos\11月9日.mp3" `
  -F "model=small"
```
（选一个**短文件**先试：`11月9日(1).mp3` 只有 3.9 分钟。也可以先截取 30 秒。）

**通过标准**：返回 JSON，`text` 非空、是中文、**有标点**。
**要记录**：转写耗时（用于 T0.3）。

---

## T0.3 实测 RTF 与资源占用

**目标**：拿到"8.44 小时音频要跑多久"的真实数字。

**步骤**：跑 3 个不同长度的文件（约 4 min / 13 min / 34 min），记录：(音频时长, 转写耗时, RTF=耗时÷时长, 容器内存峰值)。

**交付**：把结果写进 `docs/项目/录音纪要平台/state.md`（同步副本可改，但**同时告诉人类**以便更新权威版），格式：
```text
RTF 实测：4min→xx s (RTF 0.xx) / 13min→xx s / 34min→xx min
内存峰值：xxx MB
推算 8.44h 总耗时：约 x.x 小时
```

**通过标准**：三个数字齐全，且给出 8.44h 的推算。

---

## T0.4 Spring AI 最小转写跑通

**目标**：Java 侧能调通 ASR（这是架构里最关键的缝）。

**步骤**
1. `app/src/test/java/com/meetingnotes/AsrSmokeTest.java`：注入 `TranscriptionModel`，转写一个本地文件，断言 `text` 非空并打印。
2. `application.yml` 的 `spring.ai.openai.audio.transcription.base-url` 指向 **`http://asr:8000`**
   （⚠️ **不带 `/v1`**：Spring AI 会自己追加 `/v1/audio/transcriptions`，带了就变成 `/v1/v1/...` → 404，见 D-034）。
   本机测试时用宿主机映射端口：`ASR_BASE_URL=http://localhost:8001`。
3. 版本号按 `pom.xml` 里的 TODO 去官方核对最新 GA（**不要照网上旧教程**）。

**验证命令**
```powershell
mvn -q -Dtest=AsrSmokeTest test
```

**通过标准**：BUILD SUCCESS，且控制台打印出中文文本。

---

## T0.5 云 LLM 通道跑通（智谱优先）

**目标**：证明"摘要"这一层能调通任意 OpenAI 兼容供应商。

**步骤**
1. 在 `.env` 填 `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`（**base-url 与模型名必须先 curl 核对官网**）。
2. 写一个最小测试：给一段文本，让 `ChatModel` 输出 3 条摘要。
3. 把**实测通过的** base-url 与模型名写进 `docs/项目/录音纪要平台/decisions.md` 的 D-007 备注。

**通过标准**：打印出摘要；把实测值记录下来。

---

## T1.1 代码仓骨架收尾 ✅ **已完成（`6b30779`）**

**目标**：仓库干净、可追溯。
**步骤**：确认 `.gitignore` 生效（`.env` 不被跟踪）；一次 commit 只做一件事；提交信息 `<type>(<scope>): <做了什么>`。
**验证**：`git status --short` 干净；`git log --oneline` 可读；`git ls-files | Select-String '\.env$'` 为空。

---

## T1.2 docker compose 一键起（pg / redis / minio / asr / app）✅ **已完成（`fb45588`）**

> 已踩过的坑（照抄别重踩）：`REGISTRY_PREFIX=docker.m.daocloud.io/` 只对 `library/` 镜像有效；
> minio/ollama 被 daocloud 白名单拒 → 用 `MINIO_IMAGE`/`OLLAMA_IMAGE` 整体覆盖到华为云 `ddn-k8s`（D-038）。
> app 镜像必须 JDK 21；apt 换源要 `grep -rlE ... /etc/apt | xargs -r sed`（deb822 格式，D 系列见 `环境搭建说明.md` §7）。

**验证**
```powershell
docker compose down -v
docker compose up -d --build
docker compose ps        # 全部 healthy
```
**通过标准**：干净环境下一次成功，全部服务 healthy（**不要用 `sleep` 等依赖，一律 healthcheck**）。

---

## T1.3 Flyway 建表生效 ✅ **已完成（真实 PG16.16 验证）**

**验证**：`docker compose exec -T postgres psql -U mn -d meetingnotes -c '\dt'`
（`POSTGRES_USER=mn`，用 `-U postgres` 会报角色不存在）
**通过标准**：看到 `task` / `task_result` / `glossary` / `speaker_profile` / `quota` / `dead_letter` / `webhook_delivery` 七张表
（+ `flyway_schema_history` 共 8 张）；重复执行迁移不报错（`Schema "public" is up to date`）。

---

## T1.4 上传接口（校验 → MinIO → 写 task）✅ **已完成（`3ce9386`）**

**要做**：`POST /api/v1/tasks`（multipart）；校验顺序：文件头 magic number → 大小 ≤200MB → 时长 ≤60min → 配额；
通过后存 MinIO（按 taskId 分目录），写 `task` 表（status=PENDING，content_hash 计算好）。

**验证**
```powershell
# 坏文件：把 exe 改名成 .mp3 → 期望 400 INVALID_MEDIA
# 好文件：期望 201 + {"task_id":...}
```
**通过标准**：两条各按预期；`content_hash` 落库。

> 验收结论见 `docs/协作/review/2026-11-13-T1.4验收.md`：5 场景全过，但留了 3 个必须在 T1.5 前修的项（见下）。

---

## T1.5 worker 消费出转写结果（**先修契约，再做 worker**）

### 第 0 步：修 T1.4 审查遗留（一个 commit，约 20 行）

1. **P1 契约（D-041）**：全局 Jackson `property-naming-strategy: SNAKE_CASE`（yml 一行），
   响应改成 `task_id` / `content_hash` / `duration_sec` / `size_bytes` / `reused`；
   错误码改成 `SIZE_EXCEEDED`(413) / `DURATION_EXCEEDED`(400)。以 `接入指南.md` §3.1/§3.3 为准。
2. **P2 幂等状态失真**：命中已有任务时返回**库里的真实 status**（不要硬编码 PENDING）。
3. **P2 越权隐患（D-040）**：`findIdByHash` 加 `AND user_id = ?`；以后所有按内容/任务的查询都带 `user_id`。
4. 顺手：`QuotaService.consume` 返回的第二个值应是 `usedTasks` 而不是 `tasksPerDay`（当前无人使用，改名即可）。

验收：`mvn -q test` 绿；真机重跑 T1.4 的 5 个场景，响应字段/错误码与 `接入指南.md` 完全一致（贴原始 JSON）。

### 第 1 步：worker

**要做**：worker 取 PENDING → RUNNING → 容器内 ffmpeg 抽 16k 单声道 → 调 ASR → 写 `task_result` → DONE。

要点：
- 队列用 Redis（`spring.data.redis` 已配好）；**必须**用 `BRPOP`/`BLPOP` 这类阻塞取，别轮询空转。
- 抽音轨：`ffmpeg -i <input> -vn -ac 1 -ar 16000 -f wav <tmp>`（容器内已装 ffmpeg 8.0.1）。
- 调 ASR：容器内地址 **`http://asr:8000`**（不是宿主 8001），`TranscriptionModel` 已配好，直接复用。
- 写结果：`task_result(transcript, ai_tier…)`；状态机 `PENDING → RUNNING → DONE`，失败进 `RETRYING`（重试次数用 `TASK_MAX_RETRIES`）。
- 幂等/崩溃恢复先不做（T2.1/T2.3），但**不许**把任务永久卡在 RUNNING（至少 catch 后写回 RETRYING 或 DEAD）。
- 同时补 **`GET /api/v1/tasks/{id}`**（`接入指南.md` 的 snake_case：`task_id` / `status` / `duration_sec` / `size_bytes`；
  完成时附 `transcript` 或 `result_url`，未完成时 transcript 为 null）。前端轮询和接入方都靠它。

**验证**：用真实语料 `D:\datasets\meeting-notes-samples\a_e91acf.wav`（3.9 分钟）走完整链路
`POST /api/v1/tasks` → 轮询 `GET /api/v1/tasks/{id}` → `status=DONE` 且 `task_result.transcript` 非空（中文有标点）。
贴：task 表状态流转、task_result 行、ASR 容器日志里的那次请求、端到端耗时。
**通过标准**：端到端一次成功；失败时状态迁移正确（不卡 RUNNING）。

---

> 验收结论见 `docs/协作/review/2026-11-13-T1.5验收.md`：主链路 31s / 失败路径 9s / 404 / 幂等 4 组真机全过，
> 发现并已拍板 4 条：D-046（内部重试收敛 2 次）、D-047（错误码扩到 7 个）、D-048（空文本 = DONE）、D-049（DEAD 重传归 T2.1）。

---

## T1.6 三页面（提交 / 列表 / 详情）+ 空文本语义修正（D-048）

【规划四问】
- **改哪里**：`app/src/main/resources/static/`（新增 `index.html` 提交页 / `tasks.html` 列表页 / `task.html` 详情页 + `app.js`）；
  `web/TaskController.java`（`GET /` 指到提交页）；`worker/TranscriptionWorker.java`（**仅** D-048 那个分支：空文本不再抛异常）；
  `app/src/test/java/com/meetingnotes/worker/`（新增 1 个单测）。
- **复用什么**：`GET /api/v1/tasks/{id}`（T1.5 已实现，snake_case）、`POST /api/v1/tasks`（T1.4）、
  `TaskStatusResponse` 字段、`ApiException` 错误码（D-041/D-047）。
- **影响哪些功能**：对外只有 `GET /` 新增（不破坏 API）；DB 无迁移；配置无新增键；`/api/v1` 响应字段不变。
- **怎么验证**：见【验收】5 条 + `mvn -q test`（≥15 用例）+ 可维护性脚本 exit 0（命令与期望都在下面）。

【范围声明】
- **做**：①三张静态页 + 原生 JS（**不引前端框架、不引构建链**，D-006/时间盒）；②**三页共用一份 `static/app.css` 与一组样式变量**
  （颜色/间距/圆角/字号各页只引用、不许各写一套；同类交互只用一种控件形式）——11/14 音频「腐化第 1 类：样式各自实现」的直接对策；
  ③列表页对未完成项 3 秒轮询（D-024，不用 WebSocket）；
  ④详情页 5 种状态文案：`PENDING/RUNNING`（等）、`DONE`（全文）、`DONE + transcript=""`（「未识别到语音内容」，D-048）、
  `RETRYING`（重试中，别当失败）、`DEAD`（显示 `error_code`）；⑤`/tasks/999999` → 「任务不存在」（404 文案，D-047）；
  ⑥【前置】空文本语义改 DONE + 空串，补单测；⑦文件名/时长/大小按上限提示（60min / 200MB，D-028）。
- **不动**：认证（T4.6）、摘要/待办（T3.x）、编辑修正（T2.x）、SSE/WS、外链抓取（D-027R）、
  `application.yml` 键、`docker-compose.yml`、Flyway 迁移、`/api/v1` 的**已有**字段（字段只增不改，D-041）。
- **允许的唯一契约改动（S-3）**：给 `GET /api/v1/tasks/{id}` **追加** `error_code`（nullable，仅 DEAD/RETRYING 时有值），
  详情页靠它显示失败原因；改完同步 `接入指南.md` §3.2/§3.3 一行。除此之外加新 API/新字段 → 先写"原因 + 影响 + 替代方案"等确认。

【验收】反例必须进：
```powershell
docker compose up -d                                          # 5/5 healthy
# ① 正例：真实音频 → 三页走通，详情页出全文（贴 task 状态流转 + 页面截图）
# ② 正例：6s 纯正弦波 wav → DONE + 「未识别到语音内容」（D-048）
#    ffmpeg -f lavfi -i "sine=frequency=440:duration=6" -ar 16000 -ac 1 tone.wav
# ③ 反例：GET /tasks/999999 → 页面显示「任务不存在」，不是白屏/500
# ④ 反例：停 ASR（docker stop meeting-notes-asr-1）上传 → 列表出现 RETRYING 后 DEAD，详情页显示 ASR_ERROR；
#          验完立刻 docker start meeting-notes-asr-1
# ⑤ 反例：浏览器 console 无报错；页面无 undefined / [object Object]
cd app; mvn -q test                                           # 期望 exit 0，用例数 ≥15
powershell -File tools\check-maintainability.ps1               # 期望 exit 0
```
**通过标准**：5 条各按预期；测试数从 14 涨到 ≥15（新逻辑必须有测试，M-8）；脚本 exit 0；
页面只用相对路径（禁止硬编码 `localhost:8080`，M-1/M-4）。

【可维护性】commit 前必跑 `powershell -File tools\check-maintainability.ps1` → 必须 exit 0
（宪法 VII；M-1~M-10 见 `可维护性要求.md`；允许清单只减不增；页面 JS 里同样禁止魔法地址与 `console.log` 残留）。

【回报·测试三问】检查了哪些场景（列全含上面 5 条）｜ 实际运行了哪些测试（命令 + 关键输出 + exit code）｜ 还有哪些没验证（**不许留空**）。

---

## T1.7 配置外置基线自检（只读验证卡，不改代码）✅ **已完成（证据在 `review/2026-11-14-T1.7-T1.8验收.md`，只读卡无 commit）**

【规划四问】
- **改哪里**：**不改代码**。只读跑 `配置清单.md` §2 的三条命令 + 扫一遍 `application.yml` / `.env.example`；发现硬编码 → **先报告再改**（最小修复）。
- **复用什么**：`配置清单.md` §2 命令、`.env.example`、`check-maintainability.ps1` 的 M-1/M-2 检查。
- **影响哪些功能**：无运行时行为变化（纯验证）；若修硬编码，仅改动配置读取处。
- **怎么验证**：三条命令逐条跑并贴输出 + `docker compose config` 无告警 + 白名单外差集 0。

【范围声明】
- 做：跑三条命令、核对 yml ↔ `.env.example` 变量差集、Java 侧硬编码扫描（端口/路径/阈值/key）、把结果写进回报。
- 不动：`docker-compose.yml`、Flyway 迁移、`/api/v1` 字段、`Dockerfile`；发现不一致只**报告**，不擅自扩大修复。

【验收】（反例必须进）
```powershell
$env:JAVA_HOME='E:\tools\jdk-21.0.2'; $env:PATH="E:\tools\apache-maven-3.9.9\bin;$env:PATH"
# ① 配置清单 §2 三条命令（原样跑，贴输出 + exit code）
# ② 变量差集：yml 里 ${XXX} 引用 vs .env.example 键 → 差集必须 0
docker compose config 2>&1 | Select-String -Pattern 'warn|variable is not set'   # 期望 0 命中
# ③ Java 硬编码扫描：端口/绝对路径/阈值字面量（期望 0 命中，或逐条说明为何必须硬编码）
# ④ 反例：临时把 yml 里一个变量改成不存在的名字 → 启动报错 → 改回来（只贴证据，不留改动）
powershell -File tools\check-maintainability.ps1    # 期望 exit 0
```
**通过标准**：①②③ 全部 0 异常或逐条给出理由；④证明"配置错会立刻炸"而不是静默用默认值；脚本 exit 0。

【可维护性】不改代码则跑脚本确认现状 exit 0；若修硬编码，改完必须再跑 exit 0（M-1 阻塞项）。

【回报·测试三问】检查了哪些场景 ｜ 实际运行了哪些命令（含 exit code + 关键输出）｜ 还有哪些没验证（**不许留空**）。

---

## T1.8 单测补强（上传校验 + worker 状态机 S-1）✅ **已完成（`a352e5f`，23/23）**

【规划四问】
- **改哪里**：`app/src/test/java/com/meetingnotes/upload/`（新增上传校验单测）+ `worker/TranscriptionWorkerTest.java`（补 RETRYING→DEAD 状态机分支）。
- **复用什么**：已有 `MediaMagicTest` 的测试写法、Mockito（已在用）、H2（`NoKeyStartupTest` 已配）。
- **影响哪些功能**：只加测试，不改业务代码；用例数 17 → **≥20**。
- **怎么验证**：`mvn -q test` exit 0 + surefire 用例数 + 每条断言都有实际断言（不是"没报错"）。

【范围声明】
- 做：①上传校验单测（magic number / 大小 / 时长 / 格式 各 1 条）；②状态机：失败一次 → `RETRYING` 且重新入队；重试耗尽 → `DEAD` + `dead_letter` 有原因（S-1）。
- 不动：业务实现代码（若发现只有改实现才能测 → **停下来问**，不要为了测试改逻辑）；不动配置/接口/compose。
- 禁：`@Disabled`、`-DskipTests`、把断言削成"只要不抛异常"。

【验收】（反例必须进）
```powershell
# ① 坏 exe 改名 .mp3 → 断言 400 INVALID_MEDIA（不是"抛异常就算过"）
# ② 超大小 → 413 SIZE_EXCEEDED；超时长 → 400 DURATION_EXCEEDED
# ③ 状态机：第一次抛 ASR_ERROR → 断言 status=RETRYING 且队列里重新出现该 task_id
# ④ 状态机：attempts 达到上限 → 断言 status=DEAD 且 dead_letter 有 reason、error_code 是稳定码
cd app; mvn -q -B test      # 期望 exit 0，surefire 用例数 ≥20
powershell -File tools\check-maintainability.ps1   # 期望 exit 0
```
**通过标准**：4 类断言各自真的断言了**值**；用例数 ≥20；`git log -p -- app/src/test` 无被删断言（自查后贴图/输出）。

【可维护性】M-8：新公共逻辑必须有测试；禁 `@Disabled`；`*SmokeTest` 之外不得 `assumeTrue`。脚本 exit 0。

【回报·测试三问】检查了哪些场景 ｜ 实际运行了哪些测试（命令 + 用例数 + exit code）｜ 还有哪些没验证（**不许留空**）。

---

## T1.9 `scripts/test.ps1` 测试入口（0.5h，只加脚本）✅ **已完成（`24abd45`；我复跑 26/26、`-UnitOnly` 24/24、反例 exit 1）**

> 为什么有这张卡：每次跑测试都要手工注入 `.env` + `ASR_BASE_URL=http://localhost:8001`，否则 `AsrSmokeTest` 红（环境坑 #13）。
> 这就是 `rules/dev.md` 说的"测试分层入口"缺位——**不是功能，是工程化**（D-054）。

【规划四问】
- **改哪里**：新增 `scripts/test.ps1`（仓库根新建 `scripts/`；顺手加 3 行 `scripts/README.md` 说明用法）。**不改任何 Java / 配置 / compose**。
- **复用什么**：已有 `.env`（本机存在、不入库）、`tools/check-maintainability.ps1` 的 PowerShell 写法、环境坑 #13。
- **影响哪些功能**：无运行时行为变化；只是把人肉步骤固化成一条命令。
- **怎么验证**：**不预设任何环境变量**的新 shell 里直接跑，必须 23/23；反例：故意用错 `ASR_BASE_URL` 必须红（证明脚本真注入了地址）。

【范围声明】
- 做：①读 `.env` 逐行注入进程环境（**不打印 value**，只打印 key 名）；②`ASR_BASE_URL` 默认 `http://localhost:8001`，但**外部已设置时以外部为准**；③定位 maven：`$env:MAVEN_HOME`/`$env:MAVEN_BIN` 优先，否则 `E:\tools\apache-maven-3.9.9\bin\mvn.cmd`，都没有就明确报错退出 2；④`-UnitOnly` 跳过 2 个冒烟用例；⑤打印 surefire 用例数；⑥exit code 透传。
- 不动：测试代码、业务代码、`application.yml`、`.env`、`docker-compose.yml`、`Dockerfile`。
- 硬要求：中文注释的 `.ps1` 必须存 **UTF-8 带 BOM**（PS 5.1 否则乱码报错）。

【验收】（反例必须进）
```powershell
# 新 shell，不设任何环境变量
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test.ps1
#   期望：23/23、exit 0、末尾打印"用例数 23"

powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test.ps1 -UnitOnly
#   期望：跳过 2 个 *SmokeTest（21/21）、exit 0

# 反例：把 ASR 指到没人听的端口 → 冒烟用例必须红（证明脚本真的注入了地址）
$env:ASR_BASE_URL='http://localhost:59999'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test.ps1
#   期望：exit ≠ 0；跑完清掉该变量
```
**通过标准**：①两种模式都 exit 0；②反例真的红；③脚本里不出现明文 secret 打印；④`check-maintainability.ps1` 仍 exit 0。

【可维护性】脚本不在 Java 行数限制内，但**不许把 maven 参数散落硬编码**：路径查找逻辑集中一处，注释写明"为什么是这个默认值"。

【回报·测试三问】检查了哪些场景 ｜ 实际运行的命令 + 用例数 + exit code ｜ 还有哪些没验证（**不许留空**）。

---

## T2.1 幂等：`content_hash` 唯一 + DEAD 重传重投（D-049，公共面已批）✅ **已完成（`300db82`；V2 迁移 + ON CONFLICT 幂等 + 复活重投，26/26）**

> **公共面声明（已批）**：Flyway V2 迁移 + 上传接口幂等语义属于公共面。**方向已由 D-049 拍板**，你按本卡执行即可；
> 若实现中发现需要改 `/api/v1` 字段或新增错误码 → **停下先问**（错误码固定 7 个，只增不改，D-047）。

【规划四问】
- **改哪里**：①`app/src/main/resources/db/migration/V2__task_content_hash_unique.sql`（**唯一约束**，例：`UNIQUE (user_id, content_hash)`，先查重）；②`TaskService.createUpload()` 幂等分支（捕获 `DuplicateKeyException` → 回查已有任务 → `reused:true`，**不要靠"先 SELECT 再 INSERT"**）；③命中且状态 `DEAD` → 重置 `attempts=0` / `status='PENDING'` / `error_code=NULL` → `leftPush` 重新投递（D-049）；④测试。
- **复用什么**：T1.4 的 `reused` 字段与 409 语义、T1.5/T1.8 的 worker 状态机、`TaskCreatedResponse`（字段不变）、D-040 的 `user_id` 过滤。
- **影响哪些功能**：上传幂等（同文件不重复转写、重传 DEAD 能复活）；**不新增接口字段**。
- **怎么验证**：连传两次 → 同 `task_id` + `reused:true` + 队列只有 1 条；DEAD 后重传 → 状态回 `PENDING` 且队列有该 id；**并发**两个同文件请求 → 只 1 个 task（唯一约束兜底）。

【范围声明】
- 做：V1→V2 迁移（**空卷 `docker compose down -v` 重建后 Flyway 一次过**）、TaskService 幂等 + DEAD 重投、单测 ≥3 条。
- 不动：worker 的失败分级逻辑（只允许 `attempts` 重置这一处语义）、列表页、详情页、其他 API。
- 禁：为"看起来幂等"加内存锁；禁改 V1（必须新增 V2）；禁把唯一约束只写在 JPA 注解里（Flyway 才是真相）。

【验收】（反例必须进）
```powershell
# ① 空卷重建：Flyway 从 V1+V2 一次建好
docker compose down -v; docker compose up -d --build; sleep 40; docker compose ps
docker exec -it meeting-notes-postgres-1 psql -U mn -d meetingnotes -c "\d task"   # 期望看到唯一约束

# ② 连传两次同文件
curl ... -F "file=@D:\datasets\meeting-notes-samples\sample.mp3" http://localhost:8080/api/v1/tasks   # 第 1 次 reused:false
curl ... 同上                                                                                        # 第 2 次 reused:true，task_id 相同

# ③ DEAD 重投：停 ASR → 传一个文件 → 等 DEAD → 启 ASR → 重传同文件
#    期望：status 从 DEAD 回 PENDING/RETRYING，最终 DONE；队列里出现该 task_id

# ④ 并发反例：两个 curl 同时打同一文件 → 只产生 1 个任务
cd app; mvn -B test   # 期望 23+3=26 用例起，exit 0
powershell -File tools\check-maintainability.ps1   # 期望 exit 0
```
**通过标准**：唯一约束在真实 PG 上生效；并发只建 1 个任务；DEAD 重投真能复活；单测覆盖"重复上传"和"DEAD 重投"两条；脚本 exit 0。

【可维护性】迁移文件只做加约束（不许改历史 V1）；`TaskService` 若超过 300 行要**先报告**（M 规则：拆分方案），别闷头拆。

【回报·测试三问】检查了哪些场景 ｜ 实际运行的命令 + 用例数 + exit code ｜ 还有哪些没验证（**不许留空**）。

---

## T2.10 服务端任务列表 API（D-053，公共面已批）✅ **已完成（`08e862d`；我复跑分页/过滤/参数校验全过 + 截图核对）**

> **公共面声明（已批）**：新增 `GET /api/v1/tasks` 属公共面，方向由 D-053 拍板。字段命名一律 **snake_case**（D-041）；
> `user_id` 过滤必须带（D-040）；错误码只增不改（D-047）。

【规划四问】
- **改哪里**：①`TaskController` 新增 `GET /api/v1/tasks`（参数 `status` / `limit` / `before_id`，响应 `{items:[...], next_before_id}`，每项含 `id/status/original_name/duration_sec/created_at/error_code`）；②`TaskQueryService`（或 TaskService 内新增只读方法）用 **keyset 分页**（`id < before_id ORDER BY id DESC LIMIT n`，**不要 OFFSET**）；③`static/app.js` 列表页改为请求该接口，localStorage 降级为"上次提交缓存"（失败时兜底展示）；④测试。
- **复用什么**：T1.5 `GET /tasks/{id}` 的 snake_case 序列化方式、D-040 的 user_id 过滤、T1.6 的 `style.css` 与列表页结构（**不改样式**）。
- **影响哪些功能**：列表页数据源（换浏览器/清缓存也能看到历史）；详情页不变。
- **怎么验证**：无痕窗口打开 `/tasks` 有历史；`status`/`limit`/`before_id` 过滤与翻页正确；`limit>50` → 400；`limit<=0`/非数字 → 400。

【范围声明】
- 做：新端点 + 列表页切换 + 单测（分页边界 / 过滤 / 参数校验）。
- 不动：上传接口、worker、详情页、`style.css`（要改先问）、`接入指南.md`（**生成副本，禁手改**；需要补什么在回报里写"建议 接入指南.md 增加 X"）。
- 禁：`SELECT *` 不带 limit；用 OFFSET 分页；把 `user_id` 从请求里让客户端传（服务端定）。

【验收】（反例必须进）
```powershell
# ① 无痕窗口（或清 localStorage 后）打开 http://localhost:8080/tasks → 列表有历史（截图）
# ② 过滤与分页
curl "http://localhost:8080/api/v1/tasks?limit=5"                      # 期望 items=5、next_before_id 有值
curl "http://localhost:8080/api/v1/tasks?status=DEAD"                  # 只回 DEAD
curl "http://localhost:8080/api/v1/tasks?before_id=<上一页最小 id>"     # 无重复、无遗漏
curl "http://localhost:8080/api/v1/tasks?limit=51"                     # 期望 400（码沿用既有校验类，不新增）
curl "http://localhost:8080/api/v1/tasks?limit=abc"                    # 期望 400
# ③ 反例：DB 里塞 3 条同状态任务 → 翻页 2 页正好覆盖 3 条，不重不漏
cd app; mvn -B test   # 期望 26+3 用例起，exit 0
powershell -File tools\check-maintainability.ps1   # 期望 exit 0
```
**通过标准**：无痕窗口能看到历史（截图）；分页不重不漏；参数非法 400；JSON 全 snake_case；单测覆盖边界；脚本 exit 0。

【可维护性】`TaskController` 保持薄（校验 + 委托），查询逻辑放 service；新端点必须进 `接入指南.md`（由我改）与单测（M-8）。

【回报·测试三问】检查了哪些场景 ｜ 实际运行的命令 + 用例数 + exit code ｜ 还有哪些没验证（**不许留空**）。

---

## T2.11 契约与死信收口（0.5h，T2.1 验收发现的两处缺陷）✅ **已完成（`08fbfd6`；我复跑 33/33、死信 1→0、MinIO 故障 500 STORAGE_ERROR；但引入回归 F-1 → T2.12）**

> 两处都是**我验收时实测出来的**（`review/2026-11-14-T1.9-T2.1验收.md` F-1/F-2），不是"我觉得应该"：
> ① 复活成功后 `dead_letter` 里还留着该任务 → 死信列表会出现"已 DONE 的任务"；
> ② 停 MinIO 上传 → HTTP 500 但响应体是 Spring 默认体（**没有 `code` 字段**），接入方无法按码分支。

【规划四问】
- **改哪里**：①`TaskService` 的 DEAD 复活分支加 `DELETE FROM dead_letter WHERE task_id = ?`（与复活同一个事务/同一个动作）；②`ApiExceptionHandler` 加 `@ExceptionHandler(Exception.class)` 兜底 → `500 INTERNAL_ERROR`，并在上传路径把 S3/MinIO 异常映射为 `500 STORAGE_ERROR`；③`scripts/test.ps1` 去掉 `D:\datasets\...` 本机路径兜底（未设置 `ASR_SMOKE_AUDIO` 就打印用法并 exit 3）；④补单测。
- **复用什么**：既有 7 个错误码（**不新增**，D-047）、`ApiException`/`ApiExceptionHandler`、`TaskService` 的复活分支、`TranscriptionWorker` 里 `STORAGE_ERROR` 的既有语义。
- **影响哪些功能**：DEAD 复活后的死信列表一致性；上传接口的错误响应体（**公共面**：错误 envelope 形状）。**不改响应字段、不新增错误码**。
- **怎么验证**：复活一个 DEAD 任务 → `dead_letter` 该任务行数 = 0；停 MinIO 上传 → 500 且 body 含 `"code":"STORAGE_ERROR"`；随便造一个未捕获异常 → 500 且 body 含 `"code":"INTERNAL_ERROR"`；单测 26 → ≥28。

【范围声明】
- 做：上述 ①②③④；`接入指南.md` **不要手改**（生成副本），需要补的契约行在回报里写"建议增加 X"。
- 不动：幂等逻辑、队列语义、worker 状态机、列表页、其他 API。
- 禁：新增错误码；把 `Exception` 兜底写成"吞掉异常返回 200"；把 500 文案暴露堆栈 / 内部类名 / SQL。

【验收】（反例必须进）
```powershell
# ① 死信清理：停 ASR → 传文件 → 等到 DEAD（dead_letter 有 1 行）→ 启 ASR → 重传该文件
#    期望：重传返回 reused:true + PENDING → 终态 DONE 后 dead_letter 里该任务 0 行
docker exec meeting-notes-postgres-1 psql -U mn -d meetingnotes -c "select count(*) from dead_letter where task_id=<id>;"

# ② 存储故障契约：停 minio → 上传 → 期望 HTTP 500 且 body 含 code:"STORAGE_ERROR"（不是 Spring 默认体）
docker compose stop minio
curl.exe -s --noproxy "*" -w "`nHTTP=%{http_code}" -F "file=@<样本>" http://localhost:8080/api/v1/tasks
docker compose start minio

# ③ 兜底契约：确认没有 200 假成功、没有堆栈外泄

cd app; powershell -NoProfile -ExecutionPolicy Bypass -File ..\scripts\test.ps1   # 期望 ≥28 用例 exit 0
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-maintainability.ps1   # 期望 exit 0
```
**通过标准**：三个"期望"全部实测；测试三问里的"未验证"不许留空。

【可维护性】错误映射集中一处（handler 或 service），别在每个 catch 里各写一遍；新增测试断言具体 `code`/`http` 值。

【回报·测试三问】检查了哪些场景 ｜ 实际运行的命令 + 响应体 + exit code ｜ 还有哪些没验证（**不许留空**）。

---

## T2.12 错误语义收口（0.5h，T2.11 引入的回归 + T2.10 参数码语义）✅ **已完成（`9bc93e4`；我复跑七连击全对、40/40）**

> **这是 T2.11 的回归**：加了 `@ExceptionHandler(Exception.class)` 之后，Spring 自己的异常也被吞了。实测（我验收时打的）：
> | 请求 | 应该 | 现在 |
> |---|---|---|
> | `GET /api/v1/nope` | 404 | **500** |
> | `GET /nope.html` | 404 | **500** |
> | `GET /api/v1/tasks/abc` | 400 | **500** |
> | `DELETE /api/v1/tasks` | 405 | **500** |
> | `POST /api/v1/tasks` 带 `application/json` | 415 | **500** |
> 另两条契约语义：列表参数错误借用了 `INVALID_MEDIA`（会让人以为文件坏了）；`status=BOGUS` 静默返回 200 空列表。
> **决策已下（D-057）**，按它执行。

【规划四问】
- **改哪里**：①`ApiExceptionHandler` 的兜底 handler：`e instanceof org.springframework.web.ErrorResponse` → 按 `getStatusCode()` 返回
  （404→`NOT_FOUND`、405→`METHOD_NOT_ALLOWED`、415→`UNSUPPORTED_MEDIA_TYPE`、400→`INVALID_PARAM`），**只有真未知异常才 500 `INTERNAL_ERROR`**；
  ②`TaskController` 列表参数校验改 `INVALID_PARAM`（不再用 `INVALID_MEDIA`）；③`status` 严格校验（只允许 `PENDING/RUNNING/RETRYING/DONE/DEAD`，其它 400 `INVALID_PARAM`）；④补测试。
- **复用什么**：`ErrorResponse`（Spring 6 框架异常共同接口，`NoResourceFoundException`/`HttpRequestMethodNotSupportedException`/`HttpMediaTypeNotSupportedException`/`MethodArgumentTypeMismatchException` 都实现它）、既有 `ErrorResponse` record。
- **影响哪些功能**：所有接口的错误响应码/码值（**公共面**，但 D-057 已批；不新增业务码，只加"参数类/框架类通用码"）。
- **怎么验证**：5 类框架异常各打一次，状态码与 `code` 都对；`status=BOGUS` 变 400；单测 33 → ≥36。

【范围声明】
- 做：上述 ①②③④。**`接入指南.md` 不要手改**（我改）；需要补的行在回报里写"建议增加 X"。
- 不动：业务成功路径、任务状态机、列表 SQL、页面、`scripts/test.ps1`。
- 禁：把框架 4xx 继续映射成 500；为省事把 `status` 非法值当空列表返回；透传异常 detail（防泄漏）。

【验收】（反例必须进）
```powershell
$env:HTTP_PROXY='';$env:HTTPS_PROXY='';$env:ALL_PROXY=''
# 每条的期望：状态码 + body.code
curl.exe -s --noproxy "*" -w "`nHTTP=%{http_code}`n" http://localhost:8080/api/v1/nope            # 404 + NOT_FOUND
curl.exe -s --noproxy "*" -w "`nHTTP=%{http_code}`n" http://localhost:8080/nope.html             # 404 + NOT_FOUND
curl.exe -s --noproxy "*" -w "`nHTTP=%{http_code}`n" http://localhost:8080/api/v1/tasks/abc      # 400 + INVALID_PARAM
curl.exe -s --noproxy "*" -X DELETE -w "`nHTTP=%{http_code}`n" http://localhost:8080/api/v1/tasks # 405 + METHOD_NOT_ALLOWED
curl.exe -s --noproxy "*" -X POST -H "Content-Type: application/json" -d '{}' -w "`nHTTP=%{http_code}`n" http://localhost:8080/api/v1/tasks   # 415 + UNSUPPORTED_MEDIA_TYPE
curl.exe -s --noproxy "*" -w "`nHTTP=%{http_code}`n" "http://localhost:8080/api/v1/tasks?limit=abc"    # 400 + INVALID_PARAM
curl.exe -s --noproxy "*" -w "`nHTTP=%{http_code}`n" "http://localhost:8080/api/v1/tasks?status=BOGUS" # 400 + INVALID_PARAM
# 回归检查（不许被这次改动弄坏）：
curl.exe -s --noproxy "*" -w "`nHTTP=%{http_code}`n" http://localhost:8080/api/v1/tasks/999999        # 仍 404 TASK_NOT_FOUND
curl.exe -s --noproxy "*" -w "`nHTTP=%{http_code}`n" "http://localhost:8080/api/v1/tasks?status=DONE"  # 仍 200
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test.ps1   # 期望 ≥36 用例 exit 0
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-maintainability.ps1   # exit 0
```
**通过标准**：7 条"期望码"全对 + 2 条回归不坏 + 单测 ≥36；测试三问的"未验证"不许留空。

【可维护性】状态码→通用码的映射**集中在一处**（一个 switch），别散落；新增测试断言 `status` + `code` 两个值。

【回报·测试三问】检查了哪些场景 ｜ 实际运行的命令 + 每条响应体原文 + exit code ｜ 还有哪些没验证（**不许留空**）。

---

## T2.3 崩溃恢复：启动回收 RUNNING 僵尸（US-2 场景②，约 1h）✅ **已完成（`11744ee`；我复跑 RUNNING 中重启 → 回收 1 个 → 12s DONE，40/40）**

> 为什么现在做：T2.1 的"幂等 + 复活"已经能救 DEAD，但**进程被杀在 RUNNING** 的任务没人管——`docker restart app` 后它会永远 RUNNING
> （卡片 spec US-2 场景②要求"处理中杀进程重启 → 恢复或明确可重试"）。这是"不丢任务"的最后一环。

【规划四问】
- **改哪里**：`TranscriptionWorker` 启动时（`@PostConstruct`/`ApplicationRunner`，在开始 BLPOP 之前）执行一次回收：
  ```sql
  UPDATE task SET status='PENDING', updated_at=now()
  WHERE status='RUNNING' RETURNING id
  ```
  然后把这些 id 逐个 `leftPush` 回队列；**单实例前提**下"启动时的 RUNNING 必然是僵尸"（多实例要改成按 `updated_at` 阈值，写进注释即可，不实现）。
  回收数量写一条 INFO 日志（0 也要写，证明跑过）。
- **复用什么**：`TaskService.enqueue` 的逻辑（worker 里已有 `rightPush`/`leftPush` 用法）、T1.8 的状态机语义、T2.1 的幂等。
- **影响哪些功能**：worker 启动流程（**不改 API/不改表结构**）；重启后任务不卡死。
- **怎么验证**：处理一个真实音频的中途 `docker restart app` → 重启后该任务最终到 `DONE`（或重试到 `DEAD`），**不允许停在 RUNNING**。

【范围声明】
- 做：启动回收 + 单测（`status='RUNNING'` → 回 PENDING 并入队；`DONE/DEAD/PENDING` 不受影响）。
- 不动：失败分级、幂等逻辑、队列键、`/api/v1`、表结构。
- 禁：启动时把所有任务无脑重置；用 `updated_at` 阈值当唯一判据又不说清单实例假设；回收时不看状态（会把 DONE 也重跑）。

【验收】（反例必须进）
```powershell
# ① 真机：传一个较大的音频（如 probe.wav），在 RUNNING 时重启
curl.exe -s --noproxy "*" -X POST -F "file=@D:\datasets\meeting-notes-samples\probe.wav" http://localhost:8080/api/v1/tasks
docker restart meeting-notes-app-1
# 重启后轮询：期望最终 DONE（最多等到 attempts 用尽 → DEAD，但绝不长时间 RUNNING）
# ② 日志里能看到"回收 N 个 RUNNING 僵尸"，且 app 启动后队列里有该 id
# ③ 回归：正常上传仍 DONE；DEAD 复古仍可复活（T2.1 的验收 ③ 不准坏）
cd app; powershell -NoProfile -ExecutionPolicy Bypass -File ..\scripts\test.ps1   # 期望 ≥38 用例 exit 0
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-maintainability.ps1   # exit 0
```
**通过标准**：重启后任务有终态、日志有回收记录、回归不坏；测试三问不许留空。

【可维护性】回收 SQL 带 `RETURNING id` 一次拿全，别 N+1；注释写清"单实例假设，多实例需改阈值"。

【回报·测试三问】检查了哪些场景 ｜ 实际运行的命令 + 日志摘录 + exit code ｜ 还有哪些没验证（**不许留空**）。

---

## T2.2 ASR 超时：失败要"快发现"（约 1h，D-058）✅ **已完成（`d9417e5`；我复跑 DEAD 7.7s、黑洞实验证明 5s connect 生效、56.7min 长音频 DONE 334.6s，42/42）**

> 现状：停 ASR 时，任务要 **78s** 才走到 RETRYING/DEAD（实测，根因是 DNS/连接阶段长超时）。
> 用户看到的是"任务卡在 RUNNING 一分多钟"；`app.asr.timeout-sec`（900s）至今**没有消费方**（D-054 登记的"计划中"键）。
> **D-058 已定方案**，按它执行；若要改方案（比如压短 900s）→ 停下问我。

【规划四问】
- **改哪里**：①`application.yml` 的 `app.asr` 下新增 `connect-timeout-sec: ${ASR_CONNECT_TIMEOUT_SEC:5}`；
  ②按 Spring AI 1.1.x 的扩展点给 ASR 的 HTTP 调用设**连接/首字节超时**（`connect-timeout-sec`）与**单次请求总超时**（`timeout-sec`）；
  ③`.env.example` 增加 `ASR_CONNECT_TIMEOUT_SEC=5` 行；④`配置清单.md` §2 的 `$appKeys` 快照 34 → **35**（这是 D-054 的机械要求，我会在验收时核）；
  ⑤顺手删 `TranscriptionWorker` 里拆分后残留的两个未使用 import（`DataAccessException`、`java.util.List`）；⑥补测试。
- **复用什么**：Spring Boot 3.5 的 HTTP 客户端定制能力（`RestClientCustomizer` / `ClientHttpRequestFactory`）；
  既有 `app.asr.timeout-sec` 配置项；`TranscriptionWorker` 的失败分类（`ASR_ERROR`）。
- **影响哪些功能**：ASR 调用的超时行为（**不改 API/表结构/错误码**）；新增 1 个配置键（D-058 已批）。
- **怎么验证**：停 ASR → 上传 → 记录**提交到首次 RETRYING/失败**与**到 DEAD** 的耗时；长音频仍能正常 DONE。

【范围声明】
- 做：上述 ①~⑥。
- 不动：重试次数（仍 3）、状态机、队列语义、`/api/v1`、幂等、列表页。
- 禁止：把 `app.asr.timeout-sec` 压到 10s 级别（长音频 60min 会被误杀）；只加健康预检而不设超时（DNS 卡住时预检一样卡）；
  引入延时队列做退避（D-058 明确这轮不做）。

【验收】（反例必须进；**要贴时间戳**）
```powershell
# ① 停 ASR → 上传 → 先后轮询，量两个数：首次失败（RETRYING）/ 最终 DEAD 各用时多久
docker compose stop asr
curl.exe -s --noproxy "*" -X POST -F "file=@D:\datasets\meeting-notes-samples\probe.wav" http://localhost:8080/api/v1/tasks
# 期望：首次失败 ≤ 15s；到 DEAD ≤ 45s（当前基线 78s，要明显变快；贴日志时间戳）
docker compose logs app --since 5m 2>&1 | Select-String -Pattern '任务 .* 开始转写|ASR_ERROR|置 DEAD|RETRYING'

# ② 回归：ASR 恢复后，长音频仍能 DONE（probe.wav 90s）
docker compose start asr; 上传同一文件 → 期望 reused 语义正确 + DONE
# ③ 配置体检：新增键必须有消费方，且进 .env.example 与快照
#    跑 配置清单.md §2 第 5 条 → 期望孤儿键仍是"计划中"那几个，不含 ASR_CONNECT_TIMEOUT_SEC 的 yml 键 app.asr.connect-timeout-sec
cd app; powershell -NoProfile -ExecutionPolicy Bypass -File ..\scripts\test.ps1   # 期望 ≥41 用例 exit 0
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-maintainability.ps1   # 期望 exit 0
```
**通过标准**：两个耗时数字明显下降（首次 ≤15s / DEAD ≤45s，贴证据）；长音频仍 DONE；新键有消费方且在 `.env.example`；
`$appKeys` 快照已更新为 35 并自检 no diff；单测 ≥41；脚本 exit 0。

【可维护性】超时配置集中在一处；不许把 5s/900s 写成魔法数；`TranscriptionWorker` 删掉未使用 import。

【回报·测试三问】检查了哪些场景 ｜ 实际命令 + **耗时数字 + 日志时间戳** + exit code ｜ 还有哪些没验证（**不许留空**）。

---

## T2.4 健康检查四项（约 1h，D-059 已定口径）✅ **已完成（`74c0ed8`；我复跑 MinIO/ASR/Redis 三反例 + readiness 组 + 47/47）**

> 现状：`/actuator/health` 只有 `db` / `redis` / `diskSpace`——**MinIO 和 ASR 挂了没人知道**（进程还是"健康"的）。
> 目标：四项（DB / Redis / MinIO / ASR）任一不可用 → 整体 `DOWN`，但**不能因为探活把应用拖死/拖慢**。

【规划四问】
- **改哪里**：①新增 `health/MinioHealthIndicator`（`S3Client.headBucket`，短超时 2s）；
  ②新增 `health/AsrHealthIndicator`（`GET {ASR_BASE_URL}/health`，短超时 2s，**不许用 900s 的 ASR 总超时**）；
  ③`application.yml` 配 `management.endpoint.health.group.readiness.include`（含 `readinessState,db,redis,minio,asr`），
  ④补测试；⑤README/接入指南里的健康检查说明由我更新（你只报告）。
- **复用什么**：既有 `S3Client` bean、`app.asr.connect-timeout-sec`（D-058）、`/actuator/health` 已有结构、Spring Boot `HealthIndicator` 接口。
- **影响哪些功能**：运维可见性 + docker healthcheck（**公共面**：`management.*` 配置；D-059 已批）。不改业务接口。
- **怎么验证**：分别停 MinIO / ASR / DB（可选）/ Redis（可选）→ `/actuator/health` 变 `DOWN` 且 `components.<name>.status=DOWN`；恢复 → `UP`。

【范围声明】
- 做：上述 ①~④。
- 不动：业务逻辑、worker、上传、错误码、`/api/v1` 响应。
- **禁**：探活里调慢接口（必须 ≤2s 超时）；让探活异常抛到 context（指标挂了应用也要能起）；
  在测试环境因为缺 MinIO/ASR 而让 `NoKeyStartupTest`（H2 空库、无外部依赖）红——**这条最容易踩**：
  测试里要么 mock 指标、要么给指标配 `@Profile("!test")` 之类开关，总之 **H2 环境 health 仍要能返回**。

【验收】（反例必须进）
```powershell
$env:HTTP_PROXY='';$env:HTTPS_PROXY='';$env:ALL_PROXY=''
# 基线：四项都 UP
curl.exe -s --noproxy "*" http://localhost:8080/actuator/health

# 反例 1：停 MinIO → 整体 DOWN 且 components.minio.status=DOWN（且响应在 3s 内返回，不能卡）
docker compose stop minio
curl.exe -s --noproxy "*" -w "`nHTTP=%{http_code} time=%{time_total}s`n" http://localhost:8080/actuator/health
docker compose start minio

# 反例 2：停 ASR → 整体 DOWN 且 components.asr.status=DOWN（同样要快）
docker compose stop asr
curl.exe -s --noproxy "*" -w "`nHTTP=%{http_code} time=%{time_total}s`n" http://localhost:8080/actuator/health
docker compose start asr

# 恢复后回到 UP；读写任务仍正常（上传一个小文件 → DONE）
cd app; powershell -NoProfile -ExecutionPolicy Bypass -File ..\scripts\test.ps1   # 期望 ≥44 用例 exit 0（含 H2 环境健康回归）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-maintainability.ps1   # exit 0
```
**通过标准**：两个反例都 DOWN 且 `time_total` ≤3s；恢复后 UP；H2 单测全绿；脚本 exit 0。

【可维护性】两个指标各一个类（别塞进 worker/service）；超时值走配置/常量集中；日志用 DEBUG 不刷屏。

【回报·测试三问】检查了哪些场景 ｜ 实际命令 + health JSON + `time_total` + exit code ｜ 还有哪些没验证（**不许留空**）。

---

## T2.7 结构化 JSON 日志 + taskId 贯穿（约 1h，消费 `app.log-json`，D-054）

> 现状：日志是普通文本，`taskId` 混在中文句子里（`任务 33 开始转写…`）；`app.log-json`（`LOG_JSON`）是**没有消费方的死配置**（D-054 登记）。
> 目标：给 self-hosted 用户一条命令就能捞出"一个任务的全链路"：`docker compose logs app | grep <taskId>` → ≥5 条**结构化**行。

【规划四问】
- **改哪里**：①`application.yml`：把 `app.log-json` 接到 Spring Boot 3.5 内建的**结构化日志**（`logging.structured.format.console: ecs` 或等价，
  **不引入新依赖、不写自定义 encoder**），`LOG_JSON=false` 时保持现在的可读文本；②`TranscriptionWorker` 在处理任务期间用 **MDC** 放 `taskId`
  （处理结束/异常都要 `MDC.clear()`，线程复用不能串号）；③`StartupRecovery` 回收日志也带结构化字段；④补测试。
- **复用什么**：Boot 3.5 内建 `logging.structured.format.console`（无需 logstash 依赖）、SLF4J MDC、现有 `LOG_LEVEL` 配置。
- **影响哪些功能**：日志格式（**运维可见性**）；不影响 API/业务。`LOG_JSON=false` 必须能回退成人类可读。
- **怎么验证**：跑一个真实任务 → `docker compose logs app | grep "<taskId>"` ≥5 条且每行可被 JSON 解析、含 `taskId`；`LOG_JSON=false` 重启后是文本。

【范围声明】
- 做：上述 ①~④；`.env.example` 无需加键（`LOG_JSON` 已有）；`配置清单.md` 的"计划中"表里 `app.log-json` 由我除名（你不改 docs）。
- 不动：业务逻辑、错误码、`/api/v1`、健康检查、compose。
- 禁：`System.out`、把整个日志消息塞进 JSON 的 `message` 就完事（taskId 必须是**独立字段**）；日志里打印 transcript 全文/密钥；
  为 JSON 日志引入 logstash/encoder 依赖。

【验收】（反例必须进）
```powershell
# ① 起一个真实任务（用有语音的样本，确保有 transcript）
curl.exe -s --noproxy "*" -X POST -F "file=@D:\datasets\meeting-notes-samples\probe.wav" http://localhost:8080/api/v1/tasks
# ② 拿到 task_id 后：≥5 条命中，且每行是合法 JSON 且有 taskId 字段
docker compose logs app | Select-String "<taskId>"
# ③ 解析验证（PowerShell）：逐行 ConvertFrom-Json 成功、字段含 taskId 与 level
# ④ 反例：LOG_JSON=false 重启 app → 日志回到可读文本（不是 JSON）
# ⑤ 回归：健康检查仍 UP；上传/列表/详情正常
cd app; powershell -NoProfile -ExecutionPolicy Bypass -File ..\scripts\test.ps1   # 期望 ≥49 用例 exit 0
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-maintainability.ps1   # exit 0
```
**通过标准**：≥5 条结构化行 + 每行合法 JSON 含 `taskId`；`LOG_JSON=false` 可回退；单测 ≥49；脚本 exit 0。

【可维护性】MDC 的 put/clear 用 try/finally；日志字段名统一 camelCase（`taskId`）；不新增第三方依赖。

【回报·测试三问】检查了哪些场景 ｜ 实际命令 + 日志样例（脱敏）+ exit code ｜ 还有哪些没验证（**不许留空**）。

---

## T2.8 备份与恢复（US-11，约 2h，**数据安全是 P1 用户 B 的买点**）

> 现状：一个 `docker compose down -v` 就把用户所有任务和结果删光（**没有备份=不是"能给别人的自托管产品"**）。
> 目标：`scripts\backup.ps1` 一条命令产出可核对的备份；`scripts\restore.ps1 -Force` 能从备份恢复到"数据一致"。

【规划四问】
- **改哪里**：①`scripts/backup.ps1`：`pg_dump -Fc`（DB 全量）+ **MinIO 音频对象**（用 `docker run --rm --volumes-from` tar 打包 volume 或等价 `mc mirror`），
  产物放 `backups/<时间戳>/`（**不进 git**，`.gitignore` 加 `backups/`），支持 `-Keep 7` 保留最近 N 份；②`scripts/restore.ps1 -Force`：
  校验备份完整性 → 停 app worker（避免边恢复边消费）→ `pg_restore --clean` → 恢复 MinIO volume → 起服务 → 自检；
  ③`backup.ps1` 打印"备份了什么/多大/校验和"，`restore.ps1` 打印同样信息并核对行数；④补测试（脚本级：备份→删卷→恢复→数据一致）。
- **复用什么**：`docker exec meeting-notes-postgres-1 pg_dump`、compose 的 volume 名、`scripts/test.ps1` 的 PowerShell 风格（UTF-8 BOM）。
- **影响哪些功能**：运维/数据安全（**不动 API/表结构/业务代码**）。
- **怎么验证**：造 2 个有 transcript 的任务 → 备份 → `docker compose down -v`（删卷）→ `up -d` → `restore.ps1 -Force` → **任务数、task_id、transcript 全文、列表 API 结果一致**。

【范围声明】
- 做：上述 ①~④ + `README` 由我写用法（你只报告）。
- 不动：业务代码、Flyway、`/api/v1`；**备份包不含 `.env`**（D-023，密钥不进备份）。
- 禁：`restore` 不加 `-Force` 就执行（防误删生产数据）；恢复时不校验备份文件存在/非空；把备份放在会被 `down -v` 删掉的 volume 里。

【验收】（反例必须进；**必须贴前后数据对比**）
```powershell
# ① 造数据：传 2 个真实音频 → 等 DONE（记录 task_id + transcript 长度）
# ② 备份
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup.ps1
#    期望：打印 DB 行数/对象数/大小/校验和，产物在 backups\<ts>\

# ③ 反例：不带 -Force 恢复 → 拒绝执行（exit != 0）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore.ps1 -BackupDir backups\<ts>

# ④ 灾难：删卷重建
docker compose down -v; docker compose up -d --build; sleep 40
#    期望：此时 API 里任务数 = 0（证明真的删光了）

# ⑤ 恢复
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore.ps1 -BackupDir backups\<ts> -Force
#    期望：任务数/task_id/transcript 全文与①一致；列表 API 返回一致；能继续上传新任务

# ⑥ 保留策略：-Keep 2 → 第三次备份后只留 2 份
cd app; powershell -NoProfile -ExecutionPolicy Bypass -File ..\scripts\test.ps1   # 期望 ≥49 用例 exit 0
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-maintainability.ps1   # exit 0
```
**通过标准**：③拒绝、⑤数据一致（贴对比）、⑥保留策略生效、备份包不含 `.env`、单测 ≥49、脚本 exit 0。

【可维护性】脚本参数集中、错误信息可操作；不硬编码绝对路径（用 `$PSScriptRoot`）；中文 `.ps1` 存 UTF-8 **带 BOM**。

【回报·测试三问】检查了哪些场景 ｜ 实际命令 + 前后数据对比 + exit code ｜ 还有哪些没验证（**不许留空**）。

---

## 每张卡做完都要给你的回报

```text
任务：<ID>
改了什么：<文件>
验证命令：<原样>
实际输出：<关键输出>
exit code：<0/非0>
剩余风险：<一句>
```

**注意**：`docs/项目/录音纪要平台/` 是生成副本；要改状态/决策请同时告知人类，由人类更新权威版。
