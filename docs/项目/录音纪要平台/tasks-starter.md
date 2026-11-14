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

## T2.7 结构化 JSON 日志 + taskId 贯穿（约 1h，消费 `app.log-json`，D-054）✅ **已完成（`ae982d8`；我复跑 50/50、task 45/46 各 5 条共 10 行 JSON 全解析、`LOG_JSON=false` 反例 0 行 JSON；D-062）**

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

## T2.8 备份与恢复（US-11，约 2h，**数据安全是 P1 用户 B 的买点**）✅ **已完成（`6ebcdef`；我复跑 10 任务备份→`down -v`→恢复逐字一致、恢复后对象可读、无 `-Force` exit 10、`-Keep 2` 生效；D-063/D-064）**

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

## T2.9 本周单测/验收 + 三处收口（约 1.5h；**不加新功能**）✅ **已完成（`c9ad68c`；我实测 429+Retry-After、preflight 两分支、回归四连；3 处遗留转 T3.0）**

> 现状：本周功能卡（T2.1–T2.5、T2.10–T2.12、T2.2/T2.3/T2.4、T2.7/T2.8）全部已完成，`scripts\test.ps1` 一直是绿的（**50/50**）。
> 所以 T2.9 不是"补测试"，而是**把三件悬而未决的事收掉 + 出一份整周回归证据**：
> ① 配额 429 + `Retry-After` 只在单测里验过，**没走过真 HTTP**（D-061 明确并入本卡）；
> ② 我的验收发现 F-1（测试控制台被 JSON 日志搞乱，一行可修）；③ F-4（restore 缺"依赖未启动"preflight，退出码误导）。

【规划四问】
- **改哪里**：
  ① **429 真HTTP 复验（不改代码）**：临时把 `.env` 的 `QUOTA_TASKS_PER_DAY` 调成 2 → `docker compose up -d app` 重启（**不要改 compose 文件**）→
     连续上传到第 3 次，期望 **HTTP 429 + `Retry-After` 响应头 + 业务码 `QUOTA_EXCEEDED`** → 验完**恢复 `.env` 原值并重启**，贴 quota 表现状；
  ② **F-1 一行修**：`app/src/test/resources/application.yml` 加 `app.log-json: false`（测试里日志保持可读文本；产品默认仍是 JSON，D-062 不变）；
  ③ **F-4 restore preflight**：`scripts\restore.ps1` 开头检查 `postgres`/`minio` 容器是否 **running**，否则打印可操作提示并 **exit 20**
     （不要让它掉进 `pg_restore` 分支误报 exit 12；也别自动 `up -d`，D-064）。
- **复用什么**：`ApiExceptionHandler` 的 `QUOTA_EXCEEDED`（T1.4 已实现）；`scripts/restore.ps1` 现有退出码约定（10/11/12/13/14）。
- **影响哪些功能**：只动**测试配置 + 恢复脚本**；API/业务/健康检查/表结构/compose 一律不动。
- **怎么验证**：见下方【验收】，每条都要 exit code 或 HTTP 状态码。

【范围声明】
- 做：① ② ③ + 整周回归证据。
- 不动：业务代码、`/api/v1`、错误码、健康检查、compose、Flyway、`docs/`（README 备份/日志用法我写）。
- 禁：为凑测试数写无断言测试；把 429 的特例逻辑塞进业务层（配额判定已经在 `QuotaService`，只验不改）；restore 里自动起依赖。

【验收】（反例必须进）
```powershell
# ① 429 真 HTTP（临时改 .env 后重启 app，验完必须改回来再重启！）
#    $env 里改 QUOTA_TASKS_PER_DAY=2 → docker compose up -d app
curl.exe -s -o NUL -w "HTTP=%{http_code}\n" --noproxy "*" -X POST -F "file=@D:\datasets\meeting-notes-samples\fake_evidence.mp3" http://localhost:8080/api/v1/tasks
#    期望：第 3 次 HTTP=429；再用 -i 看响应头 Retry-After 与 body 里的 error_code
curl.exe -s -i --noproxy "*" -X POST -F "file=@..." http://localhost:8080/api/v1/tasks | Select-String "HTTP/|Retry-After|QUOTA"
#    ⚠️ 验完：.env 恢复原值 → docker compose up -d app → 再传 1 个应恢复 201
# ② F-1：改完 test yml 后跑测试，控制台不应再出现 {"@timestamp" 行
cd app; powershell -NoProfile -ExecutionPolicy Bypass -File ..\scripts\test.ps1
# ③ F-4 反例：整栈停掉 → restore 必须 exit 20 且提示"先 up -d postgres minio"
docker compose stop
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore.ps1 -BackupDir backups\<最新> -Force; echo "EXPECTED_EXIT=20"
# ③ 正例：docker compose up -d（等 healthy）→ 同一命令恢复成功（或至少 preflight 过、进入恢复流程）
# ④ 整周回归：健康 UP、上传→DONE、列表/详情、错误语义四连（/api/v1/nope=404、DELETE=405、JSON POST=415、/tasks/abc=400）
#    单测 ≥50 exit 0；check-maintainability exit 0；备份仍可用（backup.ps1 一次，校验 manifest）
# ⑤ 证据：本周每个卡一行"证据在哪"总表（贴到回报里，我核）
```
**通过标准**：429 + `Retry-After` 真 HTTP 命中且 `.env` 已复原；测试控制台无 JSON 行；`restore` 停机时 exit 20、恢复后成功；整周回归全绿；**不许靠改断言凑绿**。

【可维护性】restore preflight 用与现有脚本一致的风格（`Write-Host` 提示 + exit 码表更新到文件头注释）；`backup.ps1`/`restore.ps1` 仍 ≤300 行。

【回报·测试三问】检查了哪些场景 ｜ 实际命令 + HTTP 码/退出码/日志（脱敏）｜ 还有哪些没验证（**不许留空**）。

---

## T3.0 W3 前置小修（约 0.5h，**W2 关账条件**，D-066 / D-067）✅ **已完成（`0072fdd`；我复跑 干净 shell 57/57、ASR_PORT=9999 必红、两边 CST、Retry-After 33491=距本地零点 33491s）**

> 现状：T2.9 验收发现三处"没真正收掉"的问题，而 W3 每张卡都要跑测试/发请求，必须先修：
> ①`scripts/test.ps1` 把 `.env` 里**容器内地址** `ASR_BASE_URL=http://asr:8000` 注进测试进程 → **干净 shell 跑全量必红**（`UnknownHostException: asr`）；
> ②`surefire argLine` 的 `-Dfile.encoding=UTF-8` 只给 fork 的 JVM，maven 父进程仍按系统编码转发 → **中文日志仍乱码**；
> ③配额日切与 `Retry-After` 都按**容器 UTC**，中国用户实际早上 8 点才重置（D-067）。

【规划四问】
- **改哪里**：①`scripts/test.ps1`：注入 `.env` 时**跳过 `ASR_BASE_URL`**（容器地址不进测试进程），且 shell 显式设的值不被 `.env` 覆盖；
  另加 `$env:MAVEN_OPTS="-Dfile.encoding=UTF-8"`（父进程也要）；②`docker-compose.yml`：`app` 与 `postgres` 各加 `TZ: ${TZ:-Asia/Shanghai}`
  （公共面，Lead 已报备，**人类点头后再动**；未批就先只做 ①）；③`.env.example` 增 `TZ=Asia/Shanghai`（`配置清单.md` 我写）。
- **复用什么**：现有 `test.ps1` 结构；D-066/D-067 的口径；compose 现有 `${VAR:-default}` 写法。
- **影响哪些功能**：测试可复现性 + 控制台可读性 + 配额日切时区；**不动业务代码、API、表结构、测试断言**。
- **怎么验证**：见下（含反例）。

【范围声明】
- 做：①②③ + 必要注释；`scripts/*.ps1` 保持 **UTF-8 带 BOM**。
- 不动：业务代码、`application.yml`、Flyway、`/api/v1`、`docs/`（配置清单我改）。
- 禁：用 `-UnitOnly` 绕过冒烟；把 `.env` 的 `ASR_BASE_URL` 改成 `localhost`（容器内还要 `asr:8000`）；只设一处编码就宣称修好。

【验收】（反例必须进）
```powershell
# ① 干净 shell：不设任何 ASR 变量（新开一个 PowerShell/清空 env）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test.ps1     # 期望 50/50、exit 0
# ② 反例：故意指错端口 → 冒烟仍必须红（证明不是假绿灯）
$env:ASR_PORT='9999'; powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test.ps1; echo "EXPECTED_NONZERO=$LASTEXITCODE"
# ③ 中文可读：日志里应有 "worker 已启动" / "转写结果已落库"，不得出现 ����
# ④ 时区（人类批准 TZ 后）：两边都必须 CST
docker compose exec -T app date; docker compose exec -T postgres date
#    再触发一次 429：Retry-After 必须 < 86400 且 ≈ 距"本地零点"的秒数（贴计算过程）
# ⑤ 回归：上传→DONE、health UP、check-maintainability exit 0
```
**通过标准**：干净 shell 全量 **50/50 exit 0**；`ASR_PORT=9999` 必红；中文行可读；TZ 两边一致且 `Retry-After` 语义对；回归全过。

【可维护性】改动集中、每个行为有注释说明"为什么"（为什么跳过 `.env` 的地址 / 为什么父进程也要编码）；脚本 ≤300 行。

【回报·测试三问】检查了哪些场景 ｜ 实际命令 + 输出（含反例的退出码）+ exit code ｜ 还有哪些没验证（**不许留空**）。

---

## T3.1 摘要生成：云 `ChatModel` → `summary` / `keywords` / `ai_tier`（约 2.5h）✅ **已完成（`ffbc95c`；我复跑 3 样本 CLOUD 160~170 字、keywords 8 个、无编造；F-1 DONE 顺序 → T3.2 收口）**

> 现状：只有转写全文；`task_result` 的 `summary/keywords/ai_tier` **全空**，代码里**没有业务级 `ChatModel` 调用**
> （`LlmModelSwitchEnvironmentPostProcessor` 只决定 bean 开不开）。本卡做 W3 第一块：把"文字"变成"纪要"。

【规划四问】
- **改哪里**：
  ① 新增 `ai/` 包：`SummaryService`（+ 内部 `Pending`/结果记录），入参 `taskId + transcript`；调用已注入的 `ChatModel`，
     提示词要求**只输出 JSON**：`{"summary": "...", "keywords": ["..."]}`，并明确"**不得编造 transcript 里没有的人名/数字/日期/结论**"；
     用 Jackson 解析（解析失败 → 记 WARN，不重试不阻塞）；写 `task_result.summary` / `keywords`(JSONB) / `ai_tier='CLOUD'`；
  ② `TranscriptionWorker.persistResult` 落库后调用（`try/catch` 包住：AI 失败只 WARN，**任务仍 DONE，summary 留空**）——
     **只许 +2~5 行**（文件已 290 行，超 300 就先把摘要调用抽成独立协作类）；
  ③ `TaskStatusResponse` + `GET /api/v1/tasks/{id}` **只增** `summary`、`keywords`、`ai_tier` 三个 snake_case 字段（无则 `null`）；
  ④ 单测 **≥3**：提示词构造（含"禁止编造"约束）/ JSON 解析成功 / 解析失败与异常时降级不抛。
  ⑤ **LLM 必须有自己的短超时（本卡硬要求，不满足就别同步做）**：现在 `config/AsrHttpClientConfig` 的
     `RestClientCustomizer` 给**所有** RestClient 设了 connect 5s / read **900s**（D-058，为长音频转写设的），
     LLM 同步调用会吃到 900s → **单线程 worker 被拖住 15 分钟，整个队列排队**。做法：在同一个配置里改成**按 URI 路由超时**
     （`/v1/audio/transcriptions` → 900s；其它 chat → 新配置 `app.llm.timeout-sec`，`LLM_TIMEOUT_SEC` 默认 **120s**），connect 都 5s；
     `application.yml` + `.env.example` 加键（公共面 Lead 已批准）；单测断言"两条路径各用各的超时"（package-private 暴露以便断言）。
     LLM 超时/异常 → **WARN + summary 留空 + 任务照常 DONE**；WARN 里带**模型原始输出截断 200 字**（不进响应体，便于排查格式漂移）。
- **复用什么**：Spring AI 的 `ChatModel`（无 key 时 bean 不存在 → **跳过摘要**，不要 new 一个）；`ObjectMapper`；现有 `task_result` 列；
  `ai_tier` 列与 D-020 三档定义（本卡只落 `CLOUD`）；现有 `config/AsrHttpClientConfig` 与 `AsrHttpClientConfigTest`（加断言即可）。
- **影响哪些功能**：任务 DONE 后多一次 AI 调用（秒级）；详情 API 增字段；**不影响转写/状态机/幂等**。
- **怎么验证**：3 个**有语音**且内容不同（不同 content_hash）的样本 → DONE → 摘要非空、可逐句在 transcript 找到依据。

【范围声明】
- 做：①~⑤；`application.yml` + `.env.example` 加 `LLM_TIMEOUT_SEC`（默认 120；公共面 Lead 已批准）；API 增字段（只增不改，已报备）。
- 不做：三档降级（T3.3）、todos（T3.2）、模板（T3.9）、术语回流（T3.5/T3.6）、批量/导出（T3.7）；**不改表结构**（列已存在）。
- 禁：把 AI 输出直接当 transcript；LLM 失败把任务置 DEAD（转写成果不能因摘要丢失）；**给 LLM 复用 900s 读超时**（会拖死单线程 worker）；
  把提示词里的"输出 JSON"省略（必须可判）；在日志里打印 transcript 全文（隐私）；日志里打印完整模型输出（只截 200 字）。

【验收】（反例必须进）
```powershell
# ① 造 3 个不同 hash 的「有语音」样本：probe.wav 前后各拼 0.5s 静音（ffmpeg），保证内容唯一且有人声
#    逐个上传 → 等 DONE，记录 3 个 task_id
# ② 查详情：summary 非空、keywords 是数组、ai_tier=CLOUD
curl.exe -s --noproxy "*" http://localhost:8080/api/v1/tasks/<id>
docker compose exec -T postgres psql -U mn -d meetingnotes -t -A -c "select task_id, ai_tier, length(summary) from task_result order by task_id desc limit 3"
# ③ 人工比对（必做）：摘要里每个专名/数字/结论都能在 transcript 原文找到；贴 3 份对照（脱敏）
# ④ 反例：临时清空 LLM_API_KEY + 重启 app（无 ChatModel）→ 上传仍 DONE、summary 为 null、无 500；恢复 key 重启
# ⑤ 超时路由单测（硬要求）：断言 /v1/audio/transcriptions 用 900s、chat 用 120s（默认与显式覆盖各一条）
# ⑥ `scripts\test.ps1` ≥55 用例 exit 0；check-maintainability exit 0；`TranscriptionWorker` ≤300 行
# ⑦ 回归：上传/列表/详情/错误四连（404/405/415/400）不变
```
**通过标准**：3 个样本摘要非空且**无编造**；`ai_tier=CLOUD`；无 key 时优雅跳过（DONE + null）；
**LLM 超时路由单测通过**（chat 120s / ASR 900s，互不串）；单测 ≥55；脚本 exit 0。

【可维护性】`ai/` 包与 `worker`/`task` 单向依赖（`worker → ai`，不许反向）；提示词集中在一处（常量或资源文件），**不散落在业务代码**；
新公共逻辑必须有测试；对外字段 snake_case。

【回报·测试三问】检查了哪些场景 ｜ 实际命令 + 输出（3 份摘要对照 + exit code）｜ 还有哪些没验证（**不许留空**）。

---

## T3.2 待办抽取（`todos` JSON）+ 两处前置收口（约 2h）✅ **已完成（`cd35a6b`；我复跑 干净 shell 68/68、F-2 两层变异各造一次红、task 85 首次 DONE 即 summary/todos 就绪、task 86 无 key = DONE+全 null、22 条待办 0 编造；遗留 F-5~F-10 已并入 T3.3）**

> 现状：`task_result.todos`（JSONB）还是空的；摘要已能落库（T3.1）。本卡先把 T3.1 验收发现的 2 个问题收掉（30 min），再做"待办"。
> **前置收口（先做，30 min）**：
> - **F-1（必修，P1）**：现在"转写落库并置 DONE"早于"摘要"——实测 3.3s 窗口里外部能读到 `DONE + summary:null`（D-069 明令禁止）。
>   改：worker 里先写 transcript → 调摘要（try/catch）→ **最后**置 DONE（摘要失败也 DONE）。验收判据：日志时间线**"摘要已落库"必须早于"任务 DONE"**；
>   并加一条单测覆盖该顺序（用现有 `TranscriptionWorkerTest` 的 mock 结构即可）。
> - **F-2（测试洞）**：`AsrHttpClientConfigTest` 的路由方向没真断言（两条 `assertEquals(x,x)` 是自比较，映射写反仍全绿）。
>   改成用 `assertSame`/包私有 getter 断言"转写 URI → 900s 工厂、chat URI → 120s 工厂"两个方向。

【规划四问】
- **改哪里**：
  ① 新增待办抽取（建议放 `ai/TodoService` 或复用 `SummaryService` 的第二段逻辑）：提示词要求**只输出 JSON**
     `{"todos":[{"action":"...","owner":"...","deadline":"..."}]}`；**`owner`/`deadline` 未知就 `null`，不许猜**；
     解析失败 → WARN（原始输出截 200 字）+ 不重试 + 留空；写 `task_result.todos`(JSONB)；
  ② `GET /api/v1/tasks/{id}` **只增** `todos` 字段（数组，元素 snake_case：`action/owner/deadline`）；
  ③ 单测 **+4**：提示词约束（JSON + 不许猜）、合法解析落库、缺字段容错（只给 action 也行）、解析失败降级不抛；
  ④ 回归：摘要、详情、错误四连不变。
- **复用什么**：T3.1 的 `SummaryService` 结构、LLM 超时路由（120s）、失败不阻塞 DONE 的策略；`ObjectMapper`；`task_result.todos` 列已存在（**不改表**）。
- **影响哪些功能**：DONE 前多一次 LLM 调用（秒级）；详情 API 增字段；不动转写/状态机/幂等。
- **怎么验证**：3 个有语音样本 → DONE → `todos` 非空且每条 action 能在 transcript 找到依据（owner/deadline 没有就必须是 null）。

【范围声明】
- 做：前置 F-1、F-2 + 主任务 ①~④；API 只增字段（已报备）；`.env.example` 无需加键。
- 不做：三档降级（T3.3）、抽取式兜底（T3.4）、模板（T3.9）、待办勾选（T3.5）；不改表结构；不动 `docs/`。
- 禁：编造 owner/deadline（没有就 null，宁缺毋滥）；把待办塞进 summary 字段里；解析失败重试；先 DONE 后补结果（D-069）。

【验收】（反例必须进）
```powershell
# ① 前置 F-1：日志时间线 —— "摘要已落库" 必须早于 DONE（贴taskId那条链的3行）
docker compose logs app --since 10m | Select-String '"taskId":"<id>"'   # 看 order
# ② 前置 F-2：路由测试改成方向断言后仍全绿；故意把映射写反 → 测试必须红（贴反例）
# ③ 3 个样本：DONE 后查 todos
docker compose exec -T postgres psql -U mn -d meetingnotes -t -A -c "select task_id, todos, ai_tier from task_result where todos is not null order by task_id desc limit 3"
curl.exe -s --noproxy "*" http://localhost:8080/api/v1/tasks/<id>   # todos 是数组、元素含 action/owner/deadline
# ④ 人工比对：每条 action 能在 transcript 找到依据；没有 owner/deadline 的必须是 null（贴 3 份对照，脱敏）
# ⑤ 反例：临时清空 LLM key（或断网模拟）→ 仍 DONE、todos null、无 500（验完恢复）
# ⑥ 全量 `scripts\test.ps1` ≥61 用例 exit 0；check-maintainability exit 0；worker ≤300 行
# ⑦ 回归：上传/列表/详情/错误四连（404/405/415/400）不变
```
**通过标准**：前置两项都过（DONE 顺序 + 路由方向断言）；3 个样本 todos 非空、无编造；无 key 时优雅降级；单测 ≥61；脚本 exit 0。

【可维护性】提示词集中一处；`ai/` 单向依赖（worker → ai）；对外字段 snake_case；新增公共逻辑必须带测试。

【回报·测试三问】检查了哪些场景 ｜ 实际命令 + 输出（含 F-1 顺序证据、F-2 反例、3 份待办对照）+ exit code ｜ 还有哪些没验证（**不许留空**）。

---

## T3.3 三档降级（CLOUD / LOCAL / EXTRACTIVE）+ `ai_tier` 落库 + T3.2 遗留收口（约 3h）✅ **已完成（`991f429`；我复跑 97/97 exit 0、维护性 exit 0、双变异各造一次红、三档实机 95/96/97/98/100/101、错误四连与详情 11 键不变；F-5/F-6/F-7/F-8 收口；遗留 F-11 LOCAL 档未产出 ai_tier=LOCAL → T3.4/T3.8）**

> **现状**：只有第一档。`OLLAMA_ENABLED` / `OLLAMA_BASE_URL` / `OLLAMA_MODEL` / `OFFLINE_MODE` 四个键**目前是死配置**
> （yml 里注释写了"二、三档在 AiConfig 里按开关决定"，但代码没有）；没 key 时 summary/todos **全 null**（任务 DONE 但没内容）。
> **本卡目标**：不填任何 key 也能产出摘要/待办（N15「零成本可跑」），并把"用了哪一档"落库、对外可见。
> **验收报告**：`docs/协作/review/2026-11-14-T3.2验收.md`（F-5~F-10 的原始描述都在里面）。

> **前置收口（先做，约 40min；都是小改，别拖成主任务）**：
> - **F-8（P2，用户已批准，只改值不改键）**：`application.yml` 的 `max-tokens: ${LLM_MAX_TOKENS:1024}` → **`8192`**；
>   `.env.example` 已是 8192，改完三处一致。**原因**：deepseek-flash 是推理模型，1024 全被 thinking 吃光 → 空 content（D-071）。
> - **F-5（P3）**：`TodoService` 提示词"最多抽取 5 条"**模型不遵守**（实测 21 条）→ 改为"最多 20 条"，
>   并在 `parseTodos` 末尾**代码层截断 20 条**（防 JSON 过长被 `finish_reason=length` 截断）。commit message 里不要再写"max 5"。
> - **F-6（P3，测试要能造红）**：`TodoServiceTest` 里"不重试"的断言 `verify(chatModel, never()).call(contains("重试"))`
>   **不可证伪**（真重试同一 prompt 也照样绿）→ 改 `verify(chatModel, times(1)).call(anyString())`。
>
> 主任务（约 2h）：
> ① 新增 `ai/AiTier`（enum：`CLOUD`/`LOCAL`/`EXTRACTIVE`）+ `ai/AiStructuringRouter`（或 `AiConfig`）。
>    **语义是"级联降级"，不是"静态选一档"（本卡定死）**：先按可用性定首选档；该档调用异常/空 content/解析失败 →
>    **依次降一档再试**（CLOUD→LOCAL→EXTRACTIVE）；`ai_tier` 落**实际产出该结果的档位**；
>    EXTRACTIVE 是零依赖兜底，所以只要 transcript 非空就**必定有产出**。
>    **判定顺序按 plan §5.1**：① 有 `LLM_API_KEY`+`LLM_BASE_URL`+`LLM_MODEL` 且 `OFFLINE_MODE=false` → CLOUD；
>    ② `OLLAMA_ENABLED=true` 且 `OLLAMA_BASE_URL` **探活成功（≤2s，别复用 120s）** 且 `OLLAMA_MODEL` 非空 → LOCAL；
>    ③ 其它 → EXTRACTIVE。**启动期绝不因探活失败而起不来**（K-04 铁律：尽力而为 + WARN）。
>    ④ **`OFFLINE_MODE` 是云档硬门禁（D-077，验收方 2026-11-14 裁决）**：`OFFLINE_MODE=true` 时**即使三件套齐也不判 CLOUD**
>    （spec §134 场景②：离线模式配了云 LLM → 不发起外连）；同时**必须 WARN 一次**（reason=`offline_mode`，写清"云配置被离线模式屏蔽"），
>    否则用户只看到"有 key 却是 EXTRACTIVE"无法自查。`.env.example` 默认值保持 true 不动；验收 ② 的完整前提 = 恢复 key **且** `OFFLINE_MODE=false`。
> ② LOCAL 档：用独立 `ChatModel` 实例（`base-url=${OLLAMA_BASE_URL}`、`model=${OLLAMA_MODEL}`、超时沿用 120s 路由），
>    **不要覆盖/污染 CLOUD 的 bean**；两档可同时配置时 CLOUD 优先。
> ③ EXTRACTIVE 档**最小实现**（T3.4 再提质，本卡只要"能产出且不是乱码"）：按 `。！？；\n` 分句 →
>    关键句打分（词频 + 位置 + 长度惩罚）取前 5 句作 `summary`；`keywords` 取高频词（**先不引入分词库**，
>    用字符 n-gram + 停用词表 + 标点切分，避免 T3.4 的选型提前定死）；`todos` 用规则版（命中"明天/截止/负责/需要/要"的句子 → `action`，`owner/deadline` 能抽就抽、抽不到 null）。
> ④ **三档输出同构**：统一一个 DTO（`summary` / `keywords` / `todos` / `ai_tier`），上层不写三套分支；
>    `ai_tier` 必须落 `task_result.ai_tier`（CLOUD/LOCAL/EXTRACTIVE）。**空 transcript（D-048 无语音）→ 跳过结构化，`ai_tier` 也留 null**（没有可结构化内容，别假装有档位）。
> ⑤ 顺序不变，仍守 F-1/D-069：transcript → 结构化（摘要+待办）→ **最后** DONE。
>    **F-7（本轮必做，不再可选）**：把"摘要 + 待办"两次 LLM 调用**合并成一次**（同构 JSON 一次出 summary/keywords/todos）。
>    理由：① DONE 前最坏等待 2×120s → **120s**；② transcript 只交一次，token 减半（推理模型的思考量随 prompt 涨）；
>    ③ **降级语义一致**——不会出现"摘要成功、待办失败"的混合态（现在两次调用互相独立，可以一成一败）。
>    注意（写码方自己提的风险，采纳）：合并后输出 JSON **更大**，必须和 F-5 的**代码层截断 20 条**联动，否则
>    `finish_reason=length` 会把整个结构化输出截崩；同时保留"解析失败 → WARN（截 200 字）+ 不重试 + 三字段一起留空"的旧语义。
> ⑥ **诊断信息（做不动可跳过，跳过要在报告写一句）**：改用 `model.call(new Prompt(...))` 拿 `ChatResponse`，
>    在"content 为空/解析失败"的 WARN 里带上 `finish_reason` 与 `usage`（尤其 `reasoning_tokens`）——
>    T3.2 就是靠手工复现才知道是 reasoning 吃光 token（K-29），以后要能从日志直接看出来。可选尝试 `response_format: json_object`。
> ⑦ **开工前必须先算清"单次 JSON 体积预算"（写码方提的唯一未压测点，采纳）**：合并后一次调用要在 `max_tokens=8192` 里同时装
>    **思考链 + 结构化正文**。参考实测：reasoning 3000~3800（T3.2），8192−3800≈**4400** 留给正文；
>    粗算正文：summary 200 字≈300~400 tok + keywords 8 个≈30 tok + 20 条 todos×~50 tok≈1000 tok → **约 1500**，余量 ≈2900。
>    【规划四问】里要给出你的估算与**超限策略**（建议：todos 代码截断 20、summary 限 200 字、keywords ≤10；
>    `finish_reason=length` → 该任务**降级 EXTRACTIVE** + WARN 带 reason，**不做无限重试**）。
>    不允许"跑样本时才发现被截断"——预算先算，样本只用来验证预算。
> ⑧ **LOCAL 档真实生成也要验（用户 2026-11-14 已批准；环境验收方已备好，别自己拉）**：
>    `ollama` 服务在 `profiles: ["local-llm"]`，验收方已 `up -d` 并**导入好模型 `qwen2.5:1.5b`**（`ollama list` 可见，1.1GB）。
>    ⚠️ **不要执行 `ollama pull`**：本网络下 ollama registry 的 blob 宿主（r2.cloudflarestorage.com）不可达，会 3 次 EOF 失败；
>    模型是用 hf-mirror 的 GGUF + `ollama create` 导入的。若模型丢失，找验收方重新导入，不要自己硬拉。
>    验证：`.env` 设 `OLLAMA_ENABLED=true` / `OLLAMA_MODEL=qwen2.5:1.5b` / `OLLAMA_BASE_URL=http://ollama:11434/v1` → 重建 app →
>    传样本 → 期望 `ai_tier=LOCAL` 且 summary/todos 可解析非空。**小模型 JSON 不达标是允许的结论**：
>    此时必须能观察到"LOCAL 失败 → 降级 EXTRACTIVE，`ai_tier=EXTRACTIVE`，WARN 带 reason"——这条比"LOCAL 成功"更重要（级联降级兜底）。
>    验收方实测基线（供你对照）：`POST http://ollama:11434/v1/chat/completions`（qwen2.5:1.5b，max_tokens 512，temperature 0）
>    → **4.8s、`finish_reason=stop`、合法 JSON、out 68 tok**；但**编造了原文没有的日期**（原文"下周三"→ 输出 `2023-04-15`）→ 所以 ⑨ 必须做。
>    验完把 `.env` 恢复 `OLLAMA_ENABLED=false`（默认关闭、用户可选开），并在回报里写清"LOCAL 成功/降级"到底发生了哪个。
> ⑨ **待办依据校验（所有档位统一，治编造；LOCAL 实测会编日期）**：`owner` / `deadline` 必须在转写里有依据，否则一律置 `null`：
>    - `owner`：抽取到的人名/称呼**出现在 transcript** 才保留，否则 null；
>    - `deadline`：日期/时间词（如"下周三""下个月初""11月20日"）**在 transcript 中出现**才保留，否则 null——
>      不许把"下周三"直接改写成具体日期（模型会瞎算，实测 2023-04-15 就是编的）；
>    - `action` 同样要能对回 transcript（允许概括，但核心词要有依据）；
>    - 这套校验写成公共方法，CLOUD/LOCAL/EXTRACTIVE 三档都走，单测覆盖"编造的 deadline 被置 null"。

【规划四问】
- **改哪里**：`ai/AiTier.java`（新）、`ai/AiStructuringRouter`（新，或并入现有 config）、`ai/StructuringService`（新：**一次** LLM 调用出 summary/keywords/todos；`SummaryService`/`TodoService` 保留还是收编由实现方定，但对外行为按 DTO 统一）、`ai/ExtractiveStructurer`（新）、`worker/TranscriptionWorker`（调用点改一处）、`web/`（**不改**，`ai_tier` 已在 T3.1 返回）、`application.yml`（F-8 一行默认值）、单测 3~4 个文件。
- **复用什么**：T3.1 `SummaryService` 结构与"解析失败 WARN 截 200 字不重试"；T3.2 `TodoService` 的"未知→null/空 action 丢弃"；`AsrHttpClientConfig` 的 120s 路由；`LlmModelSwitchEnvironmentPostProcessor`（无 key 时 bean 不存在 → 用它而不是 `new`）；**`task_result.todos/summary/keywords/ai_tier` 列都已存在，不要新迁移**。
- **影响哪些功能**：无 key 时从"全 null"变成"有抽取式结果"（用户可见变化）；`ai_tier` 三个取值；启动期多一次 ≤2s 的 ollama 探活（失败只 WARN）；**LLM 调用 2→1（F-7 必做）**，DONE 前最坏等待 240s→120s；`summary` 与 `todos` 从"可能一成一败"变成"要么都成、要么都空"。
- **怎么验证**：见【验收】，反例是"删 key 仍产出"和"ollama 配了但没起也要能启动"。

【范围声明】
- 做：tier 判定 + LOCAL 接线 + EXTRACTIVE 最小实现 + 同构 DTO + `ai_tier` 落库 + F-5/F-6/F-8 收口 + 单测。
- 不做：T3.4 的抽取式**质量提升**（分词库选型/TextRank 深做/规则待办精细化）、T3.9 模板、前端展示（T4.13）、待办勾选（T3.5）；不改表结构、不新增迁移、不加新配置键（`OLLAMA_*`/`OFFLINE_MODE` 都是已有键）。
- 禁：把 EXTRACTIVE 结果标成 CLOUD（`ai_tier` 必须诚实，D-020）；无 key/ollama 不可达时抛异常或 500；启动期因探活失败而失败；删改已有断言；为省事把待办塞进 summary；**删证据行**（要做新反例 → 用**未上传过的样本**，如 `a_1113.wav` / `a_11月7日.wav`，别靠删任务行让 probe.wav 幂等失效，D-073）。
- **公共面**：只允许 `application.yml` 里 `LLM_MAX_TOKENS` 默认值这一处（用户 2026-11-14 已批准，F-8）；其它公共面要动先报。

【验收】（反例必须进；**证据任务行不删**，报告里写 taskId，D-073）
```powershell
# ① 零依赖档（核心反例）：.env 里 LLM_API_KEY= + OLLAMA_ENABLED=false（改前先备份 .env，D-070）→ 重建 app → 传有语音样本
#    期望：status=DONE，summary 非空、todos 非空（或至少 summary 非空），ai_tier=EXTRACTIVE，无 500
docker compose exec -T postgres psql -U mn -d meetingnotes -t -A -c "select task_id, ai_tier, length(summary), jsonb_array_length(todos) from task_result order by task_id desc limit 5"
# ①b 离线门禁反例（D-077）：.env 恢复 key 但 OFFLINE_MODE=true → 必须 ai_tier∈{LOCAL,EXTRACTIVE}（无外连）+ WARN reason=offline_mode
# ② 云档：恢复 key（.env 哈希与备份一致）**且 OFFLINE_MODE=false** → 重建 → 传样本 → ai_tier=CLOUD，摘要 150~250 字、todos 可追溯
# ③a LOCAL 降级路径：OLLAMA_ENABLED=true 但 ollama 没起（或模型没拉）→ **应用仍 healthy** + 启动 WARN + 该任务 ai_tier=EXTRACTIVE
# ③b LOCAL 真实生成（环境已备好、模型已导入，**不要 ollama pull**）→ 传样本 → 贴 ai_tier（LOCAL 或"LOCAL失败→EXTRACTIVE"）+ WARN 里的 reason
docker compose --profile local-llm up -d ollama ; docker compose exec -T ollama ollama list    # 应见 qwen2.5:1.5b
docker compose logs app --since 5m | Select-String 'ollama' ; curl.exe -s --noproxy "*" http://localhost:8080/actuator/health
# ③c 待办依据校验（⑨）：LOCAL/CLOUD 输出里凡是原文没有的 owner/deadline 必须被置 null（贴一条"编造被拦下"的单测或日志）
# ④ 空音频（纯静音/纯音乐样本）→ transcript="" + summary/todos=null + ai_tier=null（不是 EXTRACTIVE）
# ⑤ 变异验证：把 tier 判定顺序/返回值写反（例如 CLOUD 判成 EXTRACTIVE）→ 新单测必须红，验完还原
# ⑤b 变异验证二：把"合并调用"改回两次调用（或把 DTO 某字段写死 null）→ 相关单测必须红，还原后必须再绿（K-30：touch 防 Maven 增量编译）
# ⑥ 顺序不变（F-1 回归）：taskId 日志链仍是 转写落库 → 结构化（一次调用）→ DONE；贴一条完整时间线
# ⑥b 合并调用证据：日志/单测证明**一次** LLM 调用同时产出 summary+keywords+todos（不是两次），失败时三字段**一起**留空
# ⑥c 新反例要新任务 → 用未上传过的样本（a_1113.wav / a_11月7日.wav），禁止删证据行（D-073）
# ⑦ 回归：错误四连（404/405/415/400）、列表、详情字段只增不减；全量 `scripts\test.ps1` ≥76 用例 exit 0；
#    check-maintainability exit 0；worker ≤300 行；干净 shell（不预置环境变量）
```
**通过标准**：三档都能选对且 `ai_tier` 诚实落库（**级联：CLOUD→LOCAL→EXTRACTIVE**）；**删 key 后仍产出非空 summary（EXTRACTIVE）且无 500**；ollama 不可达时应用照常启动；**LOCAL 档有明确结论**（成功=ai_tier LOCAL，或失败=降级 EXTRACTIVE + WARN reason）；空 transcript 的 null 语义不被破坏；**F-7 合并成一次调用**（三字段同生同灭、DONE 前最坏 120s）；**JSON 体积预算在开工确认时给出、样本实测未出现 `finish_reason=length` 截断（若出现，必须有降级证据）**；**⑨ 依据校验生效：编造的 owner/deadline 被置 null（有单测 + 一条实测证据）**；单测 ≥76 exit 0；F-5/F-6/F-8 三处收口各有一条可判据。

【可维护性】tier 判定集中在一个类（不要散在 service 里 if/else）；三档同构 DTO；`ai/` 单向依赖（`worker → ai`，不许反向）；
新增分支必须有测试（每个档位至少一条）；EXTRACTIVE 不用第三方分词库（T3.4 再定）；commit 前 `check-maintainability.ps1` exit 0。

【回报·测试三问】检查了哪些场景（三档 × 可用性矩阵逐格）｜ 实际命令 + 输出（三档各一份 taskId + `ai_tier` + 长度；ollama 不可达的启动日志；变异反例；exit code）｜ 还有哪些没验证（**不许留空**；F-7 的 120s 真超时留到 T3.8 就写"留到 T3.8"）。

## T3.4 抽取式提质（EXTRACTIVE 质量）+ 结构化输出容错（约 2.5h）✅ **已完成（`27d7ed8`；我复跑 120/120 exit 0、双变异各造红、我独立实机 108/109 非前缀 kw 10 todos 4/3、110 CLOUD、112 空音频全 null；**并抓出修复 T3.3 遗留 `summaryOf` 少选句 bug**；F-11 本地档预算仍待用户批准）**

> **现状**：T3.3（`991f429`）已交付**最小可用**抽取式 + 三档**级联**降级（CLOUD→LOCAL→EXTRACTIVE），`ai_tier` 诚实落库；
> 但实机证据行 96/97 显示 EXTRACTIVE 质量弱：`summary` 112~117 字但**基本等于"前 N 字截断"**（ASR 输出没有标点/空格，分句退化）、`keywords` 只有 **3 个**、`todos` 只有 **1 条**。
> 另外 LOCAL 档（qwen2.5:1.5b）在 app 内**未产出 `ai_tier=LOCAL`**（F-11：全局 `LLM_MAX_TOKENS=8192` 触顶 120s；512 时输出带散文导致 JSON 解析失败）。
>
> **本卡目标（只做质量与容错，不加配置键）**：
> ① 零依赖档在**无标点 ASR 文本**上也能产出"像摘要"的结果（不是前 200 字截断）、keywords ≥5、todos ≥1~2；
> ② 云/本地档对"带散文/```json 代码块/前后缀"的模型输出能**抽出 JSON**，减少无谓降级；
> ③ **F-11 只记录不实现**——本地档独立 `max_tokens`/读超时属于**新增配置键**，必须用户批准后另开卡（本卡禁止加键）。

**【前置】** 读 T3.3 的 `ai/ExtractiveStructurer`（237 行）、`ai/TodoGrounding`、`ai/StructuringService.fromModel`，理解现有分句/打分/校验口径；不要推翻已验收的三档级联与 `ai_tier` 语义。

**主任务（约 2h）**：
1. **EXTRACTIVE 摘要提质（无标点退化路径）**：现在只按 `。！？；\n` 分句，ASR 无标点时退化为"前 200 字"。
   实现退化路径：按**停顿/连接词**（然后/接下来/所以/首先/那么/就是/我们/大家/但是/因为/比如/最后）与**固定字符窗口**双重切分，
   再用"词频 + 位置（开头/结尾加分）+ 长度惩罚"打分取前 5 句拼成 summary（≤200 字，**必须覆盖开头和结尾的要点**，不能只截开头）。
2. **keywords 提质**：在现有字符 n-gram + 停用词基础上补"口头禅/虚词表"（嗯/啊/就是/然后/这个/那个/我们/大家/的话/对吧…），
   过滤碎片（长度 ≥2、非纯数字/纯英文单字母），目标在真实样本上 **≥5 个**且不是碎片。
3. **todos 规则提质**：命中信号词（需要/要/得/必须/记得/负责/截止/之前/完成/提交/确认/明天/下周）→ 抽 action（≤40 字、去掉"然后我们"这类口头前缀）；
   owner/deadline 只能"原文里有才填"（**复用 `TodoGrounding`**，D-076：不换算相对时间、不猜）。
4. **结构化输出容错（云/本地共用）**：新增一个小的"从文本抽 JSON"工具——找第一个 `{` 到**匹配的** `}`（支持 ```json 代码块包裹、前后有散文），
   抽出来再交给现有 `parse`；抽不到才按"解析失败 → 降级"处理。**不要放宽提示词**里的 JSON 硬约束，也不要改 `ai_tier` 语义。
5. **度量必须量化**：用**新样本**（见【验收】⑥）跑 3 条 EXTRACTIVE，记录 summary 是否"非前 N 字截断"（人工判：首句/末句是否覆盖不同议题）、keywords 数、todos 数，
   与 T3.3 基线（96：summary 112 字/kw 3/todos 1；97：117 字/kw 3/todos 1）做**同列对比表**。

**【规划四问】**（开工确认时回述）
- **改哪里**：`ai/ExtractiveStructurer`（分句/打分/keywords/todos 规则）、新增 `ai/JsonExtractor`（或放 `StructuringService` 内的小工具）、`ai/StructuringService.fromModel` 调用点、`ai/StructuringPrompt`（**只在必要时**微调口头禅约束）；`web/`、`worker/`、表结构**不改**；测试 2~3 个文件。
- **复用什么**：`TodoGrounding`（依据校验，别重写）、`StructuringResult` 同构 DTO、`MAX_SUMMARY_CHARS/MAX_KEYWORDS/MAX_TODOS` 常量、现有 WARN 截断语义。
- **影响哪些功能**：只影响 EXTRACTIVE 档质量 + 云/本地档容错；CLOUD 成功路径、`ai_tier`、null 语义、DONE 顺序**不得变**。
- **怎么验证**：见【验收】1~6。

**【范围声明】**
- 做：抽取式摘要/keywords/todos 质量、无标点退化路径、JSON 抽取容错、单测与实机对比表。
- 不做：**不改 `application.yml`/`.env`/`.env.example` 任何键**（F-11 本地档预算待用户批准）；不引入第三方分词库（T3.4 只允许"自带停用词 + n-gram"；真要上分词库须先报）；不改 web/API 字段；不改表结构/迁移。
- 禁：把 EXTRACTIVE 结果标成 CLOUD/LOCAL；为过验收改断言或删证据行（D-073）；删 T3.3 已有断言；把 todos 塞进 summary；**不许在仓库里留下临时脚本/大文件**。

**【验收】（反例必须进；证据任务行不删，报告写 taskId，D-073）**
```powershell
# ① 全量单测（干净 shell）≥90 用例 exit 0（T3.3 基线 97；新增用例只能加不能减）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File D:\new\tools\check-maintainability.ps1   # exit 0；worker ≤300 行
# ② EXTRACTIVE 实机对比：.env 清 key + OLLAMA_ENABLED=false（改前备份，D-070）→ 重建 app → 传 3 个新样本
docker compose exec -T postgres psql -U mn -d meetingnotes -t -A -c "select r.task_id, r.ai_tier, length(r.summary), jsonb_array_length(r.keywords), jsonb_array_length(r.todos) from task_result r order by r.task_id desc limit 3"
#    期望：ai_tier=EXTRACTIVE；与 T3.3 基线（96/97：kw 3 / todos 1）同列对比，kw ≥5、todos ≥1、summary 首尾覆盖（贴 3 条 summary 原文）
# ③ JSON 容错单测：输入"散文 + ```json {...} ``` + 尾巴" → 解析成功；输入 `{` 不闭合 → 解析失败并降级（两条用例必须能造红）
# ④ CLOUD 回归：恢复 key（OFFLINE_MODE=false）→ 重建 → 传 1 个新样本 → ai_tier=CLOUD、summary≥150 字、无 500（成功路径不被容错改坏）
# ⑤ 契约/顺序回归：空音频（新静音样本）→ 四字段 null；错误四连 404/405/415/400；详情 11 键不变；DONE 最后写（贴一条时间线）
# ⑥ 新样本纪律：T3.3 证据行 88~101 不删；本轮用**未上传过**的新样本（如 probe25/26/27 或 a_1119.wav；先看上传响应里 reused=false）
# ⑦ 变异验证（必须造红→还原→绿，还原后 touch 防增量编译 K-30）：
#    删掉"无标点退化路径"（只留按标点分句）→ 新用例必须红；把 JSON 抽取改回直接 parse → 容错用例必须红
```
**通过标准**：EXTRACTIVE 在无标点样本上 summary **不再是前 N 字截断**（首尾覆盖可人工判 + 用例可判）、keywords ≥5、todos ≥1；JSON 容错生效且有"抽不到→降级"反例；
CLOUD 回归不变；空音频 null 语义不变；单测 ≥90 exit 0；可维护性 exit 0；worker ≤300；**零配置键改动**（`git diff --name-only` 里不能出现 `.env*`/`application.yml`）；证据行不删。

**【可维护性】** 新逻辑集中在 `ExtractiveStructurer` + 一个小的 JSON 抽取工具类；**别把 3 个规则分散到 5 个文件**；常量集中（200/10/20 已有）；
不引入新依赖；`ai/` 单向依赖不变；commit 前 `check-maintainability.ps1` exit 0。

**【回报·测试三问】** 检查了哪些场景（EXTRACTIVE × 3 新样本 / CLOUD × 1 / 空音频 / 错误四连 / 容错两反例）｜ 实际命令 + 输出（3 条 taskId + `ai_tier` + summary/kw/todos；T3.3 基线同列对比表；变异红/绿；exit code）｜ 还有哪些没验证（**不许留空**；F-11 本地档预算就写"待用户批准新键"；`finish_reason=length` 实机未触发可写"留 T3.8"）。

## T3.5 改词回流（`glossary`）+ 待办勾选/删除（约 3h）✅ **已完成（`9a34fcf` + 复核 `9f05c69`；我独立实测 task 119 改词/重复改词 hits 1→2/勾选/删除持久/反例 400/404/415、详情 11 键不变；133/133；M5/M9 清账 allowlist 清零且我造红验闸门；见 `review/2026-11-14-T3.5-T3.11验收.md`）**

> **现状**：`task`/`task_result`/`glossary` 三张表列都已存在（`glossary`: `user_id`/`wrong_text`/`right_text`/`hits`/`priority`，`UNIQUE(user_id, wrong_text)`，D-037）；
> 但 web 层只有「提交 / 列表 / 详情」三个只读端点，**没有任何写回接口**；`todos` 是 jsonb 数组，元素只有 `action/owner/deadline`（无 `done`）。
> **本卡目标**（spec §5.4 / US-3 的"回访"段）：①用户改错词/人名 → 替换该任务文本**并沉淀到 glossary**（`hits` 递增，供 T3.6 注入）；
> ②待办可**勾选完成 / 删除**，且**写库持久**（刷新后仍在）；③对外契约**只增不改**。

> ⚠️ **公共面预警**：本卡要**新增写端点**（路径/方法/请求体/响应/错误码都是对外契约）。按规矩：**开工确认必须先给 API 契约草案 + 错误码映射 + 示例请求/响应，经批准后才写码**；
> 批准前只许读代码/写方案，不许写实现。若你判断"改字段语义/加表结构"更省事 → 先报，不许擅自动。

**主任务**：
1. **改词 → 写 glossary + 替换文本**（核心 US-3 的入口）：请求体形如 `{"corrections":[{"wrong_text":"章总","right_text":"张总"}]}`（最终以你方案为准，先报）；
   事务内完成：①把该任务 `task_result.transcript` 里出现的 `wrong_text` **全部替换**，有 `summary` 时同一错词也替换（keywords/todos 里出现的也替换，避免"改了词摘要还是错的"）；
   ②`INSERT ... ON CONFLICT (user_id, wrong_text) DO UPDATE SET hits = glossary.hits + 1, right_text = EXCLUDED.right_text, priority = ...`；
   ③返回受影响条数 + 修改后的关键字段（供前端刷新）。**注意**：`wrong_text` 空/超长/等于 `right_text` → 400；任务不存在 → 404；非 multipart 的 JSON 请求要放行（不是 415）。
2. **待办勾选 / 删除**：`todos` jsonb 元素**增加 `done` 字段**（默认 `false`；老数据无此字段按 `false` 读，**不加迁移、不改表结构**）。
   切换完成：PATCH 语义（幂等：重复设为 true 仍 200）；删除：整条从数组移除；两者都要**写库**，重新 GET 详情必须看到最新状态。
   下标越界 → 400 或 404（口径自定但要在契约里写明）；`todos` 为 `null` 时不许 500。
3. **详情契约只增**：`todos` 元素从 `{action,owner,deadline}` 变为 `{action,owner,deadline,done}`；详情顶层 11 键不变；列表契约不变。
4. **错误码**：复用现有分层（404 `TASK_NOT_FOUND` / 400 `INVALID_PARAM` / 415 `UNSUPPORTED_MEDIA_TYPE` / 405）；**不许新增业务码**除非报批。
5. **回访闭环自测**：改词后，重新 `GET /api/v1/tasks/{id}` 能看到替换后的 transcript/summary；`glossary` 表有记录且 `hits` 正确；勾选/删除后重新 GET 状态一致。

**【规划四问】**（开工确认回述，含 API 契约草案）
- 改哪里：`web/`（新增 handler + DTO，注意 **F-10 技术债：不要每行 `new ObjectMapper()`**，可顺手注入单例但不要扩大到别的卡）、`repository/JdbcTemplate` 层（glossary upsert、jsonb 更新）、`ai/TodoService`（`TodoItem` 加 `done` 的读写兼容——**只加字段，不改依据校验**）；表结构/迁移**不动**；`worker/` 不动。
- 复用：现有 `ApiException`/错误码分层（D-057）、`TaskController` 详情查询、`ObjectMapper` 注入方式、`user_id` 固定口径。
- 影响：详情 `todos` 元素多一个 `done`；新增 2~3 个写端点；无 key/无 AI 不影响（纯 DB 操作）。
- 怎么验证：见【验收】。

**【范围声明】**
- 做：改词替换 + glossary upsert（hits 递增）+ 待办勾选/删除持久化 + 详情 `done` 字段 + 单测（含 DB 层）。
- 不做：T3.6 术语注入（下一张卡）、前端页面（T4.13）、批量操作（T3.7）、认证（T4.6）、改表结构/迁移、改已有字段语义、加新配置键。
- 禁：前端假状态（必须写库）；改测试断言；删证据行（D-073）；把 `done` 塞进 `action` 字符串；未报批就改路径/响应结构。

**【验收】（反例必须进；证据任务行不删，报告写 taskId，D-073）**
```powershell
# ① 契约草案先批后写：开工确认里贴 API 契约（方法/路径/请求/响应/错误码/示例）+ 范围声明
# ② 改词：对一条 DONE 任务执行改词（用真实错词，如把转写里某词改成正确写法）
#    期望：curl 返回 200 + 受影响条数；重新 GET 详情 → transcript/summary 已替换
docker compose exec -T postgres psql -U mn -d meetingnotes -t -A -c "select user_id, wrong_text, right_text, hits from glossary order by user_id, wrong_text"
#    再改同一个错词一次 → hits 从 1 变 2（幂等累积，不是插新行）
# ③ 勾选：PATCH 某条 todo done=true → 重新 GET 详情 todos[i].done=true；再 PATCH false → false（幂等）
# ④ 删除：DELETE 某条 todo → 数组少 1 条，重新 GET 仍少 1 条（写库不是内存）
# ⑤ 反例：改不存在的 taskId → 404；空 wrong_text → 400；下标越界 → 400/404；todos=null 的任务勾选 → 4xx 不 500
# ⑥ 回归：详情顶层 11 键不变（todos 元素只增 done）；列表契约不变；错误四连（404/405/415/400）仍对；空音频任务的 null 语义不变
# ⑦ 单测 ≥120 保持（新增覆盖 glossary upsert 累加 / done 兼容老数据 / 替换只在本任务内）exit 0；check-maintainability exit 0；worker 不动
# ⑧ 变异（必须造红→还原→touch→绿）：把 glossary upsert 删掉 → 改词用例红；把勾选改成只改内存不写库 → 刷新用例红
# ⑨ 只允许一个写者；.env 不做改动（本卡不需要改配置）；commit 前 git diff --name-only 贴出
```
**通过标准**：改一次词 → glossary 有记录且 hits 递增、任务文本已替换；勾选/删除**刷新后仍生效**（写库）；详情 `todos` 仅增 `done`；反例 404/400 不 500；单测 ≥120 exit 0；可维护性 exit 0；**零迁移/零新配置键**；未报批的公共面改动一律不算通过。

**【可维护性】** glossary 读写与 todos 更新放独立 repository（别塞进 controller）；`done` 兼容旧数据要有单测；jsonb 更新用参数化 SQL（不许拼字符串，防注入）；`F-10` 顺手收（详情 `ObjectMapper` 单例注入）但**不扩大**。

**【回报·测试三问】** 检查了哪些场景（改词/重复改词/勾选/取消/删除/越界/非法输入/回归）｜ 实际命令 + 输出（curl 原样 + psql 查询结果 + taskId；变异红/绿；exit code）｜ 还有哪些没验证（**不许留空**；T3.6 注入、前端 UI、并发改同一任务可写"留 T3.6/T4.x"）。

## T3.11 本地档独立预算（F-11，约 1h，**用户 2026-11-14 已批 D-081**）✅ **已完成（`64434bb`；两键三处一致、独立超时通道不碰云路由；实机 LOCAL 512 精确截断→降级（33s、无 500）、云档回归 CLOUD；变异造红后绿。遗留 F-14：`ai_tier=LOCAL` 未达成，见验收报告）**

> **现状**：LOCAL 档（ollama + qwen2.5:1.5b）在 app 内**必然失败**：本地 `ChatModel` 继承了云档的
> `spring.ai.openai.chat.options.max-tokens`（=8192，推理模型要这么多）与 120s 读超时 →
> 1.5B/CPU 生成超 120s 触顶（实测 124.9s，报误导性 `RestClientException … content type [application/octet-stream]`），
> 而临时把 `max_tokens` 调到 512 时 **16.8s 就返回**（输出带散文，T3.4 的 `JsonExtractor` 已能抽出 JSON）。
> 结果：`ai_tier` 实际只有 CLOUD / EXTRACTIVE 两种，第二档等于"挂名"。
>
> **目标**：给 LOCAL 档一套**独立预算**，让它真实产出 `ai_tier=LOCAL`；**云档/ASR 的预算与超时一个字不改**。

**主任务**：
1. **新增 2 个配置键（只这两个，别多加）**，三处一致（`.env.example` / `application.yml` 的 fallback / `配置清单.md` 已写好）：
   - `LLM_LOCAL_MAX_TOKENS` 默认 **512**；
   - `LLM_LOCAL_TIMEOUT_SEC` 默认 **180**。
2. **`config/OllamaChatModelConfig` 消费这两个值**：本地 `ChatModel` 的 `maxTokens` 用本地键（不再读云档的 `spring.ai.openai.chat.options.max-tokens`）；
   读超时用本地值——**必须建独立超时通道**（自己的 `RestClient.Builder`/`HttpClient`，connect 仍 5s），
   **不许改 `AsrHttpClientConfig` 里云的 120s / ASR 的 900s 路由**（回归要能验云档仍 120s）。
3. **可观测**：启动/首次调用打一条 INFO 说明本地档预算（`max_tokens=… / timeout=…s`）；失败时 WARN 里带这两值 + 失败原因（沿用现有降级 WARN，不许改级联语义）。
4. **不改**：`AiStructuringRouter` 判档顺序、`StructuringService` 级联、`ai_tier` 语义、web/表结构、worker；不加新业务码。

**【规划四问】**（开工确认回述）
- 改哪里：`application.yml`（2 键）、`.env.example`（2 键）、`config/OllamaChatModelConfig`（预算注入 + 独立超时）、单测 1~2 个文件；`AsrHttpClientConfig` **不改**。
- 复用什么：现有 D-058/D-068 的"按用途分超时"思想（这里只是给本地档第三条通道）、`OllamaProbe` 2s 探活不动。
- 影响哪些功能：只影响 LOCAL 档（从"必失败"变"可能成功"）；云档/离线门禁/抽取式/契约全不变。
- 怎么验证：见【验收】。

**【范围声明】**
- 做：2 个键 + 本地 `ChatModel` 预算/超时 + 单测 + 实机（LOCAL 成功或明确失败降级）+ 云档回归。
- 不做：不加第 3 个键（如本地 temperature/上下文长度）；不改云档 max_tokens 8192；不改 120s 云路由；不动 `AiStructuringRouter`/`TodoGrounding`；不做前端。
- 禁：为让 LOCAL 成功而放宽云的 8192 或超时（A 让 B）；用 `ollama pull`（本网络不通，模型已在镜像里）；删/改已有断言；删证据行（D-073）。

**【验收】**
```powershell
# ① 三处一致（硬判据）
Select-String -Path .env.example,app\src\main\resources\application.yml -Pattern 'LLM_LOCAL_MAX_TOKENS|LLM_LOCAL_TIMEOUT_SEC'
#    期望：.env.example 两行 + yml 两个 fallback（512 / 180）；配置清单.md 两行已就位
# ② 单测：本地 ChatModel 的 maxTokens=512、超时=180s（反射/属性断言，必须能造红）；全量 ≥131 exit 0；check-maintainability exit 0
# ③ 实机 LOCAL：.env 设 OLLAMA_ENABLED=true / OLLAMA_MODEL=qwen2.5:1.5b / LLM_API_KEY 空 / OFFLINE_MODE=false（改前备份，D-070）
#    → docker compose up -d app → 传**新样本**（先看 reused=false）→ 期望：
#    docker compose exec -T postgres psql -U mn -d meetingnotes -t -A -c "select task_id, ai_tier, length(summary) from task_result order by task_id desc limit 1"
#    **ai_tier=LOCAL** + summary 非空 + 耗时（贴日志 "结构化已落库" 与上一行时间差）；若失败：必须 WARN 带 reason 且 ai_tier=EXTRACTIVE（两者都能接受，写清是哪种）
# ④ LOCAL 失败路径仍降级：临时把 OLLAMA_MODEL 改成不存在的模型（探活仍 200、调用必失败）→ 期望 ai_tier=EXTRACTIVE + WARN，无 500，应用照常
# ⑤ 云档回归：恢复 key（OFFLINE_MODE=false）→ 重建 → 传新样本 → ai_tier=CLOUD 且正常；再确认云档超时仍是 120s（改的是本地通道，别串线）
# ⑥ 空音频（新静音样本）→ 四字段 null；错误四连 404/405/415/400；详情 11 键不变
# ⑦ 变异：把本地 max_tokens 改回继承云档 8192（或本地 timeout 改回 120）→ 单测必须红 → 还原 → touch → 绿（K-30）
# ⑧ 收尾：`.env` 恢复基线哈希 2BCC440F1C9E；证据行不删；`git diff --name-only` 只含配置/代码/测试（允许 `.env.example`、`application.yml`——本卡是**批准过的公共面**）
```
**通过标准**：2 键三处一致且在本地档生效（单测可判 + 实机可判）；**LOCAL 档有明确结论**（`ai_tier=LOCAL`，或失败降级 + WARN 写清原因）；
云档/空音频/错误四连/详情 11 键全不变；全量 ≥131 exit 0；可维护性 exit 0；worker 不动；零新业务码；证据行不删。

**【可维护性】** 本地档预算集中在 `OllamaChatModelConfig`（一个类里说清"为什么本地和云不一样"）；超时值不许散落魔法数；键名与 `配置清单.md` 一致；commit 前 `check-maintainability.ps1` exit 0。

**【回报·测试三问】** 检查了哪些场景（LOCAL 成功/LOCAL 失败降级/云端回归/空音频/错误四连）｜ 实际命令 + 输出（taskId + `ai_tier` + 耗时 + 日志；三处一致 grep；变异红/绿；exit code）｜ 还有哪些没验证（**不许留空**；`ollama pull` 不通、模型换新版本未验可写"已知环境限制"）。

## T3.6 术语注入（`glossary` → 结构化提示词，US-3 闭环）（约 1.5h）✅ **已完成（`a8845b4`；我独立复跑 139/139、变异造红后绿、独立实机 task 123 闭环（transcript 留错词、summary/keywords 用正确写法）；139 用例；F-15 抽取式不读词表待评估）**

> **现状**：T3.5 已把"用户改词"沉淀进 `glossary`（`wrong_text`/`right_text`/`hits`/`priority`），但**没有任何地方读它**——
> 用户改过"章总→张总"后，下一次转写照样输出"章总"，US-3 闭环没合上。
> **本卡目标**：让结构化提示词读取 glossary（按 hits/priority 取前 N 条），使**下一次转写**的 summary/keywords/todos 主动使用正确写法。
>
> ⚠️ **配额红线**：今日已 100/120 分钟、**47/50 任务**——本卡实机**最多新增 2 个任务**。技巧：需要"同一段语音的第二个任务"时，
> 用 python `wave` 把已有样本**去掉第一个采样点**重写（语音内容不变、字节/hash 变 → `reused=false`），别反复传新长音频。

**主任务**：
1. **新增 `GlossaryTermSource`（或等价 repository 方法）**：读 `glossary` where `user_id=1`，按 `hits desc, priority desc, wrong_text asc` 取前 **20** 条，拼成一段**短注入文本**
   （格式自定但要可读，例如 `【术语表（原文说法 → 正确写法）】章总→张总；…`），**总长 ≤400 字**（超了按序截断），空表时返回空串（不注入、不报错）。
2. **注入 `StructuringPrompt.build(transcript)`**：把上面这段拼在"要求"之前，并加一句"输出中遇到下列说法，一律改用正确写法"；
   **不改**已有 JSON 结构约束、不改 `summary/keywords/todos` 字段定义、不改 max_tokens 预算公式（400 字 ≈ 400~500 tok，仍在余量内）。
3. **可观测**：结构化日志一条 INFO：`术语注入：N 条（≤20）/ 长度 M 字`（**不打印词条内容**）；空表时打"术语注入：0 条（跳过）"。
4. **三档一致性**：提示词注入对 CLOUD/LOCAL 都生效（同一段文本）；EXTRACTIVE 档不调 LLM，**本卡不改抽取式逻辑**（若要"确定性替换"另开卡，先报）。
5. **测试**：单测覆盖 `GlossaryTermSource`（排序/上限 20/长度 400 截断/空表）、`StructuringPrompt` 含注入段与不含注入段的两种输出、`StructuringService` 调 prompt 时把注入文本传进去（可断言 `Prompt.getContents()` 含"术语表"）。

**【规划四问】**（开工确认回述）
- 改哪里：`ai/GlossaryTermSource`（新）、`ai/StructuringPrompt`（拼段）、`ai/StructuringService`（取注入文本传给 prompt）；`web/`/worker/表结构/配置**零改动**；测试 2~3 个文件。
- 复用什么：`JdbcTemplate` 注入方式、`StructuringPrompt` 现有结构、`MAX_*` 常量收敛、日志 JSON/MDC 口径。
- 影响：CLOUD/LOCAL 提示词变长（≤400 字）；空 glossary 时行为与现在**逐字节一致**（回归判据）；`ai_tier`/契约/空音频语义不变。
- 怎么验证：见【验收】。

**【范围声明】**
- 做：glossary 读取 + 排序上限 + 注入提示词 + 单测 + **1~2 条实机**（改词→下一次转写）。
- 不做：抽取式的确定性替换（另开卡）、前端展示、注入条数/开关配置化（不加新键；要配置化先报）、改 `StructuringPrompt` 的 JSON 约束、改 T3.5 的写端点。
- 禁：注入超过 400 字/20 条；打印词条内容到日志（隐私）；空表时改变任何输出；改已有断言；删证据行（D-073）；**超过 2 个实机任务**（配额）。

**【验收】**
```powershell
# ⓪ 先清理 T3.5 验收遗留的测试词条（这 2 条是验收造的，不是真实词表；任务行不删，D-073）
docker compose exec -T postgres psql -U mn -d meetingnotes -c "delete from glossary where wrong_text in ('第二次的开发','这就是我')"
# ① 单测：排序/上限 20/400 字截断/空表跳过/prompt 含注入段；全量 ≥133 exit 0；check-maintainability exit 0
# ② 造一条真实词条：用 T3.5 的 corrections 端点对已有任务改一个**真实 ASR 错词**（如把某专有名词改成正确写法），glossary 出现该条
# ③ 实机闭环（**只跑 1 个新任务**）：把某个已有样本去掉 1 个采样点重写（新 hash → reused=false）上传 →
#    docker compose logs app --since 5m | Select-String '术语注入'   # 应见 "术语注入：N 条（≤20）/ 长度 M 字"
#    GET 详情 → summary/keywords/todos 里应出现 **right_text**、不再出现 wrong_text（贴原文对比）
# ④ 空表回归（可选，不新增任务）：临时把 glossary 清空后重启 app → 日志"术语注入：0 条（跳过）"，同一 prompt 无注入段（单测已覆盖即可，不必再传）
# ⑤ 云档回归：本卡不改预算/路由，无需重跑云档；但要确认 `application.yml`/`.env*` 零改动
# ⑥ 变异：把注入文本从 prompt 里删掉（或强制返回空串）→ 注入单测必须红 → 还原 → touch → 绿（K-30）
# ⑦ 收尾：`.env` 哈希仍 2BCC440F1C9E；`git diff --name-only` 只含 `app/src`；证据行不删；1 个 commit
```
**通过标准**：glossary 被读取（排序/≤20/≤400 字，日志可见）；注入段真的进了 prompt（单测可判）；**1 条实机证明"改过的词在第二次转写里被改成正确写法"**；
空表时行为与现在一致；全量 ≥133 exit 0；可维护性 exit 0；零配置/表结构改动；实机任务新增 ≤2；证据行不删。

**【可维护性】** glossary 读取/排序/截断集中在一个新类；注入文案集中在 `StructuringPrompt`（一处改文案）；上限用常量；不引第三方依赖；commit 前 `check-maintainability.ps1` exit 0。

**【回报·测试三问】** 检查了哪些场景（排序/上限/空表/注入生效/实机闭环）｜ 实际命令 + 输出（taskId + 日志"术语注入"行 + 前后 summary 对比 + 单测红/绿；exit code）｜ 还有哪些没验证（**不许留空**；EXTRACTIVE 的确定性替换、跨任务词表增长可写"留 T3.8/T4.x"）。

## T3.7 导出 Markdown + PASTE 入口（JSON 文本 / srt / ass，跳过转写）（约 2.5h）✅ **已完成（`2027406`；我独立复跑 160/160、可维护性 exit 0、**我另做变异红→绿**；实机 task 124 PASTE 2.57s DONE/跳过 ASR/复用不扣配额、不变量 hash==sha256(对象字节) 成立、导出 123/124 三节、负例 404/400/413/415/429；回报配额 103→我复算 102；F-16/F-17 见 D-085）**

> **现状**：只能 `multipart` 上传音频 → ASR → 结构化；**没有导出端点**；**PASTE（贴文本）/字幕入口未实现**；
> 用户拿不到"下载一份纪要"，粘贴一段会议记录也得先转成音频——spec US-2 的"PASTE 入口"和 T3.7 的"文件含三节"都没兑现。
>
> ⚠️ **两处公共面**：①新增导出端点；②`POST /api/v1/tasks` 增加 JSON/字幕入口（请求体或 `source_type` 可能新增取值）。
> 按规矩：**开工确认先给契约草案（方法/路径/请求/响应/错误码/示例）+ 是否需要新 `source_type` 取值，我批了再写码**。
> ⚠️ **配额红线**：今日 **49/50 任务**——实机**只能新增 1 个任务**（PASTE 那一条）；导出用已有任务 123；字幕解析靠单测，不许为验证再传音频。

**主任务**：
1. **导出 Markdown**：`GET /api/v1/tasks/{id}/export?format=md` → `200`、`Content-Type: text/markdown; charset=utf-8`、`Content-Disposition: attachment; filename="task-{id}.md"`；
   正文三节固定顺序：`## 摘要`（summary）/`## 关键词`（逗号分隔或换行列表）/`## 待办`（`- [x]`/`- [ ]` + action + （owner / deadline），null 不显示）；
   `task_result` 缺失或 transcript 空 → 4xx（契约里写清，不许 500）；`format` 非 `md` → 400；任务不存在 → 404。
2. **PASTE 入口（JSON）**：`POST /api/v1/tasks`，`Content-Type: application/json`，body `{"text":"..."}`（trim 后 ≥1 字符、≤100_000 字符）→
   建任务（`source_type` 用现有枚举里最贴切的值；**如需新取值先报**）→ **跳过 ASR/ffmpeg**、`transcript=text` → 结构化（走现有三档）→ DONE；
   `text` 缺失/空/超长 → 400。响应沿用现有"提交任务"形状（task_id/status/...），**不许改已有键**。
3. **字幕文件（srt/ass）**：`multipart` 上传 `.srt` / `.ass` → 解析去掉序号/时间轴/样式标签，按行合并成 transcript（**跳过 ASR**）→ 同样直接结构化 → DONE；
   解析不出任何文本 → 400（提示"字幕文件没有可识别文本"）。扩展名判断按文件名后缀（大小写不敏感）。
4. **契约只增**：详情 11 键不变、列表不变；导出/PASTE/字幕都是"新增端点/新增请求形态"，不许改已有响应字段语义。
5. **测试**：导出三节顺序 + 待办 done 勾选 + 404/400；PASTE 建任务且 **`verify(transcriptionModel, never())` 不调 ASR**；srt/ass 解析单测各一（含时间轴/样式剥离）；空文本/解析为空 → 400。

**【规划四问】**（开工确认回述 + 契约草案）
- 改哪里：`web/TaskController`（导出 + JSON 入口）、`upload/` 或 `task/`（字幕解析器、跳过 ASR 的提交路径）、`task_result` 读取/导出格式化、单测 3~4 个文件；`worker/` 的 DONE 顺序与既有音频路径**不改**（只加"无音频"分支）。
- 复用什么：现有 `TaskService` 提交/幂等口径、`ApiException`/错误码分层、`TodoItemView`（done）、`MAX_*` 常量、`ObjectMapper` 单例。
- 影响：新增 1 个读端点 + 提交入口多两种输入形态；音频路径与三档级联不受影响；无 key 时 PASTE 仍走 EXTRACTIVE。
- 怎么验证：见【验收】。

**【范围声明】**
- 做：Markdown 导出（三节 + done）+ JSON 文本 PASTE + srt/ass 字幕解析路径 + 单测 + 1 条 PASTE 实机。
- 不做：前端页面/按钮（T4.13）、导出 PDF/DOCX、批量导出（T3.7 卡面只要求 Markdown；批量属 T3.7 后段或 T4.x，要加先报）、改表结构、加配置键。
- 禁：PASTE 路径偷偷调 ASR；导出内容缺节；改已有响应字段/错误码语义；未报批就改公共面；超 1 个实机任务；删证据行。

**【验收】**
```powershell
# ① 单测：导出三节/待办 done/404/400；PASTE 不调 ASR（verify never）；srt+ass 解析；空文本 400；全量 ≥139 exit 0；check-maintainability exit 0
# ② 实机（唯一 1 个新任务，PASTE）：用一句话文本提交 → 期望秒出 DONE、transcript=原文、ai_tier=CLOUD、summary 非空
curl.exe -s --noproxy "*" -X POST http://localhost:8080/api/v1/tasks -H "Content-Type: application/json" --data-binary "@D:\new\artifacts\paste.json"
# ③ 导出（不新增任务）：对已有 DONE 任务 123 导出 → 贴 markdown 正文（三节 + 待办 done 状态）；再验 404/400
curl.exe -s --noproxy "*" "http://localhost:8080/api/v1/tasks/123/export?format=md"
# ④ 字幕解析（单测覆盖；如需实机则用已上传过的字幕文本走 JSON PASTE？——**不许再传音频**）
# ⑤ 回归：音频路径仍正常（用已有证据行说明，不新增任务）；详情 11 键不变；错误四连仍对
# ⑥ 变异：导出删掉"关键词"节 → 导出用例红；PASTE 改成走 ASR → PASTE 用例红；还原 → touch → 绿（K-30）
# ⑦ 收尾：`.env` 哈希 2BCC440F1C9E；`git diff --name-only` 只含 `app/src`（除非报批过契约面）；证据行不删；1 个 commit
```
**通过标准**：导出 Markdown 三节齐全、待办带 done 状态、404/400 正确；PASTE 跳过 ASR 且秒出 DONE（`verify never` 可判）；字幕 srt/ass 能被解析成 transcript（单测 + 至少一种实机或等价证据）；
音频路径与三档级联不受影响；详情 11 键不变；全量 ≥139 exit 0；可维护性 exit 0；零配置键/表结构改动；实机新增 ≤1 任务；证据行不删。

**【可维护性】** 字幕解析独立小类（别塞进 controller）；导出格式化单独方法（便于以后加 PDF）；上限常量集中；不引第三方依赖；commit 前 `check-maintainability.ps1` exit 0。

**【回报·测试三问】** 检查了哪些场景（导出/PASTE/字幕/空值/404/变调）｜ 实际命令 + 输出（导出 markdown 正文、PASTE taskId + 耗时 + ai_tier、变异红/绿、exit code）｜ 还有哪些没验证（**不许留空**；批量导出/前端按钮/PDF 可写"留 T4.x"）。

## T3.9 摘要模板机制（5 模板，US-12）（约 3h，含 shortvideo +0.5h）

> **现状**：结构化只有一套"会议纪要"提示词；`task_result.template` 列已有（VARCHAR(32)）但恒为 NULL；提交入口没有模板参数。
> 学生/播客/访谈/短视频用户拿到的结构全是"会议纪要"——US-12 四类场景（D/E/F/G/K）无证据。
>
> ✅ **契约已预批准（D-085）**：本卡内的 API 契约由 Lead 在卡内定死，**不用再单独报草案**；按卡写码即可。
> 若实现中发现契约不可行（例如详情加键会破坏既有测试语义）→ **立刻停，报"契约冲突"+2 个候选方案**，不要自行改契约。
>
> ⚠️ **配额红线**：今天（2026-11-14）**50/50 任务已满**；写码 + 单测可立即做，**实机等次日 00:00 配额重置**（或环境日期已滚动）。
> 本卡实机**最多 3 条任务**（PASTE 也可以但音频更贴 DoD）：2 条音频（同语音 hash 变体 × meeting/course）+ 1 条文件名自动推荐证据；不够就先做单测，等额度。

**主任务**：

1. **5 套模板提示词**：新增 `ai/TemplatePrompts`（模板文案集中一处），值固定为 `meeting`（默认）/`course`/`interview`/`podcast`/`shortvideo`。
   三字段 JSON 结构不变（`summary`/`keywords`/`todos`，预算约束沿用 T3.3/T3.6：summary ≤200 字、keywords ≤10、todos ≤20）；
   只改 `summary` 的**结构与关注点**：
   - `meeting`：议题 / 结论 / 待办（**必须与现有提示词逐字节一致**——回归不变量，见【测试】）；
   - `course`：课堂知识点分点（含定义/公式/例题结论）、讲师强调的重点与考点、课后作业/复习要求；
   - `interview`：问答结构（问题 → 回答要点）、受访者核心观点、结论/金句；
   - `podcast`：Show notes（主题、章节要点、提到的资源/人名/作品、可执行启发）；
   - `shortvideo`：核心观点、论据/例子、可执行动作、**是否值得收藏的结论**（K 场景，D-027–D-029 增量）。
   **注入顺序不变**：模板主体 → glossary 注入段（为空时逐字节不变）→ 共用要求（`TodoService.promptSection()` + 严禁编造）→ transcript。
2. **提交时可选**：
   - 音频 multipart：新增**可选**表单字段 `template`；字幕 multipart 同字段；JSON PASTE body 新增可选 `template`（`PasteRequest(String text, String template)`）。
   - 解析：`trim()` + `toLowerCase(Locale.ROOT)`；**非法值 → 400 `INVALID_PARAM`**，message `template 只支持 meeting/course/interview/podcast/shortvideo`（**不静默回落**，US-12 case 4）。
   - 顺手修 T3.7 遗留：`TaskController.upload` 与 `TextTaskService.createSubtitle` 的 `toLowerCase()` 缺 `Locale.ROOT`（土耳其 locale 会坏）。
3. **文件名自动推荐（仅音频 UPLOAD）**：显式 `template` 优先；否则按原文件名小写**包含**关键字匹配，**优先级顺序固定**
   `shortvideo → course → interview → podcast → meeting`，未命中默认 `meeting`；规则表写成命名常量（`TemplateResolver` 独立小类，便于单测）：
   - shortvideo：`抖音` `快手` `小红书` `视频号` `bilibili` `b站` `短视频` `shortvideo`
   - course：`课程` `网课` `讲座` `课堂` `公开课` `lesson` `course` `lecture`
   - interview：`访谈` `面试` `采访` `interview`
   - podcast：`播客` `电台` `podcast`
   - meeting：`会议` `腾讯会议` `例会` `zoom` `飞书` `钉钉` `meeting` `teams`
   - 文本 PASTE / 字幕：**不做自动推荐**（没有有意义的文件名），显式值否则 `meeting`。
   - 日志一行可追溯：`模板：course（来源：文件名）` / `（来源：显式）` / `（来源：默认）`。
4. **落库（零迁移）**：`task_result.template`（列已存在，**不许加迁移**）。**提交事务内**先 `INSERT INTO task_result (task_id, template) VALUES (?, ?)`；
   worker 的 `saveTranscript`（`ON CONFLICT … SET transcript`）与 `StructuringService.persist`（`UPDATE … SET summary/keywords/todos/ai_tier`）**只能更新各自列，不得把 template 覆盖成 NULL**。新任务 `template` 恒非空；T3.7 之前的旧任务保持 NULL。
5. **详情响应加第 12 个键 `template`**：`GET /tasks/{id}` 在 `todos` 之后追加（旧任务 null）。**列表、POST 201、导出 Markdown 的响应/正文一律不变**；错误码不变。
6. **结构化读模板**：`StructuringService.structure(taskId, transcript)` 内部 `SELECT COALESCE(template,'meeting') FROM task_result WHERE task_id=?`（查不到/旧任务→meeting），传给 `StructuringPrompt.build(transcript, glossaryText, template)`；
   **不要给 worker 加参数/加行**（worker 现 286 行 ≤300）。`ai_tier` 级联语义、EXTRACTIVE 行为、DONE 最后写（D-069）不变。
7. **EXTRACTIVE 不做模板分化**（只落 template）：记 F-18，T3.8 评估 spec §6.1"模板摘要"是否必须；本卡不扩范围。

**【契约（预批准，按此写）】**

```text
POST /api/v1/tasks                     multipart/form-data
  file=@会议.mp3
  template=course                      ← 可选；缺省 → 文件名自动推荐 → 默认 meeting

POST /api/v1/tasks                     application/json
  {"text":"……会议记录……", "template":"interview"}   ← template 可省

GET /api/v1/tasks/124
  → 原 11 键 + 末尾 "template": "course" | null（旧任务）

非法 template → 400 {"code":"INVALID_PARAM","message":"template 只支持 meeting/course/interview/podcast/shortvideo"}
```

**约束**：不改 table/迁移/配置键；不改 `content_hash`（幂等键不变，F-17）；不改已有键名与语义；不加新业务码。

**【范围声明】**
- 做：5 套提示词 + 模板解析（显式/文件名推荐/默认）+ 落库 + 详情第 12 键 + 单测（≥12 条）+ 实机取证（≤3 条任务）。
- 不做：前端模板选择器（T4.13）、用户自定义模板（P3）、EXTRACTIVE 模板分化（F-18）、批量换模板重跑（F-17 → T4.1）、导出内显示模板。
- 禁：改已有响应字段语义/键序；加迁移/配置键；改幂等键；动 `docs/`；超 3 条实机任务；push。

**【开工前必读的 3 个坑】**
1. **行数闸会先红**：`web/TaskController` 已 **294 行**（上限 300），加模板参数必撞闸 → **先把导出端点整体抽到 `web/TaskExportController`**（新类，导出行为逐字节不变，T3.7 用例回归），controller 回到 ~230 行再写模板。
   其他预算：`StructuringService` 266、`TaskService` 253、`TextTaskService` 188、`worker` 286 —— 新增逻辑进独立小类（`TemplatePrompts`/`TemplateResolver`），别往大文件堆；allowlist **只许 0 条**。
2. **同音频换模板拿不到两个任务**：幂等键是 `content_hash`（不含 template）→ 同文件会 `reused=true` 返旧任务。实机必须用 **hash 变体样本**（同一段语音复制两份，各用 ffmpeg 前加 10ms 静音或丢 1~3 个采样点改 hash，方法同 K-33/T3.6 的 123）。
3. **`task_result` 早写不能盖掉**：提交时先写 template 行；worker/结构化若用 `INSERT … DO UPDATE SET` 或整行覆盖，可能把 template 写成 NULL → 单测要抓（断言各自 SQL 不含 `template` 列，或断言 persist 参数里没有 template）。

**【验收】**
```powershell
# ① 单测：总数 ≥172（现 160）exit 0；含 meeting 提示词逐字节不变、5 模板互不相同、自动推荐优先级/大小写/未命中、
#    非法 template 400、详情 12 键且旧任务 null、template 不被 worker 覆盖；check-maintainability exit 0（allowlist 0）
# ② 实机（配额重置后，≤3 条任务）：
#    A) 复制 T3.7 用过的短样本 t36-verify.wav 两个 hash 变体；A 命名 "腾讯会议_20261115.wav" 不传 template
#       → 期望详情 template=meeting、日志"来源：文件名"；B 普通命名 + template=course
#       → 期望 template=course；两条 ai_tier=CLOUD，summary 结构不同且各自含模板要素（course 有知识点/考点/复习）
#    C) template=foo → 400（不建任务）；GET /tasks/123 → template=null（旧任务兼容）
# ③ 回归：导出 123 正文与 T3.7 完全一致；详情键集 = 原 11 + template（不多不少）；列表/POST 响应键不变；错误四连不变
# ④ 变异：把 course 提示词换成 meeting 文案 → course 用例红；自动推荐短路成默认 → 推荐用例红；还原 → touch → 绿
# ⑤ 收尾：`.env` 哈希 2BCC440F1C9E；`git diff --name-only` 只含 app/src；1 个 commit；证据行不删；配额记实际值（别编）
```
**通过标准**：5 模板可切换且结构可区分（CLOUD 实测 2 条）；文件名自动推荐生效且可追溯；`task_result.template` 落库、新任务非空、旧任务 null；详情 12 键、其余契约零变化；单测 ≥172 exit 0；可维护性 exit 0；零迁移/零配置键；实机新增 ≤3 任务；证据行不删。

**【可维护性】** 模板文案集中 `TemplatePrompts`（一处改文案）；推荐规则集中 `TemplateResolver`（常量表 + 单测）；导出类抽出后 controller 精简；不引第三方依赖；commit 前 `check-maintainability.ps1` exit 0。

**【回报·测试三问】** 检查了哪些场景（5 模板选择/优先级/非法值/落库/旧任务兼容/键集/回归/变异）｜ 实际命令 + 输出（5 模板各自 summary 前 80 字对比、taskId + template + 来源日志、变异红/绿、exit code）｜ 还有哪些没验证（**不许留空**；EXTRACTIVE 模板分化 F-18、换模板重跑 F-17 可写"留 T3.8/T4.1"）。

**【开工方式（新会话）】** 仓库 `D:\new\projects\meeting-notes` → 读根 `AGENT.md` → `git pull`（确认 HEAD 含 `2027406` 之后的 docs 提交）→ 读本卡 + `docs/项目/录音纪要平台/state.md`/`handoff.md` → 直接开工；**契约不用再报**，先做单测，配额重置后做实机。

## T3.10 格式扩展校验：amr / opus（ass 已由 T3.7 覆盖）（约 1.5h）

> **现状**：`MediaMagic` 已识别 `amr`（`#!AMR`）与 opus（`OggS` → `ogg`）；容器 ffmpeg 我已预跑实测能解码
> （amr → 60.02s、opus → 59.999s 的 16k 单声道 wav）。但 **W3 验收要求"每种格式各传一个真实样本 → DONE"，目前 amr/opus 没有实机证据**；
> 失败提示也没有 spec §4 验证命令要求的"请转 mp3"兜底指引。
>
> ✅ 样本已备（同一段真实语音 60s 转码，hash 与既有任务不同）：
> `D:\new\artifacts\t310-amr.amr`、`D:\new\artifacts\t310-opus.opus`。
>
> ⚠️ **配额**：本卡只许 **2 条实机任务**（amr + opus，各约 1 分钟），必须等次日 00:00 配额重置后再跑。
> 今日 50/50 已满；不得为验证另建任务，也不得把失败样本偷偷转码绕过（失败就停并报卡点）。

**主任务**：

1. **实机 amr + opus 各 1 条**：`POST /api/v1/tasks` 上传 → 轮询到 `DONE`；记录 taskId、耗时、`duration_sec`≈60、
   `ai_tier`（期望 CLOUD）、`transcript` 非空且前 60 字可读。两条都必须走**现有音频路径**（不得改 worker/上传管线）。
2. **失败提示兜底（spec §4 验证命令）**：`TaskService` 的 `INVALID_MEDIA` message 增加一句"若仍失败，请先把音频转成 mp3 再上传"
   （**只改文案、错误码不变**；`INVALID_MEDIA` 不是码表变更）。grep 既有测试对旧文案的断言并同步更新，**不许删断言**。
3. **单测**：补/确认 `MediaMagicTest` 覆盖 amr 与 opus（`OggS`）；新增 1 条"未知容器 → `MediaMagic.detect` 返回 null"（若无）；
   新增 1 条文案断言（未知容器 → 400 且 message 含 `转成 mp3`）。
4. **ass 不重做**：T3.7 已覆盖（解析单测 8 条 + 实机"解析成功→429"等价证据）；本卡不扩新格式（vtt/txt 不排）。

**【范围声明】**
- 做：amr/opus 两条实机 + 失败文案兜底 + 相关单测。
- 不做：新格式（vtt/txt/amr-wb 等）、容器 ffmpeg 解码器变更（已实测可解）、上传/worker 管线改动、表结构/配置键/端点契约。
- 禁：为凑 DONE 转码绕过、改断言/编数字、动 `docs/`、超 2 条实机任务、push、删证据行。

**【验收】**
```powershell
# ① 实机（配额重置后，2 条）
curl.exe -s --noproxy "*" -X POST http://localhost:8080/api/v1/tasks -F "file=@D:\new\artifacts\t310-amr.amr"
curl.exe -s --noproxy "*" -X POST http://localhost:8080/api/v1/tasks -F "file=@D:\new\artifacts\t310-opus.opus"
# 轮询到 DONE，贴 taskId + 耗时 + ai_tier + transcript 前 60 字 + duration_sec
# ② 不支持格式：造一个未识别容器（如随机字节 .xyz）→ 400 INVALID_MEDIA，message 含 "转成 mp3"
# ③ 回归：正常 wav/mp3 路径用既有证据行说明；全量 ≥185 exit 0；check-maintainability exit 0
# ④ 变异：把 message 里 "转成 mp3" 提示删掉 → 文案用例红；还原 → touch → 绿（K-30）
# ⑤ 收尾：.env 哈希 2BCC440F1C9E；git diff --name-only 只含 app/src；单 commit；证据行不删；不 push
```
**通过标准**：amr/opus 各 1 条 `DONE` 且 transcript 非空；未知容器提示含"转成 mp3"；全量 ≥185 exit 0；可维护性 exit 0；
零表结构/配置键/端点契约改动；实机新增 ≤2 任务；证据行不删。

**【可维护性】** 文案只改一处（`TaskService`）；不新增类/依赖；commit 前 `check-maintainability.ps1` exit 0。

**【回报·测试三问】** 检查了哪些场景（amr/opus 实机、未知容器文案、回归、变异）｜ 实际命令 + 输出（两条 taskId + DONE + ai_tier + transcript 摘录、400 响应原文、变异红/绿、exit code）｜
还有哪些没验证（**不许留空**；amr-wb / 手机真机导出的私有变体、长 amr 未测等要写清）。

**【开工方式（新会话）】** 仓库 `D:\new\projects\meeting-notes` → 读根 `AGENT.md` → `git status` 干净（HEAD 应含 `f880b6a` T3.9 代码，**别回退/重写 T3.9**）→
读本卡 + `docs/项目/录音纪要平台/state.md`/`handoff.md` → 先做文案 + 单测（不等配额），配额重置后再跑 2 条实机。

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
