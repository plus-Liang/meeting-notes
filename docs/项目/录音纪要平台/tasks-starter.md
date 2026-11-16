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

## T3.9 摘要模板机制（5 模板，US-12）（约 3h，含 shortvideo +0.5h） ✅ **已完成（`f880b6a`；我独立复跑 185/185、可维护性 exit 0、两处变异红→绿；零配额实机全过；实机 129 自动 meeting + 130 显式 course + 131 PASTE interview：同段语音结构明显不同、template 落库、ai_tier=CLOUD；见 `review/2026-11-14-T3.9验收.md`）**

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

## T3.10 格式扩展校验：amr / opus（ass 已由 T3.7 覆盖）（约 1.5h） ✅ **已完成（`0abbe0e`；187/187 exit 0、可维护性 exit 0、我独立变异红→绿；实机 amr=132（62s/96038B/CLOUD）/opus=133（61s/244460B/CLOUD）DONE；未知容器 400 新文案实测零任务；配额 8 分钟/5 任务复算吻合；见 `review/2026-11-15-T3.10验收.md`）**

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

## T3.8 第 3 周验收（W3 收口：测试补强 + 56.7min 长音频 + 真 120s 超时 + 遗留评估）（约 4h）✅ **已完成（`3b80f02`；我复跑 192/192 + 可维护性 exit 0 + 三向变异；134 长音频 361s/RTF0.106、135 真超时 2s、136/137 F-14、138 我重放超时；W3 门禁关闭，D-089）**

> **现状**：W3 功能任务 T3.0~T3.11 已全 ✅（T3.7 导出/PASTE、T3.9 模板、T3.10 amr/opus 均已验收）；本卡是**周验收收口**：
> 补测试缺口 → 用真实长音频跑通 → 实测 LLM 真超时降级 → 把 F-14~F-19 逐条评估落结论。
> 配额：2026-11-15 已用 **8 分钟 / 5 任务**；本卡实机**最多 4 条任务**：①长音频 1 条（56.7min → 57 分钟配额）
> ②真超时实验 PASTE 1 条（0 分钟）③F-14 实验 PASTE 1~2 条（0 分钟）→ 合计 ≤57 分钟 / ≤9 任务，安全。
> ⚠️ **不改 `.env` 持久值**：打超时/本地档预算实验一律用「外部 override 文件 + `docker compose -f` 临时叠加」，验完恢复基础 compose。

**主任务**：

1. **补测试缺口（只补真缺口，不重复覆盖）**：
   - Schema 畸形矩阵：`todos` 元素缺 `action`/类型错、`keywords` 元素非字符串、`summary` 非字符串/超长、JSON 被截断、围栏/散文包裹（`JsonExtractor` 已盖的写"已覆盖"）→ 期望统一"三字段一起留空 + 降级，DONE 不失败"。
   - 三档选择：补 1 条 **LOCAL 调用抛异常 → 降级 EXTRACTIVE 且 `ai_tier=EXTRACTIVE`**（mock ChatModel throw，断言不重试同一档）；OFFLINE_MODE 已有 WARN 断言则记"已覆盖"。
   - 模板选择：显式/文件名/默认/旧任务 null/详情 12 键已有（T3.9）→ 若缺"非法 template 在 PASTE 路径"补 1 条。
   - T3.9 遗留：`StructuringServiceTest` 的 `never()).update(anyString(), any(Object[].class))` 按 K-31 可能空过 → 改成可靠断言（捕获实际调用数/`verifyNoMoreInteractions` 配合 stub），确保"失败路径零写库"不是假绿。
2. **56.7min 长音频实机（1 条）**：样本 `D:\datasets\meeting-notes-samples\a_1113b.wav`（56.7min / 103.8MB，<60min/<200MB 内）。
   验：`DONE`、`duration_sec`≈3402、transcript 非空且长度合理、`ai_tier`、`summary/keywords/todos` 正常、**总墙钟耗时 + ASR RTF**、`docker stats` 期间 app 内存峰值（不 OOM）、DONE 最后写。
3. **LLM 真 120s 超时（实机，1 条 PASTE）**：写 override 文件（放 `D:\new\artifacts\t38-override.yml`，**不进仓库**）给 app 加：`LLM_TIMEOUT_SEC=2`、`LLM_BASE_URL=http://10.255.255.1:81`（黑洞地址）、`LLM_API_KEY=dummy`、`OFFLINE_MODE=false`；
   `docker compose -f docker-compose.yml -f D:\new\artifacts\t38-override.yml up -d app` → POST 一段 PASTE 文本 → 期望 ~2s 后 **ReadTimeout → 降级**、WARN 带 `timed out`、任务仍 `DONE`、`ai_tier=EXTRACTIVE`；
   验完 `docker compose up -d app` 恢复基础配置，确认 `.env` 哈希 `2BCC440F1C9E` 不变、6 容器 healthy。
4. **F-14 本地档实验（≤2 条 PASTE，0 分钟配额）**：同样用 override（`OLLAMA_ENABLED=true`、`LLM_LOCAL_MAX_TOKENS=1024`，仍截断再试 2048）跑 1 条 PASTE 短文本，记录 `ai_tier` 与 WARN（`finish_reason=length` / 超时）：成功出 `ai_tier=LOCAL` → 记录可行预算；仍失败 → 结论"v1 记已知限制（零成本 LOCAL 档不可用，EXTRACTIVE 兜底）"，**不改 `.env`**、本卡不改代码。
5. **遗留逐条评估并落结论**（给"结论 + 依据 + 排期/不修理由"，写进回报；我复核后并入 decisions）：
   **F-15**（EXTRACTIVE 不读 glossary：评估确定性替换成本，>30 行或要改公共面 → 不修，记 T4.x）· **F-16**（字幕编码/纯数字行边界：不修，写进文档）· **F-17**（幂等键不含模板 → 留 T4.1 重跑）· **F-18**（EXTRACTIVE 模板分化：spec §6.1 是否必须？给判据与建议）· **F-19**（模板只读查询：P3 不修）· 本机 C 盘存储纪律执行情况（样本/日志/override 是否都在 D 盘）。
6. **W3 过关材料汇总**（放进回报，我据此写周验收报告）：US-3 Independent Test（task 122/123）· N15 三档证据（95/96/97/98/100/101/120/121 + 本轮长音频/超时）· US-12 证据（129/130/131）· 错误四连/详情 12 键/导出回归。

**【范围声明】**
- 做：补测试缺口 + 2~4 条实机（长音频/超时/可选 F-14）+ 遗留评估报告。
- 不做：新功能（F-15/F-18 若评估要改代码，先报我批准；本卡默认不改）；不改 `.env`/配额/表结构/公共契约；不删证据行；不动 `docs/`；不 push。
- **存储纪律（2026-11-15 起，新）**：所有样本/日志/override/临时数据放 **D 盘**（`D:\new\artifacts\` 或 `D:\cache\`），不许写 `C:\Users\ljh` 下的大文件。

**【验收】**
```powershell
# ① 单测：全量 ≥189 exit 0（现 187 + 新增 ≥2）；check-maintainability exit 0（allowlist 0）
# ② 长音频实机（1 条）：POST a_1113b.wav → DONE，贴 taskId/耗时/duration_sec/ai_tier/transcript 前 80 字/内存峰值
# ③ 真超时实机（1 条 PASTE）：贴降级 WARN 原文 + 最终 DONE + ai_tier；恢复 compose 后 .env 哈希不变、6 容器 healthy
# ④ F-14 实验：贴 override 关键行 + ai_tier/WARN；结论一句话
# ⑤ 变异：把"LOCAL 异常→降级"用例的降级断言改坏 → 红 → 还原 → 绿（至少 1 处）
# ⑥ 收尾：git diff --name-only 只含 app/src；单 commit；证据行不删；配额记实际值（我的 8 分钟 + 本卡 57 分钟 = 65/120 上限内）
```
**通过标准**：长音频 `DONE` 且耗时/内存有数；真超时能降级且任务不失败；遗留逐条有结论；全量 ≥189 exit 0；可维护性 exit 0；零 `.env`/表结构/契约改动；实机 ≤4 任务；证据行不删。

**【可维护性】** 测试集中在既有测试类/新小类；不引依赖；commit 前 `check-maintainability.ps1` exit 0；override/日志不进仓库。

**【回报·测试三问】** 检查了哪些场景（畸形 JSON 矩阵/三档降级/模板/长音频/真超时/F-14）｜ 实际命令 + 输出（taskId + 耗时 + ai_tier + transcript 摘录 + WARN 原文 + 内存峰值 + 变异红绿 + exit code）｜ 还有哪些没验证（**不许留空**）。

**【开工方式（新会话）】** 仓库 `D:\new\projects\meeting-notes` → 读根 `AGENT.md` → `git status` 干净（HEAD 应含 `0abbe0e` + 本轮 docs 提交）→ 读本卡 + `state.md`/`handoff.md` → 先补测试，再跑实机。

---

## T4.1 列表筛选 + 批量重试 + 设置页 + **F-15 抽取式读词表**（约 3h；W4 首卡）✅ **已完成（`690b644`；我复跑 204/204 + 可维护性 exit 0 + 变异红→绿；139 造 DEAD→retry→3s DONE、140 F-15 术语替换 4 处；D-090/第 25 份报告）**

> **现状**：W3 全 ✅（HEAD 应为 `77b85e4`，含 T3.8 验收 docs；代码 `3b80f02`）。列表**后端**已支持 `?status=` 过滤（T2.10/TaskController），
> 但前端没有筛选 UI、没有批量重试、没有设置页；`glossary` 有 2 条真实词条（`工作副盘→工作复盘`、`AirCoding→AI Coding`），
> **EXTRACTIVE 档不读词表**（F-15，已在 D-089 批准实现，≤30 行、不改公共面）。
> **配额**：2026-11-15 已用 65 分钟 / 10 任务；本卡实机预计 **≤4 条任务、≤10 分钟**（见【验收】，先做代码+单测，实机再花配额）。
> ⚠️ 不改 `.env` 持久值做实验（超时/降档一类用外部 override，D-070）；**每组实验一结束立刻存日志**（K-39/D-089）。

**主任务**：

1. **列表筛选 UI（前端，无新后端）**：`tasks.html` + `app.js` 顶部加状态筛选（全部 / PENDING+RUNNING+RETRYING=进行中 / DONE / DEAD=失败），
   点击即带 `?status=` 重新拉第一页；分页继续用 `next_before_id` 游标（**不许改回 OFFSET**，D-053）。筛选与"加载更多"要能共存，切换筛选要重置游标。
2. **批量重试（后端 + 前端）**：新端点 `POST /api/v1/tasks/retry`，body `{"ids":[1,2,3]}`（1..20 个，去重）；
   - 只处理 **DEAD**：复用现有"DEAD 重生"路径（重入队 Redis + 状态迁移与 re-upload 复活**同一口径**，先读 `TaskService`/`TaskMutationService` 再动手，**不许新造状态语义**）；
   - 非 DEAD / 不存在 / 非本人：**跳过并在响应里列出原因**，不报错整体失败；响应 `{"retried":[ids],"skipped":[{"id":x,"reason":"NOT_DEAD"|"NOT_FOUND"}]}`（snake_case，D-041）；
   - 非法 body（空数组 / >20 / 非数字）→ **400 `INVALID_PARAM`**；配额不重复扣（重试不消费额度，与"重传复活"一致）。
   - 前端：列表筛选=DEAD 时出现"全选 / 批量重试"，显示 `retried/skipped` 结果。
3. **设置页 `settings.html`**（只读展示为主，读 DB 现成数据，不新增可写配置）：
   - **配额**：今日已用分钟/上限、已用任务数/上限、剩余（口径与 `QuotaService` 一致；读 `quota` 表 + `app.quota.*`）；
   - **当前 AI 档位**：首选档 + 原因（复用 `AiStructuringRouter` 的判定日志口径，如 `CLOUD/LOCAL/EXTRACTIVE` + 三条件 true/false）；**不许另写一套判定逻辑**（单一来源）；
   - **术语表**：新增只读端点 `GET /api/v1/glossary`（返回 `wrong_text/right_text/hits/priority`，按 hits desc 取 ≤100），页面表格展示；
   - 页面无 JS 报错、无内联密钥、移动端可读。
4. **F-15（EXTRACTIVE 读词表，≤30 行，不改公共契约）**：EXTRACTIVE 产出后，用 glossary 的 `wrong_text → right_text` 做**确定性替换**
   （summary / keywords / todos.action 三个字段；词表为空时输出**逐字节不变**）；实现放 `StructuringService`（或紧邻的小方法），
   **不新表、不改 API 字段、不改三档选择逻辑**；日志只记"术语替换 N 处"（不泄露词条，沿用 T3.6 口径）。

**【范围声明】**
- 做：前端筛选/批量重试/设置页 + 后端 `POST /tasks/retry` + `GET /glossary` + F-15 + 对应单测。
- 不做：不改状态机语义/表结构/Flyway/错误码表；不动 `.env`/compose/健康检查；不碰导出/详情契约（12 键不动）；不写 `docs/`；不 push。
- 存储纪律：样本/日志/override 一律 `D:\new\artifacts\`，不写 `C:\Users\ljh`。

**【验收】（反例必须进）**
```powershell
# ① 单测：全量 ≥196 exit 0（现 192 + 新增 ≥4）；check-maintainability exit 0（allowlist 0，新增文件 ≤300 行）
# ② 列表筛选（真 HTTP）：status=DEAD / status=DONE / status=ABC(400) / limit=0(400)；筛选结果与 DB 计数一致
# ③ 批量重试实机（≤2 条任务）：
#    a) 造 DEAD：docker compose stop asr → 上传 D:\datasets\meeting-notes-samples 里的短视频样本（≤10s）
#       → 等终态 DEAD（记录 taskId、error_code）
#    b) docker compose start asr（等 healthy）→ POST /api/v1/tasks/retry {"ids":[<deadId>]} → retried=[id]
#       → 轮询到 DONE、transcript 非空；附带测：对同 id 再重试 → skipped=NOT_DEAD；对不存在 id → skipped=NOT_FOUND；
#       混入 1 个 DONE id → 只重试 DEAD、DONE 进 skipped
# ④ F-15 实机（1 条 PASTE，0 分钟）：override 指向静默监听容器 + LLM_TIMEOUT_SEC=2（同 T3.8 手法，override 放 artifacts）
#    → PASTE 一段含"工作副盘"的文本 → 期望 ai_tier=EXTRACTIVE 且 summary/keywords 中**不含"工作副盘"、含"工作复盘"**
#    → 验完 docker compose up -d app 恢复基础配置、.env 哈希 2BCC440F1C9E、5 服务 healthy
# ⑤ 设置页：浏览器打开 /settings（或截图 + curl 三个数据源）→ 配额数字与 SQL 一致、首选档与启动日志一致、术语表 2 条
# ⑥ 变异（至少 1 处）：把 F-15 的替换调用注释掉 → 单测红 → 还原 → touch → 绿（K-30）
# ⑦ 收尾：git diff --name-only 只含 app/src（前端静态资源也在 app/src/main/resources/static）；单 commit；证据行不删；不 push
```
**通过标准**：筛选/分页共存且反例 400 正确；批量重试只动 DEAD、`retried/skipped` 语义准、重试后真的 DONE；设置页三块数据与 SQL/日志一致；
F-15 单测 + 实机都证明"EXTRACTIVE 输出被词表纠正"且空词表逐字节不变；全量 ≥196 exit 0；可维护性 exit 0；零表结构/契约/`.env` 改动。

**【可维护性】** 新端点走 `TaskController`（薄）+ 服务类；设置页数据复用 `QuotaService`/`AiStructuringRouter` 口径，**禁止复制判定逻辑**；
F-15 控制在 ≤30 行并集中一处；新增静态资源不引第三方库（原生 JS）；commit 前 `check-maintainability.ps1` exit 0。

**【回报·测试三问】** 检查了哪些场景（筛选/分页/批量重试正反例/设置页/F-15）｜ 实际命令 + 输出（HTTP 码、taskId、retried/skipped JSON、EXTRACTIVE 摘要片段、DQ 数字、变异红绿、exit code）｜
还有哪些没验证（**不许留空**；如：移动端真机未验、并发重试同一 id 未测等要写清）。

**【开工方式（新会话）】** 仓库 `D:\new\projects\meeting-notes` → 读根 `AGENT.md` → `git status` 干净（HEAD 应含 `77b85e4`，**别回退/重写 T3.8**）→
读本卡 + `docs/项目/录音纪要平台/state.md`/`handoff.md` → 先读 `TaskService` 的 DEAD 复活路径再动重试端点 → 代码+单测先做完，再花配额跑实机。

---

## T4.2 端到端自动化测试（N14）（约 2h；W4 第 2 张）✅ **已完成（`e9649d2`；我复跑 209/209 + -UnitOnly 202/202 + 单跑 5/5；停栈反例红、导出变异精确红；D-091/第 26 份报告）**

> **现状**：全量 **204/204**，但两个冒烟只覆盖单点（ASR 直连、LLM 连通）；**没有一条"提交 → 处理 → 查询 → 导出 → 重试 → 错误语义"的端到端测试**。
> N14 的 DoD 是"一条命令跑通"。本卡把它补上，并纳入全量套件。
> **配额**：2026-11-15 已用 66 分钟 / 12 任务；E2E 用 **PASTE（0 分钟）**，每跑一次全量会新建 1 条任务（唯一文本，避免幂等命中）→ 本卡预算 **≤6 任务 / 0 分钟**。

**主任务**：

1. **新增 `app/src/test/java/com/meetingnotes/e2e/E2ETest.java`**（≤200 行，JUnit5 + `java.net.http`，**不引新依赖**）：
   - 基址 `E2E_BASE_URL` → 回退 `APP_BASE_URL` → 默认 `http://localhost:8080`；只拼相对路径，**不硬编码端口**（D-066 纪律）。
   - **前置探活**：`GET /actuator/health` 非 200 → `fail("端到端需要基础栈（5 服务 healthy）…")`，**不许 skip 假装通过**（假绿灯红线）。
   - **场景 A 主链路（PASTE）**：唯一文本（UUID 后缀）→ `POST /api/v1/tasks` → 断言 **201**、`task_id`>0、`status=PENDING`、`content_hash` 64 位、`reused=false`；
     轮询详情（间隔 500ms、超时 60s，超时要打印最后状态）→ `DONE`；断言**详情 12 键固定集合**、`transcript` 与输入一致（trim）、`summary` 非空、
     `keywords`/`todos` 是数组、`ai_tier ∈ {CLOUD,LOCAL,EXTRACTIVE}`、`template=meeting`、`error_code=null`。
   - **场景 B 幂等（D-055）**：同文本再 POST → `reused=true` 且 `task_id` 与 A 相同（不新建任务）。
   - **场景 C 导出（T3.7）**：`GET /tasks/{id}/export?format=md` → 200、`Content-Type: text/markdown`、三节顺序 `## 摘要` → `## 关键词` → `## 待办`；`format=pdf` → 400。
   - **场景 D 批量重试语义（T4.1 回归）**：`POST /tasks/retry {"ids":[<id>]}` → `retried=[]` 且 `skipped[0].reason="NOT_DEAD"`。
   - **场景 E 错误语义**：JSON 提交 `template=xxx` → 400 且 message 逐字；`Content-Type: text/plain` → 415。
2. **`scripts/test.ps1`**：`-UnitOnly` 排除模式加 `,!E2ETest`（否则无栈跑单元会红）；全量模式自动纳入；文件头注释与用法同步更新。
3. **不做**：不引 RestAssured/Testcontainers（新依赖=供应链与时间风险）；不做音频端到端（ASR 冒烟已覆盖，音频场景验证在 T4.4/T4.5）；不改主代码/契约/状态机。

**【范围声明】**
- 做：E2E 测试类 + `scripts/test.ps1` 排除项 + 注释；配额内跑实机。
- 允许改动文件：`app/src/test/**` + `scripts/test.ps1`（**本卡唯一允许碰 scripts/ 的地方**，因为入口要加排除项）。
- 禁：改 `.env`/compose/表结构/错误码表/主代码；删证据行（D-073）；为了绿而 skip/降低断言（红线）；写 `docs/`；push。
- 存储纪律：日志/临时一律 `D:\new\artifacts\`。

**【验收】（反例必须进）**
```powershell
# ① 全量：powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test.ps1 → 用例数 ≥206，mvn exit 0
# ② 纯单元：scripts\test.ps1 -UnitOnly → 不含 E2ETest、exit 0（证明排除项生效）
# ③ 单跑：mvn -B test "-Dtest=E2ETest" → BUILD SUCCESS；输出/报告里有 taskId 与各场景断言
# ④ 反例（必须有）：docker compose stop app → 跑 E2ETest → 明确失败（贴报错原文），不许 skip/绿
# ⑤ 变异（主代码，验 E2E 真能抓回归）：把 TaskMarkdownExporter 三节顺序对调 → E2ETest 红 → 还原 → touch → 绿（K-30）
# ⑥ 收尾：git diff --name-only 只含 app/src/test 与 scripts/test.ps1；单 commit；不 push；证据行不删；配额 ≤6 任务/0 分钟
```
**通过标准**：一条命令跑通全部 5 场景且纳入全量套件；无静默 skip；反例 ④ 与变异 ⑤ 都造过红；全量 exit 0、可维护性 exit 0；零主代码/契约/`.env` 改动。

**【可维护性】** 单文件 ≤200 行；用 JDK 自带 HTTP 客户端 + Jackson（既有依赖）；断言消息带 taskId 与关键字段，失败可直接定位；commit 前 `check-maintainability.ps1` exit 0。

**【回报·测试三问】** 检查了哪些场景（A~E + 反例 + 变异）｜ 实际命令 + 输出（用例数前后、E2E taskId、轮询耗时、三节原文、400/415 原文、反例报错、变异红绿、exit code）｜ 还有哪些没验证（**不许留空**）。

**【开工方式（新会话）】** 仓库 `D:\new\projects\meeting-notes` → 读根 `AGENT.md` → `git status` 干净（HEAD `49691f6`，**别回退/重写 T4.1**）→ 读本卡 + `state.md`/`handoff.md` → 先写 E2E 类与 test.ps1 排除项，再跑全量。

---

## T4.4 零成本验证（N15）（约 1.5h；W4 第 3 张）✅ **已完成（无 commit，零代码改动只交证据；我重放零 key E2E 5/5 - task 162 EXTRACTIVE、重放反例启动崩、复跑 209/209；D-092/第 27 份报告）**

> **要证明的事**：**一个不填任何 API key 的人，也能把 US-1 主故事走完**（上传音频 → 转写 → 摘要 → 待办 → 导出），
> 只是质量降级到第③档抽取式（`ai_tier=EXTRACTIVE`）。这是 D-003R/D-020/D-075 的核心承诺，之前只有单测与 PASTE 验证过，**没有真音频零 key 全链路证据**。
> **纪律**：**不许改 `.env` 持久值**（D-070/K-35）——用外部 override 临时覆盖，验完 `docker compose up -d` 恢复 + 哈希校验。
> **配额**：2026-11-15 已用 66 分钟 / 20 任务；本卡预算 **≤3 任务 / ≤3 分钟**（1 条真音频 + 1~2 条 E2E PASTE）。

**主任务**：

1. **准备零 key override**（放 `D:\new\artifacts\t44-nokey.yml`，**不进仓库**；用**文件工具**写，写完 `docker compose config --quiet` 预检，K-41）：
   ```yaml
   services:
     app:
       environment:
         LLM_API_KEY: ""
         LLM_BASE_URL: ""
         LLM_MODEL: ""
         LLM_ENABLED: "false"
         OLLAMA_ENABLED: "false"
         OFFLINE_MODE: "false"     # 不给云 key，也不靠离线模式兜——纯"无 key 也能跑"
   ```
   `docker compose -f docker-compose.yml -f D:\new\artifacts\t44-nokey.yml up -d app` → 等 healthy。
   **生效判据（不看 `/actuator/health` 的 200，K-41）**：启动日志里首选档判定必须出现 **`首选 EXTRACTIVE`**（三条件全 false），
   `docker inspect` 核到 4 个环境变量确实为空/false，`GET /api/v1/settings` 的 `ai_tier.preferred` = `EXTRACTIVE`。
2. **真音频全链路（1 条，≤59s 样本）**：选一个**没用过的短样本**（或按 K-33 造 hash 变体）上传 → 轮询到 `DONE`：
   - 断言：`ai_tier=EXTRACTIVE`、`transcript` 非空、`summary` 非空、`keywords` 数组 **≥5**（T3.4 的抽取式质量线）、
     `todos` 数组（**≥1** 或用"确实没有待办"的样本并在回报里说明判据）、`template` 落库、`error_code=null`；
   - `GET /tasks/{id}/export?format=md` → 200 且三节齐全（US-1 最后一步）；
   - 日志证据：**没有 CLOUD 调用**（无 `首选档：CLOUD`、无云档 WARN/异常），有 EXTRACTIVE 落库行。
3. **E2E 在零 key 环境下跑一遍**（0 分钟）：`mvn -B test "-Dtest=E2ETest"` → 5/5 绿（这同时补上 T4.2 验收里"E2E 未在纯 EXTRACTIVE 路径实机走过"的缺口，需断言 `ai_tier=EXTRACTIVE` 的 A 场景实际命中）。
4. **恢复**：`docker compose up -d app`（基础配置）→ 5 服务 healthy、`.env` 哈希 `2BCC440F1C9E`、`GET /settings` 回到 `preferred=CLOUD`、
   全量 `scripts\test.ps1` 209/209 exit 0、`check-maintainability` exit 0。
5. **文档**：把"零成本模式下故事怎么走、质量差在哪、怎么切回云"写进 `接入指南.md`（**只写实测事实**，不写承诺）。

**【范围声明】**
- 做：override 实验 + 1 条真音频 + E2E 零 key 跑 + 恢复验证 + `接入指南` 补一段"零成本模式"（文档在验收时同步，写码方只需给事实）。
- 不做：改 `.env`/compose 基值/主代码；不试 LOCAL 档（F-14 已定案不可用，D-089）；不做离线断网（那是 T4.5）；不删证据行。
- 存储纪律：override/日志/样本全在 `D:\new\artifacts\`。

**【验收】（反例必须进）**
```powershell
# ① override 生效：启动日志"首选档 EXTRACTIVE" + inspect 四键为空/false + /settings preferred=EXTRACTIVE
# ② 真音频：taskId/DONE/ai_tier=EXTRACTIVE/transcript 非空/summary 非空/keywords>=5/todos 判据/export 三节
# ③ 全链路日志无 CLOUD 迹象（grep 云配置/WARN），有 EXTRACTIVE 落库行
# ④ E2E 零 key：5/5 绿，A 场景实际 ai_tier=EXTRACTIVE
# ⑤ 反例：故意把 override 写成"空 key 但 LLM_ENABLED=true" → 启动日志/`/settings` 必须暴露矛盾（不许静默当 CLOUD）
# ⑥ 恢复：基础 compose 5 healthy、.env hash 2BCC440F1C9E、preferred=CLOUD、全量 209/209、可维护性 exit 0
# ⑦ 收尾：单 commit（只含 app/src 若有小改，或零代码改动时就只交证据）；不 push；证据行不删；配额 ≤3 任务/≤3 分钟
```
**通过标准**：零 key 下真音频走出 **DONE + EXTRACTIVE + 可用结果 + 导出可用**；有"无 CLOUD 调用"的日志证据；E2E 在零 key 下绿；
恢复后一切回到基线；`.env` 哈希守恒；反例⑤能暴露矛盾配置。

**【可维护性】** 大概率零代码改动；若需改文档只改 `接入指南.md` 一段；不新增配置键；不引依赖。

**【回报·测试三问】** 检查了哪些场景（零 key 启动/真音频全链路/E2E 零 key/恢复/反例）｜ 实际命令 + 输出（override 关键行、启动日志首选档、taskId + 各字段、export 三节摘录、E2E 用例数、`.env` 哈希、exit code）｜ 还有哪些没验证（**不许留空**）。

**【开工方式（新会话）】** 仓库 `D:\new\projects\meeting-notes` → 读根 `AGENT.md` → `git status` 干净（HEAD `e9649d2` + 本次 docs 提交，**别回退/重写 T4.1/T4.2**）→ 读本卡 + `state.md`/`handoff.md` → 先写 override 并验证生效判据，再跑真音频。

---

## T4.5 离线验证（US-9 / N16）（约 1.5h；W4 第 4 张）✅ **已完成（无 commit；真断网 + 真 key → 门禁屏蔽、task 166 DONE/EXTRACTIVE/零出站；反例 167 级联降级实证；209/209；D-093/第 28 份报告）**

> **要证明的事**：**把外网真的断掉（不是只清 key），US-1 主故事仍然能走完，且全程没有任何出站连接**。
> 同时验证 **D-077 的离线硬门禁**：即使 `.env` 里配着真实云 key，`OFFLINE_MODE=true` 也必须把云档硬屏蔽 → 实际档位 ≠ CLOUD。
> **与本卡的区别**：T4.4 证明"没 key 也能跑"（配置层）；本卡证明"没网也能跑"（网络层）——两件事，不能互相替代。
> **纪律**：不改 `.env` 持久值（D-070/K-35）；override 叠加 + 验完恢复 + 哈希校验；**每组实验结束立刻落盘日志**（K-39，尤其 `down` 会销毁容器日志）。

**配额**：2026-11-15 已用 **67/120 分钟、25/50 任务**；本卡预算 **≤2 任务 / ≤2 分钟**（1 条 30s 真音频；可选反例 +1 任务）。

**前置事实（Lead 已实测，照抄即可，别重复踩）**：
- `networks.default.internal: true` 叠加后：**必须 `docker compose -f … down` 再 `up -d`**；只 `up -d` 会留下 endpoint invalid IP → app `UnknownHostException: postgres` 起不来（K-44）。
- 内网下：5 服务仍 healthy（asr 模型是宿主目录只读挂载 + `HF_HUB_OFFLINE=1`，不需要外网）；`/proc/net/route` **无默认路由**；
  容器内 `curl https://api.deepseek.com` → `Could not resolve host`；**宿主访问 8080 = 000**（内部网络不发布端口）→ **API 一律在容器内 `docker compose exec -T app curl …`**（app 镜像自带 curl）。
- **别用** `com.docker.network.bridge.enable_ip_masquerade=false`：实测仍能出网（不是断网）。

**主任务**：

1. **写 override**（`D:\new\artifacts\t45-offline.yml`，**不进仓库**，用**文件工具**写，写完 `docker compose config --quiet` 预检，K-41）：
   ```yaml
   networks:
     default:
       internal: true
   services:
     app:
       environment:
         OFFLINE_MODE: "true"
   ```
   **`LLM_*` 三件套保持 `.env` 真值不动**——这样 `/settings` 里 `cloud_configured=true`，才能证明是门禁在拦，而不是"没 key 所以没云"。
2. **切换并等健康**：`docker compose -f docker-compose.yml -f D:\new\artifacts\t45-offline.yml down`（**不加 `-v`**，卷保留）→ 同一组 `-f … up -d` → 等 **5 服务 healthy**。
3. **生效判据（三条都要，不许只看 health）**：
   - `docker compose exec -T app curl -s http://localhost:8080/api/v1/settings` → **`preferred=EXTRACTIVE` + `cloud_configured=true` + `offline_mode=true`**；
   - 启动日志有 D-077 的**离线门禁 WARN**（贴原文，别只写"有 WARN"）；
   - `docker compose exec -T app cat /proc/net/route` **无默认路由** + `docker compose exec -T app curl -sS --max-time 5 -o /dev/null https://api.deepseek.com` **必须失败**（贴原文与 exit code）。
4. **US-1 全链路（全部在容器内）**：
   - 造一条 ≤59s 的**新样本**（未用过，或按 K-33 造 hash 变体）：容器内 PyAV 截取（镜像里没有 ffmpeg 可执行，T4.4 已验证可行），或宿主样本 `docker cp` 进容器；
   - `docker cp <样本> meeting-notes-app-1:/tmp/t45-offline.wav` →
     `docker compose exec -T app sh -c 'curl -s -H "Authorization: Bearer $API_TOKEN" -F "file=@/tmp/t45-offline.wav" http://localhost:8080/api/v1/tasks'` → 拿 `task_id`；
   - 轮询 detail 到 `DONE`（≤90s），断言：**`ai_tier≠CLOUD`（应为 EXTRACTIVE）**、transcript 非空、summary 非空、keywords ≥5、`error_code=null`、`template` 落库；
   - `GET /tasks/{id}/export?format=md` 容器内 → **200** 且摘要/关键词/待办三节齐全；
   - 日志：有 `结构化首选档：EXTRACTIVE` + 离线门禁 WARN + 落库行；**没有任何云档调用/超时重试**。
5. **（可选，预算允许就做）反例**：在**同一 internal 网络**里把 `OFFLINE_MODE` 改回 `false` → 首选档会判 CLOUD，但外网不可达 → 云档调用失败 → **自动降级 EXTRACTIVE 且任务仍 DONE**（证明"断网时云档失败会降级，不是卡死"）。
   代价 +1 任务；若不做，必须在回报"没验证"里写明原因。
6. **恢复基线**：先把日志落盘（K-39）→ `docker compose -f … down`（**不加 `-v`**）→ `docker compose up -d`（基础配置）→
   5 服务 healthy、`/settings` 回到 `preferred=CLOUD` + `offline_mode=false`、`.env` SHA256 前 12 = `2BCC440F1C9E`、
   全量 `powershell -ExecutionPolicy Bypass -File scripts\test.ps1` **209/209 exit 0**、`tools\check-maintainability.ps1` **exit 0**。
7. **收尾**：单 commit（大概率零代码改动，按 T4.4 先例**不为提交而改代码**，只交证据）；不 push；证据行不删。

**【范围声明】**
- 做：断网 override 实验 + 1 条真音频 US-1 全链路 + 无出站三证 + 可选降级反例 + 恢复验证。
- 不做：改 `.env`/compose 基值/主代码；不测 LOCAL 档（F-14 已定案不可用）；不测 T4.4 已证的"零 key"（本卡刻意保留真 key）；
  **内网下不跑 E2E**（宿主到 8080 不通，属环境约束不是漏测——在回报里写清）；不删证据行。
- 存储纪律：override/样本/日志全在 `D:\new\artifacts\`。

**【验收】**
```powershell
# ① 断网生效：无默认路由 + 容器内 curl 外网失败 + settings(cloud_configured=true / offline_mode=true / preferred=EXTRACTIVE)
# ② 5 服务 healthy（内网下）
# ③ 真音频：taskId / DONE / ai_tier≠CLOUD / transcript 非空 / summary 非空 / keywords≥5 / error_code=null / export 三节 200
# ④ 日志：离线门禁 WARN + 首选 EXTRACTIVE + 落库行；零云档调用
# ⑤（可选）OFFLINE_MODE=false + 断网 → 云档失败 → 降级 EXTRACTIVE 且 DONE
# ⑥ 恢复：基础 compose 5 healthy、offline_mode=false、preferred=CLOUD、.env hash 2BCC440F1C9E、全量 209/209、可维护性 exit 0
# ⑦ 收尾：单 commit（或零提交）、不 push、证据行不删、配额 ≤2(+1) 任务 / ≤2 分钟
```
**通过标准**：断网 + 有 key 的环境下 US-1 仍 **DONE + 可导出**，`ai_tier≠CLOUD`；有"无出站"的硬证据（无默认路由 + 外网请求失败）；
离线门禁 WARN 可见；恢复后一切回基线、`.env` 哈希守恒。

**【可维护性】** 零代码改动（只交证据 + 文档）；不新增配置键；不引依赖。

**【回报·测试三问】** 检查了哪些场景（断网判据 / 真音频全链路 / 可选降级反例 / 恢复）｜ 实际命令 + 输出（override 原文、`/proc/net/route`、外网失败原文、settings JSON、
日志门禁 WARN 原文、taskId + 各字段、export 摘录、恢复后 settings、`.env` 哈希、exit code）｜ 还有哪些没验证（**不许留空**；E2E 未跑要写原因）。

**【开工方式（新会话）】** 仓库 `D:\new\projects\meeting-notes` → 读根 `AGENT.md` → `git status` 干净（HEAD `909b416` + 本次 docs 提交，**别回退 T4.1/T4.2/T4.4**）→ 读本卡 + `state.md`/`handoff.md` → 先写 override 并**先 down 再 up**。

---

## T4.6 接入面（US-10 / N17）（约 3.5h；W4 第 5 张）✅ **已完成（`1c2aa73` 单 commit；我复跑 220/220 + -UnitOnly 212/212，自建接收端重放验签/重试/终局全过；D-094/第 29 份报告）**

> **要证明的事**：**别人不开浏览器，也能把这个项目接进自己的系统**——三条 curl 走完"提交 → 查状态 → 取结果"，
> 一个 20 行接收端能收到 Webhook 并**验签通过**，契约能被发现（`/v3/api-docs` + Swagger UI）。
> 这是"给别人用"（D-003R）的最后一公里：现在 `/api/v1/**` **完全匿名**，`API_TOKEN` 只是摆设（D-040 明确留到本卡）。
> **纪律**：不改 `.env` 持久值（D-070/K-35）；实验配置用外部 override；验完恢复基线 + 哈希校验；每组实验立刻落盘日志/输出（K-39/K-43）。

**配额**：2026-11-15 已用 **68/120 分钟、28/50 任务**；本卡预算 **≤4 任务 / 0 分钟**（PASTE 0 分钟；Webhook 2 条 + 鉴权 1 条 + E2E 1 条）。
**开工前先查当日已用；回报前最后一步再查一次**（K-45）。

**主任务**：

1. **API Token 认证（D-040：`401 UNAUTHORIZED` + Bearer）**
   - 新增 `web/ApiTokenFilter`（`OncePerRequestFilter`）：`/api/v1/**` 必须带 `Authorization: Bearer <API_TOKEN>`；
     缺失/错误 → **401** + JSON `{"code":"UNAUTHORIZED","message":"…"}`（沿用全局 snake_case 与 `ErrorResponse` 结构），响应头加 `WWW-Authenticate: Bearer`；
   - **豁免（必须逐条实测）**：`/actuator/health`、`/actuator/health/**`、静态页与资源（`/`、`/index.html`、`/tasks.html`、`/task.html`、`/settings.html`、`/app.js`、`/auth.js`、`/style.css`）、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html`；
   - token 从**已有配置键**读（`security.api-token` ← `API_TOKEN`），**不新增键**；比较用**常量时间**（`MessageDigest.isEqual`），别用 `String.equals`；
   - 401 必须走统一错误口径（通用码，D-057），不能是默认 HTML 错误页。
2. **浏览器 UI 不被认证打死**（现在 4 个页面直接 `fetch('api/v1/…')`，没有 token）
   - 新增 `app/src/main/resources/static/auth.js`：`apiFetch(url, opts)` —— 自动带 `Authorization: Bearer`（token 存 `localStorage.mn_token`）；
     遇 401 → `prompt()` 让用户粘一次 token → 存 → **重试一次**；仍失败展示可读错误。
   - `index.html` / `tasks.html` / `task.html` / `settings.html` 的 fetch 调用改用 `apiFetch`，并把 `auth.js` 放在 `app.js`/内联脚本之前。
   - 验收：匿名 curl → 401；带 token curl → 200；页面静态审查 +（T4.13 真机再点）。
3. **OpenAPI（D-019 的第三面，几乎免费）**
   - `pom.xml` 加 `springdoc-openapi-starter-webmvc-ui`（**2.8.x**，需与 Spring Boot 3.5 兼容）；配置 `/v3/api-docs`、`/swagger-ui.html`；
     声明 `bearer` securityScheme（≤20 行配置即可，别给每个 controller 堆注解）。
   - 验收：`curl /v3/api-docs` → 200 合法 JSON（`paths` 里有 `/api/v1/tasks`）；`curl /swagger-ui/index.html` → 200；**这两个端点不加 token 也能访问**（契约可发现）。
4. **Webhook（严格按 `接入指南.md` §四 契约实现，不要自创字段）**
   - **触发**：任务进终态时 `DONE` → `task.done`、`DEAD` → `task.failed`；目标 URL：任务 `callback_url`（`task` 表已有列）优先，否则全局 `WEBHOOK_URL`；**`WEBHOOK_SECRET` 为空 → 不投递**（安全默认）。
   - **请求**：JSON 体 `{event, delivery_id, task_id, status, ai_tier, occurred_at, result_url, summary, todos_count}`；
     头 `X-MeetingNotes-Event`、`X-MeetingNotes-Delivery`（UUID，去重键）、`X-MeetingNotes-Signature: sha256=<HMAC_SHA256(secret, 原始请求体字节)>`、`Content-Type: application/json`。
   - **投递语义**：异步投递，**不阻塞 worker、不改变 DONE 时序**（D-069）；2xx = 成功；失败按 `WEBHOOK_MAX_RETRIES`（3）指数退避 **1s / 10s / 60s**；超时 `WEBHOOK_TIMEOUT_SEC`；
     每次尝试记账 `webhook_delivery`（`attempts`/`last_status`/`next_retry`），最终失败**留表可观测、不静默丢**；重试扫描用 `@Scheduled`（不新增配置键）。
   - **离线口径（本卡定，写进回报）**：`OFFLINE_MODE=true` 时**不投递**（Webhook 是出站），只记一行日志"离线模式跳过 Webhook"。
   - **不做（写进回报的"没做"）**：页面手动重投 UI（P2）、`WEBHOOK_SECRET_2` 轮换（P2）、签名时间窗/重放防护（P3）。
5. **契约回归与测试**
   - 现有 **209** 必须全绿；`E2ETest` 改为**带 token**（顺序：`E2E_API_TOKEN` 环境变量 → 读仓库根 `.env` 的 `API_TOKEN` → 空），并**新增 1 条用例**：不带 token 调 `/api/v1/tasks` → 401 且 `code=UNAUTHORIZED`（**基线变 210，验收按 ≥210 且不减少**；你若另加单测更多更好）。
   - `scripts/test.ps1`：全量跑前从 `.env` 取 `API_TOKEN` 并 `$env:E2E_API_TOKEN=…`（或在头注释写清手动跑法）；`-UnitOnly` 不受影响。
   - 我核过：现在**没有 MockMvc 单测**，所以 filter 不会打挂现有单测；若你新增 MockMvc 测试，必须带 token。
6. **验收（三条 curl + 20 行接收端 + 反例，全部要落盘输出）**
   - 三条 curl（带 token）：`POST /api/v1/tasks`（PASTE JSON）→ `GET /api/v1/tasks/{id}` → `GET …/export?format=md`；再各跑一次**不带 token**（必须 401）。
   - 接收端：`D:\new\artifacts\t46-receiver.ps1`（PowerShell `HttpListener` 或 Python stdlib，≤20 行有效逻辑）后台跑；
     用 override 起 app：`WEBHOOK_URL=http://host.docker.internal:18080/hooks`、`WEBHOOK_SECRET=t46-secret`，并加 `extra_hosts: ["host.docker.internal:host-gateway"]`；
     提交 1 条 PASTE → 接收端打印 payload、**验签 True**、`delivery_id` 与请求头一致；**反例**：secret 改成错的 → 验签 False。
   - OpenAPI：解析 `/v3/api-docs` JSON，断言 `paths["/api/v1/tasks"]` 存在。
   - 恢复基线：5 服务 healthy、`.env` hash `2BCC440F1C9E`、匿名/带 token 行为回到 .env 真实 token、全量 **≥210（209+新增 401 用例）** exit 0、可维护性 exit 0。
   - 收尾：**单 commit**（代码 + 测试 + 静态页 + pom 一个 commit）、不 push、不写 `docs/`、证据行不删；**最后一步再查配额/任务数**（K-45）。

**范围声明**
- 做：认证 filter + UI token 适配 + springdoc + Webhook 投递/签名/重试/记账 + E2E 一条 401 用例 + 三条 curl/接收端/反例证据。
- 不做：多用户/角色（D-002）、OAuth2、JWT、Webhook 管理页面、`WEBHOOK_SECRET_2`、手动重投 UI、给 OpenAPI 加全量中文注解、inbox/MCP。
- 存储：override/接收端脚本/日志/输出全在 `D:\new\artifacts\`。

**【可维护性】** 单文件 ≤200 行；认证 filter、Webhook 投递、控制器分离；`WEBHOOK_*` 四个已有键全部被消费（不许新增死配置）；commit 前 `check-maintainability.ps1` exit 0。

**【回报·测试三问】** 检查了哪些场景（401/豁免面/静态页/openapi/webhook 验签/反例/离线口径）｜ 实际命令 + 输出（三条 curl 原文、401 响应体、`/v3/api-docs` 路径片段、接收端 payload + 验签 True/False、`webhook_delivery` 行、E2E 用例数前后、全量 ≥210、`.env` 哈希、exit code）｜ 还有哪些没验证（**不许留空**）。

**【开工方式（新会话）】** 仓库 `D:\new\projects\meeting-notes` → 读根 `AGENT.md` → `git status` 干净（HEAD 以最新一条 docs 提交为准，**别回退 T4.1/T4.2/T4.4/T4.5**）→ 读本卡 + `state.md`/`handoff.md` → 先做认证 filter + UI 适配，再做 OpenAPI，最后做 Webhook。

---

## T4.7 批量提交（US-8）（约 3h；W4 第 6 张）✅ **已完成（`265b534` 单 commit；我复跑 227/227 + -UnitOnly 217/217，自造样本实机：逐项隔离/幂等不扣配额/超限 400/契约可见；D-095/第 30 份报告）**

> **要证明的事**：A 类用户（本地文件囤积者）的主场景——**一次提交 10~20 个文件，全部走到终态、互不影响，失败的可单独重试**；
> 提交后能看到**总时长 + 预计耗时**（需求确认单 D2：上限 20，不是 10）。
> **现状**：`POST /api/v1/tasks` 只收单文件；上传页一次只能选一个；E2E 没有批量场景。已具备：列表筛选/批量重试（T4.1）、`content_hash` 幂等、
> 模板（T3.9）、认证（T4.6——**所有请求都要带 Bearer**，这是横切改动，K-46）。
> **配额**：2026-11-15 已用 **68/120 分钟、36/50 任务**；本卡预算 **≤10 任务 / ≤6 分钟**（2 条 1s 静音 wav + 1 个坏文件 + 1 次超限请求 + E2E 1 条）。
> **开工前查配额，回报前最后一步再查**（K-45）。

**主任务**：

1. **新端点 `POST /api/v1/tasks/batch`**（multipart：`files` 可重复 + 可选 `template`；`/api/v1/**` 已由 T4.6 要求 Bearer）
   - 校验：0 个文件 → **400 `INVALID_PARAM`**；文件数 > `BATCH_MAX_FILES`（默认 20）→ **400**；总体积超限 → **413**（引擎层或显式检查，哪种都行，但要在回报里写清是哪层）；
   - **逐文件独立**：每个文件走**与单文件完全相同**的校验/幂等/入队路径（**复用它，不复制逻辑**，M6）；单项失败只影响该项；
   - 响应（请求合法即 **200**，即使部分失败）：`{items:[{file_name, task_id?, status?, reused?, code?, message?}], summary:{accepted, reused, failed, total_duration_sec, estimated_sec}}`；
     全部 snake_case（D-041）；`accepted` 含 `reused`；失败项复用统一错误码（D-057）；
   - 语义：accepted 入 PENDING 队列（与单文件一致）、DONE 最后写（D-069 不破）、同内容幂等 `reused`、配额**逐项**扣（不足时该项 429，其余继续——口径写进回报）。
2. **总时长 + 预计耗时**：对可探测的音频复用 `AudioDurationProbe` 取时长（**别重复实现**）；`total_duration_sec` = 各项之和；
   `estimated_sec` = `round(total_duration_sec * BATCH_ESTIMATE_RTF + accepted * 2)`（系数默认 0.15，含 ASR+结构化的保守估计；**只是预估，不承诺**）；PASTE/字幕项无时长则跳过。
3. **配置（不许新增死配置）**：`application.yml` + `.env.example` 加 **`BATCH_MAX_FILES`（默认 20）**、**`TASK_BATCH_MAX_SIZE_BYTES`（默认 2147483648）**、**`BATCH_ESTIMATE_RTF`（默认 0.15）**，
   放进已有 `app.limits.*` 段并沿用命名风格；三个都必须有消费方。注意：`spring.servlet.multipart.max-request-size` 若仍用 `TASK_MAX_SIZE_BYTES`(200MB) 会卡批量 → 改用 `TASK_BATCH_MAX_SIZE_BYTES`（单文件仍受 `max-file-size` 200MB）。
4. **前端**：`index.html` 文件选择器改 `multiple`（或加"批量"入口）→ `POST /api/v1/tasks/batch` → 展示**逐文件结果**（成功/复用/失败原因）+ "总时长 X 分，预计 Y 分"；
   失败项给"去重试"引导（DEAD 的可走 T4.1 批量重试，本卡只展示+引导，不做页内重试队列）。**保留单文件接口与旧行为**（别打断 T4.1/T4.5/T4.6 的调用方，K-46）。
5. **测试**
   - 单测：0 文件 / 超 20 / 混合成功失败 / 逐项结果映射 / 时长汇总（能抽纯函数就抽；**不要**新起全上下文 SpringBootTest，避免 K-42 抢队列）；
   - **E2E 新增场景 G**：测试内生成 2 个 **1s 静音 WAV**（44 字节头 + 零数据；空 transcript 按 D-048 仍 DONE）+ 1 个 `.mp3` 后缀的纯文本（**400 `INVALID_MEDIA`**）
     → 断言：`items` 逐项、2 个 accepted 最终 DONE、坏文件失败**不影响**其它；再发 **21 个 1 字节文件** → **400 上限**；全程带 token；
   - 全量 **≥221**（220 + 1）exit 0、`-UnitOnly` 相应增加、可维护性 exit 0；顺手确认 `/api/v1/tasks/batch` 在 `/v3/api-docs` 里可见。
6. **验收证据**：batch 请求/响应原文（含 summary 数字）、逐项 taskId、坏文件隔离、超限 400、总时长/预计耗时字段、
   `GET /api/v1/tasks?limit=50` 能查到这一批全部 id、E2E G 绿、恢复基线（209→221 的用例数、`.env` 哈希、5 healthy）。

**范围声明**
- 做：批量端点 + 逐项失败隔离 + 总时长/预计 + 上传页多选 + 3 个配置键 + 单测 + E2E G。
- 不做：INBOX 目录监控（P2 已砍）、断点续跑（P2）、批量 JSON 提交、批量换模板重跑（F-17 仍未做）、跨项事务/全有或全无语义（逐项独立才是需求）、页内重试队列。
- 存储纪律：证据/日志/临时文件全在 `D:\new\artifacts\`；实验配置用外部 override，不改 `.env`。

**【可维护性】** 批量逻辑抽 `task/BatchTaskService`（单文件 ≤200 行），复用单文件路径；控制器不膨胀；commit 前 `check-maintainability.ps1` exit 0。

**【回报·测试三问】** 检查了哪些场景（批量成功/幂等复用/坏文件隔离/超限/配额口径/前端多选/E2E G）｜ 实际命令 + 输出（batch 响应原文、summary 数字、逐项 taskId、
坏文件 400 原文、21 文件 400 原文、总时长与预计耗时、`/v3/api-docs` 片段、全量用例数、`.env` 哈希、exit code）｜ 还有哪些没验证（**不许留空**）。

**【开工方式（新会话）】** 仓库 `D:\new\projects\meeting-notes` → 读根 `AGENT.md` → `git status` 干净（HEAD `1c2aa73` + 本次 docs 提交，**别回退 T4.1/T4.2/T4.4/T4.5/T4.6**）→ 读本卡 + `state.md`/`handoff.md` → 先抽 `BatchTaskService` 复用单文件路径，再做端点，最后前端 + E2E。

---

## T4.8 重启不丢验证（N20）（约 1.5h；W4 第 7 张）✅ **已完成（零代码改动；Lead 自己又 down/up 一次复现数据逐字节不丢；僵尸回收 recovered:1 → task 202 DONE；Webhook 重启续跑实机补上 + 独立验签；F-22 新发现；D-096/第 31 份报告）**

> **要证明的事**：**重启（`down`/`up`，不删卷）后数据在、任务与结果均可查**；并且**"处理中被重启"的任务不会丢**——
> `StartupRecovery`（T2.3）会把崩溃时残留的 `RUNNING` 一律回收为 `PENDING` 并重新入队，最终仍能 DONE。
> **现状**：`StartupRecovery` 已有（单实例假设）；T2.8 已验"删卷能恢复"，本卡验的是**不删卷的日常重启**（部署升级场景）。
> **配额警告（必读）**：今日已用 **76/120 分钟、46/50 任务——只剩 4 个任务配额**；本卡预算 **≤2 任务 / ≤4 分钟**。
> **预期零代码改动**：本卡是纯验证卡。若发现必须改代码 → **停，报 Lead**（今日配额不够再跑一遍全量 E2E 的 3 条任务）。
> **纪律**：所有 curl 带 `Authorization: Bearer <API_TOKEN>`（T4.6 起）；`down` 会销毁容器日志，**先落盘日志再 down**（K-39）；不改 `.env`（hash `2BCC440F1C9E` 守恒）。

**主任务**：

1. **基线快照（不新建任务）**
   - 选一条**内容完整**的已有 DONE 任务（如 140 / 166 / 170 / 177），`GET /api/v1/tasks/{id}` 全文落盘、`GET …/export?format=md` 落盘并记 **sha256**；
   - DB 计数（落盘原文）：`task` / `task_result` / `dead_letter` / `webhook_delivery` 四张表；`GET /api/v1/settings` 配额也记下。
2. **重启**：`docker compose down`（**不加 `-v`**，卷保留）→ 记 `docker volume ls` 里数据卷仍在 → `docker compose up -d` → 等 **5 服务 healthy**。
3. **数据不丢（逐条断言）**
   - 同一任务 detail **逐字段**与快照一致（`transcript/summary/keywords/todos/template/ai_tier/created_at/status`）；
   - 导出 md **sha256 逐字节相同**（内容真的没变，不是"能打开"）；
   - 四张表计数与快照一致；配额不变（同日）；
   - 读端点全通：详情 / 列表 / 导出 / 设置（带 token）各一次。
4. **RUNNING 僵尸回收（唯一新建任务，1 条）**
   - 从 `D:\datasets\meeting-notes-samples` 选一条 **≥2 分钟**的现成音频（用未用过的样本或 hash 变体，避免命中 reused）；
   - 上传 → 轮询到 `RUNNING`（通常几秒内）→ **立刻 `docker compose restart app`**；
   - 重启后断言：日志有 `启动恢复：回收 RUNNING 僵尸任务重新入队`（**贴原文 + recovered 数**）；任务回到 `PENDING` 并重新被消费 → 最终 **DONE**、detail 字段完整（transcript 非空）、`error_code=null`、`task_id` 不变、`attempts` 未被异常放大；
   - 配额只扣一次（对比上传前后 `/settings`）。
5. **可选（预算内、0 任务）**：重启后 **Webhook 重试续跑**（补 T4.6 的"重启后续跑未验"）——手工插一行 `webhook_delivery`（引用现有 DONE 任务、`next_retry=now()`），用 webhook override + 接收端起 app，`@Scheduled` 应捡起并投递；不做就在回报写原因。
6. **收尾**
   - 5 healthy、`.env` hash `2BCC440F1C9E`、`/settings` `preferred=CLOUD`；
   - **零代码改动 → 不 commit、不 push**；
   - 测试：**零代码改动时跑 `-UnitOnly`（217/217 exit 0）即可**（全量 E2E 会再吃 3 个任务，今日配额不够；**在回报里写明为什么没跑全量**）；
   - 证据行不删（被回收的那条任务会重跑，属预期）；日志/快照落盘（K-39/K-43）。

**范围声明**
- 做：不删卷重启 + 数据/导出逐字节不丢 + RUNNING 回收 +（可选）Webhook 续跑。
- 不做：备份/恢复（T2.8 已验）、卷损坏/跨机器（T2.8 §6 未验）、多实例集群（单实例假设，StartupRecovery javadoc 已写）、改代码。

**【可维护性】** 预期零代码改动；若必须改，先报 Lead 再动（今日配额限制）。

**【回报·测试三问】** 检查了哪些场景（重启前快照 / down-up / 数据逐字节 / RUNNING 回收 / 可选 Webhook 续跑）｜ 实际命令 + 输出（快照与重启后 detail/导出的 sha256、
四张表计数、`启动恢复` 日志原文 + recovered 数、重启任务 id/状态/attempts、`-UnitOnly` 用例数与 exit code、`.env` 哈希）｜ 还有哪些没验证（**不许留空**，全量未跑要写原因）。

**【开工方式（新会话）】** 仓库 `D:\new\projects\meeting-notes` → 读根 `AGENT.md` → `git status` 干净（HEAD `265b534` + 本次 docs 提交，**别回退 T4.1~T4.7**）→ 读本卡 + `state.md`/`handoff.md` → **先做快照并落盘，再 down**。

---

## T4.12 发布前死配置体检（约 0.5–1h；W4 第 8 张）✅ **已完成（`3c650ca` 单 commit；40 键/死配置 5 全计划中/孤儿 0；F-22 空 secret 短路+WARN 一次、F-21 fail-fast 可读、F-23 时区 WARN；我独立复现三反例、复跑 -UnitOnly 225/225、可维护性 0；全量 235/235；D-098/第 32 份报告）**

> **要证明的事**：①"配置一个不落"是**可判定**的——`app.*` 每个键都有消费方、五方（yml / `.env.example` / compose / 代码 / `配置清单.md`）一致、**孤儿配置 = 0**（或逐条登记"计划中"）；
> ②补掉两个已实证的"空配置"缺陷：**F-22**（`WEBHOOK_SECRET` 为空时遗留到期行被调度器空烧 4 次重试、日志文案误导）与 **F-21**（`LLM_ENABLED=true` + 云 key 空 → 启动崩、报错间接）。
>
> **现状（别重复造）**：`配置清单.md` §2 有 `$appKeys` 快照（当前 **34 键**）+ 机器判据；`/settings` 已是配额/档位单一判定来源（T4.1）；Webhook 记账/重试已上线（T4.6）；
> T4.7 新增 3 个 `BATCH_*` 键且**都有消费方**。**F-22 已被 Lead 独立复现**：基础栈（`WEBHOOK_SECRET=` 空）手工插一行到期 `webhook_delivery` → 1s/10s/60s 空烧 4 次到 `last_status=0`，
> 日志原文 `Webhook 投递异常：…（HMAC-SHA256 不可用），留表重试`；根因是 `onTerminal` 检查了空 secret、**`scanDue` 没检查**，`attempt→post→sign("")` 抛空密钥异常被包成"算法不可用"。
>
> **配额警告（必读）**：截至 2026-11-15 15:24 已用 **80/120 分钟、47/50 任务——今日只剩 3 个任务配额**，而**全量 227/227 一次吃 3 个任务**（E2E A PASTE + G 两个静音 wav）。
> 本卡是**代码卡，必须跑全量**。**若开工时今日剩余任务配额 < 3 → 等北京日切（00:00）后再开工**（日切点已由 F-23 修正为北京时间 00:00，可先 `SHOW timezone` 复核）；
> 开工前查一次、收尾最后一步再查一次（K-45）。
>
> **范围（按序做）**：
> 1. **配置体检表（只读核对，产出一张表）**：`application.yml` 的 `app.*` 键、`.env.example`、`docker-compose.yml` 环境变量、`配置清单.md` §1/§2、代码消费点五方对齐，逐键给：
>    键名 / 默认值 / 消费方（`文件:行` 或 `@Value`/`@ConfigurationProperties`/`Environment`）/ 有无测试覆盖 / 结论（保留 / 计划中 / 删除）。
>    重点核 **3 个 BATCH_* 键**不是死键；凡"文档写了但代码没有"或"代码读但文档没有"的，逐条列出。
> 2. **F-22 修复（必须）**：`WebhookDeliveryService.scanDue`（或 `attempt` 入口）在 `secret` 为空时**直接跳过**并给一条明确日志（语义是"配置缺失"，不能再出现 `HMAC-SHA256 不可用` 误导）。
>    要保证遗留到期行**不再空烧重试**；对"`next_retry` 保持原样还是置空"二选一，**回报里写明选择与理由**。**必须加单测**：secret 为空时扫描不投递/不空烧。
> 3. **F-21 启动预检（小改）**：`LLM_ENABLED=true` 且云档 key 全空 → 启动 **fail-fast 且文案可读**（点明缺哪个键、怎么修），替代现在的间接报错（`OpenAI base URL must be set`）。
>    **不改**"显式优先"语义（D-039）；已有 `NoKeyStartupTest` 覆盖零 key 正常启动，别弄红（`@MockBean TranscriptionWorker` 保留，K-42）。
> 4. **队列键独立评估（只评估，不写代码）**：`NoKeyStartupTest` 现靠 `@MockBean` 避免抢真实 Redis 队列（K-42）。评估"独立队列键 / 独立 Redis DB"是否更忠实，给结论（改/不改 + 理由 + 风险）。
> 5. **时区一致性（F-23，T4.8 验收后实测发现，运维修正已由 Lead 做完）**：`TZ=Asia/Shanghai` 只对**新建数据卷的 initdb** 生效；本机老卷的 PG GUC 仍是 **UTC**（容器 `date` 显示 CST 但 `SHOW timezone`=UTC），
>    导致配额实际在**北京时间 08:00** 重置、00:00–08:00 窗口里 `Retry-After`（JVM 本地日）与计费日打架。Lead 已执行 `ALTER DATABASE meetingnotes SET timezone TO 'Asia/Shanghai'` 并重启 app（证据 `artifacts\T4.8\tz-fix\`，
>    现 `SHOW timezone=Asia/Shanghai`、`CURRENT_DATE` 与北京日一致）。本卡要做的：①**启动预检**：DB `TimeZone` 与配置的 `TZ` 不一致 → 明确 WARN 或 fail-fast（二选一给理由）；
>    ②**新装/新卷验证**：临时起一个空数据卷的 postgres 验 compose 的 `TZ` 是否真写进 `postgresql.conf`（`SHOW timezone`=Asia/Shanghai；**别动现有卷**，验完 `down -v` 清掉临时卷）；
>    ③核对 `QuotaService`（`CURRENT_DATE`）与 `ApiExceptionHandler.secondsUntilMidnight`（JVM `LocalDate.now()`）在修正后的语义是否一致（判据：`SHOW timezone` + `CURRENT_DATE = (now() AT TIME ZONE 'Asia/Shanghai')::date`）。
> 6. **孤儿配置**：若有"计划中"的键，逐条登记（写码方只交清单，`配置清单.md` 由 Lead 同步）。
>
> **不做**：不改任何对外契约（详情 12 键、错误码、批量 200 部分成功语义、Webhook 九键）；不加新功能；不动 `.env` 持久值（实验一律 override）；**不删除 F-22 的证据行**（`webhook_delivery` id=6，D-073）。
>
> **验收命令（原样贴输出）**：
> - 全量 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test.ps1` → **≥228/228**（新增单测）+ exit 0；`-UnitOnly` 对应条数 + exit 0；`tools\check-maintainability.ps1` exit 0；单 commit、不 push、零 `docs/`、零 `.env`。
> - **F-22 反例**：基础栈（`WEBHOOK_SECRET=` 空）+ 手工插一行 `next_retry=now()` → 等 10s：**不再空烧**（attempts 不增长，或按实现置空并有明确日志），日志**无** `HMAC-SHA256 不可用`；该行保留作证据（不删）。
> - **F-21 反例**：用 override 设 `LLM_ENABLED=true` + 云 key 空 → 启动**失败**且日志/退出文案可读（贴原文）；还原后 5 healthy。
> - 收尾：5 healthy、`.env` hash `2BCC440F1C9E`、`/settings` `preferred=CLOUD`、证据行不删、配额最后一步查（K-45）。
>
> **【回报·测试三问】** 检查了哪些场景（配置五方对齐 / F-22 空 secret 反例 / F-21 预检反例 / 队列键评估 / 全量回归）｜ 实际命令 + 输出
> （配置体检表、F-22 反例前后 attempts 与日志原文、F-21 启动失败原文、用例数 + exit code、commit hash）｜ 还有哪些没验证（**不许留空**）。
>
> **【开工方式（新会话）】** 仓库 `D:\new\projects\meeting-notes` → 读根 `AGENT.md` → `git status` 干净（HEAD = T4.8 验收的 docs 提交，`git log -1` 核对 message 含 `docs(T4.8)`；**别回退 T4.1~T4.8**）
> → 读本卡 + `state.md`/`handoff.md` → 先读 `webhook/WebhookDeliveryService.java` 与 `配置清单.md` §1/§2，再动代码。

---

## T4.14 前端视觉重设计（约 3–4h；W4 第 10 张，插在 T4.13 之前）✅ **条件通过（`f42dc85`+`508de4c`；我独立验证 4 页桌面/手机 0 console error/0 溢出、导出真下载、token 对话框/拖拽/单箭头全过；P1 对比度 3.16:1 待修；全量 235/235 待 00:10 自动化执行；D-100/第 33 份报告）**

> **要证明的事**：4 个原生页面在**不改技术栈、不改后端契约**的前提下，从"极简能用"升级到"专业、一致、有状态感"；
> **不是重写**，而是按设计技能做"审计 → 就地升级"，并留下**前后截图对比**。技能来源 = 用户指定的 GitHub 仓库（MIT）。
>
> **技能（本地已 vendor，不用联网、不用装 Node）**——**开工第一件事：完整读这两份再动手**：
> 1. 主技能：`D:\new\artifacts\design-skills\redesign-skill\skills\redesign-existing-projects\SKILL.md`（流程：Scan → Diagnose → Fix；Fix Priority 9 级；Rules）
> 2. 设计值：`D:\new\artifacts\design-skills\ai-design-skills\skills\landing-page-design\SKILL.md` 的 **PART B（视觉系统）+ Design values 段**（只取设计值，**不采用** PART A 的落地页转化/SEO/文案套路——我们不是营销页）
> 3. 现状截图（我 15:50 用 Playwright 截的当前 UI）：`D:\new\artifacts\ui-preview\index.png` / `tasks.png` / `settings.png` / `tasks-mobile.png`
>
> **Design Values 适配版（本项目强制，覆盖技能默认值）**：
> - **字体**：禁止外链字体/CDN（离线自托管）→ 系统中文栈 `-apple-system, "PingFang SC", "Microsoft YaHei", "Noto Sans SC", sans-serif`；权重 ≤700；禁斜体；
> - **字号/行高**：吸附 Tailwind 刻度 12/14/16/18/20/24/30/36px（行高 16/20/24/28/28/32/36/40）；
> - **间距**：0/2/4/8/12/16/24/32/40/48px；**圆角**：4/6/8/12/16px（嵌套形状 inner=outer−gap，结果>2 才用）；
> - **颜色**：**单一 accent**（沿用蓝系、饱和度收敛，不用紫色 AI 渐变）、背景**禁渐变**、卡片**不要"边框+阴影+白底"三件套**（二选一）、阴影带背景色倾向、数字用 `tabular-nums`；
> - **交互态**：hover / active / `:focus-visible` 三态齐全；动效 base curve `cubic-bezier(.22,1,.36,1)`，移动 500ms、颜色 300ms，`prefers-reduced-motion` 降级为淡入；
> - **状态**：骨架屏（形似真实布局）+ 空态（组合式引导，不是空白）+ 行内错误（**禁 `window.alert`**）；导航有当前页高亮；
> - **响应式**：断点 640 / 900 / 1200；手机上是可用的一等公民（不是桌面缩放）。
>
> **参考站（只借鉴信息结构/交互，不抄视觉资产与文案；开工前扫一眼即可）**：
> [Otter.ai 界面截图集](https://design.lazyweb.com/company/otter) · [Otter App 截图](https://www.screenspo.com/apps/otter-ai) · Fireflies / tl;dv / Notta / 飞书妙记 / 通义听悟。
> 重点看：上传区（拖拽/进度）、任务列表（状态+重试入口）、详情页"转写 / 摘要 / 待办"的信息分层、配额用量、空态引导。**我们的定位是自托管文件工具，不做会议机器人/日历集成**。
>
> **范围（只改 `app/src/main/resources/static/`）**：
> 1. `style.css` 重做为**设计 token + 组件样式**（CSS 变量：颜色/间距/圆角/字号/阴影/动效；保留现有 class 名兼容或同步改 JS）；
> 2. 4 个页面视觉与状态升级：`index.html`（上传区含**拖拽**、批量逐项结果、进度/错误态）、`tasks.html`（筛选、自动刷新、加载更多、批量重试、状态徽章、骨架/空态）、
>    `task.html`（元信息、转写、摘要/关键词/待办分节、模板/ai_tier 呈现、导出入口）、`settings.html`（配额条、档位、术语表）；
> 3. `auth.js` 的 token 输入：把现在的 `window.prompt` 换成**页内对话框**（保留 localStorage `mn_token` 与"401 后重试一次"语义，保留 prompt 兜底或删除需在回报说明）；
> 4. `app.js` 只在**渲染结构**需要时同步改（API 调用/字段名一律不动）。
>
> **功能契约（一条都不许少）**：上传（单/多文件）、批量逐项结果与 `summary`、列表筛选 + 3s 自动刷新 + 游标加载更多、批量重试（只 DEAD）、
> 详情 12 键渲染、导出 md 链接、设置页配额/档位/术语表、token 流（`mn_token`、401 重试一次）、所有 `/api/v1/**` 带 Bearer、离线可打开（无外链资源）。
> **不做**：不改 Java/后端/契约/错误码；不加新功能（登录/多用户/分享）；不引框架/构建链/CDN/外链字体；不做深色模式（v2）；不改 `.env`/`docs/`；不删任何现有功能。
>
> **流程（按技能，但不等 veto 卡住）**：先写诊断清单到 `D:\new\artifacts\T4.14\diagnosis.md`（**每条：问题 / 严重度 / 建议改法 / 是否修 + 理由**，对照技能 audit 的 Typography/Color/Layout/States/Content/Components 分类），
> 然后按 Fix Priority 实施；**诊断清单必须进回报**（Lead 验收时逐条核对"说了不改的为什么"）。
>
> **验收命令（原样贴输出）**：
> - Playwright 截图（可复制/扩展 `D:\new\artifacts\ui-preview\shot.py`）：桌面 1440 + 手机 390，覆盖 4 页 × 正常/空/加载/错误/筛选态，落盘 `D:\new\artifacts\T4.14\before|after\`；
>   **必须同时断言：页面无 console error、无 401 泄漏、无 layout overflow（手机 390 下横向滚动条必须没有）**；
> - 功能冒烟（配额允许时 1 任务）：用 1s 静音 wav 真上传一次 → 列表出现 → 截图；配额不够则用现成任务截图并在回报写明；
> - `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test.ps1` 全量 **≥235/235** + exit 0；`-UnitOnly` 225/225；`tools\check-maintainability.ps1` exit 0；
> - 单 commit、零 `docs/`、零 `.env`、零 Java/后端改动（`git diff --name-only` 只允许 `app/src/main/resources/static/`）。
>
> **配额警告（必读）**：截至 2026-11-15 17:00 配额 **82/120 分钟、50/50 任务（今日归零）**→ **必须等北京时间 00:00 日切后开工**；
> 本卡预算 **≤4 任务**（全量 3 + 上传冒烟 1），其余（截图/脚本/诊断）0 任务；开工前与收尾各查一次 `/settings`（K-45）。
>
> **【回报·测试三问】** 检查了哪些场景（诊断清单逐类 / 4 页前后对比 / 5 类状态 / 手机 390 / 功能冒烟 / 全量回归）｜ 实际命令 + 输出
> （诊断清单文件、改动文件、设计 token 表、截图文件名、用例数 + exit code、commit hash）｜ 还有哪些没验证（不许留空）。
>
> **【开工方式（新会话）】** 仓库 `D:\new\projects\meeting-notes` → 读根 `AGENT.md` → `git status` 干净（HEAD = T4.13 卡写入后的 docs 提交，`git log -1` 核对含 `docs(T4.14)`；**别回退 T4.1~T4.12**）
> → 读本卡 + `state.md`/`handoff.md` → **先读两份本地 skill 文件** + 看现状截图 → 写诊断清单 → 动手。

---

> **【验收后追加：T4.14b 视觉微修（P1，约 10 分钟，0 配额）】✅ 已完成（`663ddfe`；Lead 复测 `.hint` 白卡 5.28:1 / 纸底 4.88:1、badge 6.49:1，`-UnitOnly` 225/225、可维护性 0/0；D-101）** —— 原始要求：Lead 验收实测：`.hint` 文字 3.16:1（`rgb(138,138,147)` on `rgb(248,246,239)`）**低于 WCAG AA 4.5**；`.hint` 是全站说明文字主力 class。
> 要求：①把 `--ink-3` 加深到 **≥4.5:1**（建议 `#6b6b73`≈4.9:1，或等价 token 级改法）；②顺带把「已完成」徽章 4.56:1 再抬一点（可选）；③重拍关键截图（index/tasks/task/settings 各 1 张 + 手机 1 张）落 `artifacts\T4.14\after2\`；
> ④`-UnitOnly` 225/225 + 可维护性 exit 0；⑤**CSS-only 单 commit**；⑥**commit 后立刻把 00:10 自动化（id 52235e59）的 HEAD 校验值更新到该 commit**，让全量绑定最终代码。不做其他风格改动（除非 Lead 另行确认）。

> **【验收后追加：T4.14c 四项小打磨（已授权，约 10 分钟，0 配额，CSS/HTML/JS 但仅 static/）】✅ 已完成并通过（`643ed90`；Lead 独立复测：hover 标题 16.24:1 / 次要行 4.93:1（alpha 合成后）、`.task-meta` 正常态 5.28:1、4 页 0 console error、手机 390 溢出 0 / 行高 107px、mock 验 null→`—`/UTC+8/DEAD 复选框/长名省略、`-UnitOnly` 225/225 exit 0、可维护性 0/0、无新增任务；D-102）**
> 原始要求：
> ② 方格纹理再淡 10–20%（保持可见但不抢）；③ 列表行 hover 极淡底色（桌面可点感）；④ 手机 390 行高/点按区放松（≥44px 目标）；
> ⑤ 列表行补次要信息：**时长 + 提交时间**（`GET /api/v1/tasks` item 已含 `duration_sec`/`created_at`，**纯前端渲染，不得改后端/契约**；PASTE 无时长时间显示 `—`，数字用 `tabular-nums`，时间格式统一本地 `YYYY-MM-DD HH:mm`）。
> 约束：**改完必须重测 `.hint`/徽章/chip 的对比度**（网格/hover 底色变化可能压文字，K-56）；正常态 0 console error、手机 390 零横向溢出；`-UnitOnly` + 可维护性；**CSS/HTML/JS-only 单 commit**；
> **commit 后把 00:10 自动化 HEAD 再绑定到最终 commit**，并在回报里给出最终 HEAD。**不做其他风格改动**（暖纸底/黄 accent/标题字号/圆角/阴影一律不动）。

---

## T4.13 真机 UI 验收（约 1h；W4 第 9 张）**✅ 已通过（2026-11-16，用户本人真机点完 9 条 + 新增 UI 速看；耗 2 任务 264/266；发现 5 个新问题 → T4.20/T4.21；第 42 份报告，D-114）**

> **要证明的事**：**真浏览器/真手机**上"用户点得到"的路径全部走通并留证据（截图/录屏），不是静态代码审查、不是 curl。
> **这是人机协同卡**：**人类**在桌面浏览器 + 手机（同局域网）上操作；**写码方**负责准备测试文件、确认栈 5/5 healthy、收集/整理截图与逐条结果、写回报。
>
> **配额（2026-11-16 19:50 复核，T4.17/18/19 完成后）**：今日已用 **30 任务 / 126 分钟**，**剩 70 任务 / 74 分钟**。预算 **≤3 任务**（单文件上传 1 + 手机上传 1；其余路径复用已有任务或只读端点、0 任务）。开工前与收尾各查一次 `/settings`（K-45）。
> **人机分工**：人类负责**点**（桌面浏览器 + 手机），写码方负责**准备/核对/整理截图**；写码方不要替人类伪造"点过"的结论，没点到的路径必须如实写"未验证 + 原因"。
>
> **验收清单（9 条，逐条给"操作 → 实际结果 → 截图文件名"）**：
> 1. **上传页单文件**：`/`（`index.html`）→ 文件选择器选 1 个音频 → 上传 → 列表出现新任务 → 点进详情可看（transcript/摘要/待办/模板/ai_tier）→ 导出 md 可下载；
> 2. **拖拽上传**（桌面 Chrome/Edge）：把同一文件拖进上传区 → 同上；
> 3. **批量多选**（T4.7）：一次选 2 个文件 → 逐文件结果 + `总时长/预计耗时` 展示；含 1 个坏文件时应"逐项失败不整批失败"（可用纯文本改成 .mp3 后缀造坏文件）；
> 4. **列表页筛选** `/tasks.html`（T4.1）：全部/进行中/失败等筛选可切换，列表不报 JS 错；分页"加载更多/游标"可用；
> 5. **设置页** `/settings.html`（T4.1）：配额（已用/剩余）、当前 AI 档位、术语表只读展示正确；
> 6. **浏览器 token 流**（T4.6）：无 token 首次调 API → 页面弹 prompt → 粘 `API_TOKEN` → 自动重试成功（`localStorage.mn_token` 生效；刷新后不再问）；
> 7. **Swagger UI** `/swagger-ui/index.html`：Authorize 填 Bearer → Try it out 调 `GET /api/v1/tasks?limit=1` 返回 200；
> 8. **手机真机**（iOS Safari / Android Chrome，同局域网访问 `http://<宿主IP>:8080`）：选文件上传 1 个 → 列表可见（P1 手机上传）；
> 9. **无痕窗口**：未带 token 时 401 文案不泄漏内部信息；带 token 后正常。
>
> **操作清单（2026-11-16 19:50 刷新版，给人照着点）**：`D:\new\artifacts\T4.13\操作清单.md` —— 9 条路径 + **第 10 条"新增 UI 速看"**（用已有任务 237 看要点、256 看一句一行/导出勾选，**0 配额**）；全程用 `127.0.0.1` 不用 `localhost`（K-70）；截图落 `D:\new\artifacts\T4.13\shots\`。
>
> **纪律**：本卡**不改代码**——发现 UI bug 只记录（现象 + 截图 + 复现步骤）并报 Lead；**不改 `.env`**；截图/录屏一律落 `D:\new\artifacts\T4.13\`；
> 所有手工 API/curl 带 Bearer；不改 `docs/`。
>
> **回报（测试三问）**：检查了哪些路径（9 条逐条）｜ 实际结果（每条：通过/失败 + 证据文件名 + 关键现象；手机型号/浏览器版本；配额消耗）｜
> 还有哪些没验证（不许留空；真机没点到的必须写明谁没点到、为什么）。**再附**：栈 5/5 healthy、git status、配额最后一步查。
>
> **开工方式（新会话）**：仓库 `D:\new\projects\meeting-notes` → 读根 `AGENT.md` → `git status` 干净（HEAD = **`48df935`**〔T4.19〕；`git log -1` 核对；**别回退 T4.1~T4.16b**）
> → 读本卡 + `state.md`/`handoff.md` → 查 `/api/v1/settings`（`remaining_tasks ≥ 3`）+ `docker compose ps` 5/5 healthy。
> **已取消的旧前置**：00:10 自动化没执行（K-62），全量 235/235 已由 Lead 在 `fb93469` 上手跑通过，不再阻塞本卡。
> **证据目录**：`D:\new\artifacts\T4.13\`（每条路径"操作 → 实际结果 → 截图文件名"）。

---

## T4.15 第 0 档：把已有东西摆出来 + AI 可见度（约 7–8h；0 配额；**纯 static**）✅ 已完成（`e95e7e2`；Lead 独立复核九项全过，`-UnitOnly` 225/225、可维护性 0/0、0 新增任务，D-105）

> **要证明的事**：**不新增后端、不新增依赖**，只靠信息设计解决"页面太空 / 看不出 AI 在哪"——首页有「最近任务 + 怎么用 + 示例结果」，列表能搜能数，详情页每条待办都能看出「依据原文哪句」、能勾、能复制。
>
> **硬约束（违反即打回）**：只改 `app/src/main/resources/static/`（4 个 html + `style.css` + `app.js`）；
> **零后端**：不新增/修改接口、不改 Java / `application.yml` / `.env` / DB / 契约；不新增依赖、不加构建链。
> 只用现有接口：`GET /api/v1/tasks`、`GET /api/v1/tasks/{id}`、`PATCH /api/v1/tasks/{id}/todos/{index}`、`DELETE /api/v1/tasks/{id}/todos/{index}`、`GET /api/v1/settings`、`GET /actuator/health`。
> **离线约束不破**：无 CDN / 外链字体 / 外部图片；图标一律内联 SVG 或 `data:`；页脚 GitHub 链接只是 `<a href>`，**不加载任何外部资源**。不动 T4.14 的设计 token（暖纸底/黄 accent/标题字号/圆角/阴影）。
>
> **九项要求 + 验收判据**（Lead 会逐条量，不许只给"看起来对"）：
> 1. **首页「最近 3 条任务」**：`GET /api/v1/tasks?limit=3` 展示 名称/状态/时长/时间 + 可点详情；失败**整块静默隐藏**；0 条不显示。
> 2. **首页「三步说明」**：①上传 ②等转写 ③看纪要/待办并导出；静态 HTML + 内联 SVG；桌面/手机不溢出，对比度 ≥4.5:1。
> 3. **首页「示例结果」折叠卡**：展开显示一段**明确标注「示例数据」**的摘要/关键词/待办样例；**静态、不发请求、不建任务**（今日配额 0，不能靠它造数据）。另提供「复制示例文本去真跑」入口（只在 `remaining_tasks ≥ 1` 时可用，否则禁用并提示"今日任务额度已用尽"）。
> 4. **列表页搜索**：输入文件名/ID 即时过滤**已加载**条目，文案写"在已加载的 N 条中筛选"（诚实标注），清空恢复，空结果空态。
> 5. **列表页状态计数**：筛选胶囊旁显示 全部/进行中/已完成/失败 条数；实现 = 游标循环拉全量（**上限 200 条**，超过显示 `200+`；页面可见时 30s 刷新）。数字必须与 DB 实测一致。
> 6. **列表页多选批量导出 md**：多选后逐个下载 `.md`（**浏览器端逐个 `a.download`，不做 zip**——zip 属 T7.7 后端工作）。
> 7. **设置页「系统状态」卡**：5 服务（app/asr/minio/postgres/redis）UP/DOWN + 当前 AI 档位 + 今日配额 + 版本号 `v0.1.0`（**静态常量**；commit 展示属 T7 的 build-info，本卡不做）；任一接口失败显示"未知"而不是报错弹窗。
> 8. **详情页「依据高亮 + 关键词定位」**：每条待办下方一行「依据：「原句」」，点它跳到逐字稿对应句并高亮；点关键词同样定位。
>    实现 = 浏览器内用**与 `ai/TodoGrounding.java` 同口径**的算法（归一化：去空白/标点/小写 → 字符二元组命中率 ≥0.5）找最匹配句子；**匹配不到就写「未找到直接依据」，绝不瞎指**；
>    文案必须是「在原文中匹配到的句子」（**不得声称是模型给出的依据**）。**只读，不改任何存储**。
> 9. **详情页「待办勾选/删除 + 复制全文」**：待办 checkbox 调 `PATCH`（`{done:true|false}`，幂等，乐观更新 + 失败回滚 + 行内错误），可 `DELETE` 删待办；逐字稿旁「复制全文」用 `navigator.clipboard` + `textarea` 兜底 + "已复制"提示。
> **顺带**：页脚 = 版本号 + GitHub 链接 + LICENSE 说明（T5.6 发布素材会用到）。
>
> **禁止**：其他风格改动 / 加新页面 / 引入外部资源 / 顺手改后端（哪怕只加 `total` 字段——那是 T7.2）/ 为凑效果造假（示例必须标"示例数据"、依据匹配不到就说匹配不到）/ 改 `.env` / 改 `docs/`。
>
> **回报（测试三问）**：`任务：T4.15`；改了什么（文件 + 行数）；验证命令（原样）；实际输出（关键输出 + exit code）；剩余风险（一句）。
> 另附：`git diff --name-only`（只允许 `static/`）、`-UnitOnly` **225/225 exit 0**、可维护性 **exit 0**、桌面 1440 + 手机 390 截图路径、新增文字对比度数值、**最终 commit hash**、**已把 00:10 自动化（id 52235e59）重绑到该 commit 的确认**。
>
> **排期（A 方案）**：实现 → `-UnitOnly` 225/225 → 单 commit → **把自动化 `52235e59` 的 HEAD 重绑到该 commit** → 回报 hash；00:10 产物落 `artifacts\T4.15\post-midnight\`；明早 Lead 核验后写第 35 份验收报告。
> **配额警告**：本卡 **0 任务**（`-UnitOnly` 不建任务；示例卡是静态数据）。**今日任务配额 50/50 已归零**，任何真提交都会 429——所以第 3 项的真跑入口只做界面与 mock 验证，真跑留给 T4.13 真机验收（日切后）。
> **开工方式（新会话）**：仓库 `D:\new\projects\meeting-notes` → 读根 `AGENT.md` → `git status` 干净（HEAD = `643ed90`）→ 读本卡 + `state.md`/`handoff.md` → 先确认 5/5 healthy 与 `/settings`。

---

> **【验收后追加：T4.15b 对比度 P2 微修（约 5 分钟，0 配额，CSS-only）】✅ 已完成（`fb93469`；Lead 复测三处使用点 6.45:1，badge.done 6.49 不变，D-105）**
> Lead 复核实测（`artifacts\T4.15\lead-verify\`）：`--accent-deep #a16207` on `--accent-tint #fdf4d9` = **4.48:1 < WCAG AA 4.5**（12px 文字）；
> 受影响元素：**T4.15 新增的 `.sample-badge`**（"示例数据"诚实标签）+ **复用的 `.chip`（关键词胶囊）与 `.badge.busy`**（T4.14 就有，Lead 当时未量到，属漏项，K-56 补）。
> **要求**：①`style.css` 里 token `--accent-deep: #a16207` → **`#92400e`**（Lead 预算：on tint = 6.45:1，on white = 更高；**不要动 `--accent` / `--accent-tint` / 其他 token**）；
> ②复测并给出数值：`.sample-badge`、`.chip`、`.badge.busy`、以及 `grep` 到的所有 `--accent-deep` 使用点；
> ③前后对比截图（index 示例卡 + task 203 关键词/待办 + 列表「进行中」徽章状态若可造）落 `artifacts\T4.15\after2\`；
> ④`-UnitOnly` 225/225 + 可维护性 exit 0；⑤**CSS-only 单 commit**（`fix(T4.15b): ...`）；
> ⑥**把 00:10 自动化（id 52235e59）重绑到新 commit 并回报 hash**（Lead 会只读查库核验）。

---

## T4.15c 详情页空态文案修正（约 10 分钟；0 配额；纯 static）

> **要证明的事**：用户截图指出——1 秒静音样本（task 206）的详情页把"没识别到语音"显示成"AI 未启用或本次未生成"，**误导**。要按 D-072 语义把三种情况分清。
>
> **要求**（只改 `static/task.html`，不碰后端/契约）：
> 1. `transcript` 为空 **且** `ai_tier` 为 null → 摘要/关键词/待办区域文案改为：**「这段音频未识别到语音（可能是纯音乐、静音或无人声），所以没有摘要/关键词/待办。」**
> 2. `transcript` 非空 **且** `ai_tier` 为 null → **保留现有字符串不变**「AI 未启用或本次未生成，暂无内容。」（不要改字）
> 3. `transcript` 非空且 `ai_tier` 有值 → 现有渲染不变；`todos === []` 仍显示"确实没有待办"（D-072 语义不动）。
> 4. 判据：task **206/205**（空转写）显示新文案；task **203**（正常）三节不变；console error 0；手机 390 不溢出；`-UnitOnly` 225/225 + 可维护性 exit 0；单 commit（`fix(T4.15c): ...`）。**不需要重绑自动化**（00:10 自动化未执行，Lead 已在 `fb93469` 上亲自跑全量：235/235、`mvn exit 0`，证据 `artifacts\T4.15\post-midnight\full-test.log`）。**不动其他文案/样式。**
5. 自验要求：改完 `docker compose build app` + `docker compose up -d app`（静态文件打在 jar 里，不重启看不到），确认容器 healthy 后用浏览器/接口核对上面 4 条，证据写进回报。Lead 会另开 Playwright 独立复核（含拦截模拟"有逐字稿但 AI 未跑成"的第四种情况）。

---

## T4.16 真内容质量验收（约 1.5h 机器时间；**6 任务 / 约 44 分钟配额**；D-103 之后的质量门）

> **状态：✅ 已完成（2026-11-16，第 37 份报告，D-107）**——small 223/224/225、medium 229/230/231；结论：默认 ASR 保持 small，另开 T4.16b 修 LLM 三问题。以下为原始卡，保留追溯。

> **要证明的事**：用**有内容的真实文件**验证「摘要 / 关键词 / 待办」到底好不好，并回答一个决策问题：**默认 ASR 模型要不要从 `small` 升到 `medium`**。
>
> **前置（硬性顺序）**：①Lead 先核完 00:10 自动化产物（含 235/235 + 冒烟）；②`GET /api/v1/settings` 查 `remaining_tasks ≥ 8`、剩余分钟 ≥ 60；不满足就报"配额未就绪"并停。
>
> **素材（Lead 已备好，勿改勿删）**：`D:\new\artifacts\T4.16\samples\` — `s1.mp3`（6.3min 音频）、`s2.mp4`（6.3min 视频）、`s3_10min.mp3`（10min，长音频裁段）。
>
> **步骤**：
> 1. **small 档（线上现状）**：上传 3 个样本（一次一个或批量均可），等 DONE；记录每个的 `task_id` / `ai_tier`（应为 CLOUD）/ 耗时 / RTF / transcript 字数；用详情页「导出 md」各存一份到 `D:\new\artifacts\T4.16\outputs\small\`。
> 2. **medium 档（临时切换，**不许改 `.env`**）**：写外部 override（`D:\new\artifacts\T4.16\override-medium.yml`，设 `ASR_MODEL_NAME=medium`），按 K-41/K-44 用 `docker compose -f docker-compose.yml -f <override> up -d asr` 起；确认 `/asr/health` 返回 `{"model":"medium","ready":true}` 再跑；同素材再跑 3 个任务，记录同样指标 + 导出到 `outputs\medium\`。
> 3. **立即恢复**：`docker compose up -d asr` 回基础配置；确认 `/asr/health` `model=small`、5/5 healthy、`.env` hash `2BCC440F1C9E` 不变（K-45 最后查配额）。
> 4. **归档日志**：`docker compose logs asr > D:\new\artifacts\T4.16\asr-small.log`（切换前）与 `asr-medium.log`（切换后）；app 日志同样落盘（K-39/K-43）。
> 5. **回报（测试三问）**：6 个 task id + ai_tier + 耗时/RTF + transcript 字数；**摘要/关键词/待办原文**（或导出文件路径）；两侧差异（转写文字是否更准、专有名词是否正确、待办是否更多/更准）；配额消耗；有没有出现 429/超时/降级。
>
> **禁止**：改提示词/改 Java/改 `.env`；跑全量测试（吃 3 任务）；上传 `samples` 之外的文件；把 medium 档留在线上。**medium 跑完必须切回 small**。
>
> **Lead 会做的对照**：Lead 用本地已有的 faster-whisper **medium/large-v3** 在临时容器里转写同一素材（**不占任务配额**），自己整理一份「参考答案（摘要/关键词/待办）」，再与产品 small/medium 两侧输出做三方对比，写进第 36 份验收报告与 D-106（是否升默认模型/是否调提示词）。

---

## T4.16b LLM 结构化质量三修（约 30–40 分钟；0 配额；纯后端 + 提示词）

> **状态：✅ 已完成（`885038b`，2026-11-16，第 38 份报告，D-108）**——我独立复核：`-UnitOnly` 231/231、可维护性 0/0、**三次变异测试全造红**、真实任务 232 摘要 134 字完整句+待办 0。以下为原始卡，保留追溯。

> **要证明的事**：T4.16 用 3 个真实素材实测发现三件伤用户的事（第 37 份报告 / D-107）：
> ①同一份转写稿两次结构化，待办条数 **0 vs 5**、摘要相似度 0.47（云档 `temperature` 用的是供应商默认值，没固定）；
> ②摘要 200 字被 **`substring` 硬切**，出现「…测试与证据链。**未明确**」「…**无明确课后作业/**」这种末句腰斩；
> ③待办把讲者的**演示动作**当听众任务（"登录 BOSS 账号并打开后台""查看后台沟通数据"），依据命中率 100% 但语义不是待办。
>
> **要求**（一个 commit 做完三件，不许顺手改别的）：
> 1. **云档 temperature 固定 0.0**：`app/src/main/resources/application.yml` 的 `spring.ai.openai.chat.options` 下加
>    `temperature: ${LLM_TEMPERATURE:0.0}`；同步 `.env.example` 与 `配置清单.md`（§1 键表、§2 快照 `$appKeys`）。
>    判据：启动后日志/单测能证明云档用 0.0（参照 `OllamaChatModelConfig.localChatOptions` 的做法：把云档选项构造抽成 package-private 静态方法并加单测断言 `temperature==0.0`）。
> 2. **summary 截断改句边界**：`StructuringService.truncate()` 只用于诊断日志，别动；为 summary 新增句边界截断（`len<=200` 原样；否则在 `[0,200)` 内从后往前找最后一个 `。！？!?`，找到就截到它含该标点；找不到再退回 200 硬截）。
>    判据：单测覆盖 `<=200 原样` / `201 字且 180 处有句号 → 截到 180` / `201 字无标点 → 截 200` / `正好 200 不改`；重跑一条真实任务（可用一次新上传或已有任务的修复后重结构化——若不方便，用单测 + 一个 E2E 断言证明）。
> 3. **待办提示词收紧**：`TodoService.promptSection()` 的 `3. todos ...` 段补充（保留原有 owner/deadline/null 规则，原文不要删）：
>    「只列 transcript 里**明确要求听众/参会者去做**的行动项；讲者/主播自己的演示动作（如"我登录一下账号给你看"）、举例、知识点罗列、流程讲解都不算待办；
>     教程/课程类通常 0–8 条，宁少不滥；确实没有就输出 {"todos": []}。」
>    同步更新因文案变化而变红的测试（`TemplatePromptsTest` 有旧文案硬拷贝，仅当它断言这段才改）。
>
> **自验（必须全做，证据落盘 `D:\new\artifacts\T4.16b\workbuddy\`）**：
> ```powershell
> cd D:\new\projects\meeting-notes
> powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test.ps1 -UnitOnly     # 期望 225+ 全绿、exit 0
> powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-maintainability.ps1  # 期望 exit 0
> docker compose build app; docker compose up -d app; docker compose ps   # app healthy
> ```
> 再用一次小体量真实任务（**最多 1 个任务**，可用 `D:\new\artifacts\T4.16\samples\tier2\s2.mp4` 换字节后上传，或直接复用已有任务走"修复后重新结构化"接口；避免重复上传同字节被 content_hash 复用）验证：摘要结尾是完整句子、待办里没有"登录/查看后台"这类演示动作。
> 证据：改前后同一输入的对比（JSON 或截图）+ 单测输出 + exit code。
>
> **禁止**：改 ASR/模型默认值、改 `templates` 五个模板的 summary 规则文案（只动 todo 公共段）、改前端、跑全量测试（吃 3 任务）、push、多 commit。
>
> **回报**：按仓库根 `AGENT.md` 的回报模板 + 上面三件各自的"改前 → 改后"证据。

---

## T4.17 音频要点（keypoints）功能（约 2–2.5h；0 配额跑单测 + 1 任务真机抽检；后端+前端）

> **状态：✅ 已完成（`66af59b`，2026-11-16，第 39 份报告，D-111）**——21 文件 +431/−117；我独立：`-UnitOnly` 240/240、**全量 250/250 exit 0（含 E2E 13 键）**、可维护性 0/0、**两处变异造红**、真实任务 237 要点 6 条依据可查。以下为原始卡，保留追溯。

> **要证明的事**：用户要求新增「音频要点」——详情页与导出 md 里多一节编号要点，格式同他给的样例：
> `1. **市场分化**：传统 Java 岗收缩、大厂基础开发编制缩减…`（编号 + 加粗小标题 + 冒号 + 说明）。
> 条数**按内容长度自适应**（短 3–6 条、长最多 15 条），每说明 40–120 字，内容必须来自转写、不得编造；
> 短内容（<3 分钟）不应硬凑条数。**待办功能保留不动**（用户决定先观察）。
>
> **改动范围**：
> 1. **DB（Flyway V3）** `app/src/main/resources/db/migration/V3__keypoints.sql`：`ALTER TABLE task_result ADD COLUMN keypoints jsonb;`（历史任务为 null，前端按"未生成"处理，语义同 todos 的 null）。
> 2. **结构化契约**：`StructuringResult` 增加 `keypoints`；JSON 输出增加 `keypoints: [{"title":"...","detail":"..."}]`；
>    `StructuringPrompt`/`TemplatePrompts` 要求段增加要点规则（自适应条数、title ≤20 字、detail 40–120 字、必须来自 transcript、禁止编造；考点/结论/数据/建议优先）。
>    解析容错：keypoints 缺失或形状不合法 → 视为该字段为空（null），**不影响 summary/keywords/todos 落库**（不要整条降级）。
> 3. **落库/查询**：`StructuringService` 写 keypoints；`TaskController` 的 `GET /tasks/{id}` 响应增加 `keypoints`（字段顺序无关，snake_case）；
>    历史任务返回 null。列表接口不动。
> 4. **前端详情页**（`static/task.html` + `app.js`/`style.css` 尽量少改）：新增「要点」区块（在摘要之后、待办之前），渲染为
>    `<ol>`：每条 = `编号` + `**加粗小标题**` + `：说明`；`null`/空 → 「AI 未启用或本次未生成，暂无要点。」；
>    手机 390 宽不溢出；不引入外部资源。
> 5. **导出 md**（`TaskMarkdownExporter`）：在「摘要」「关键词」之后加「## 要点」节：`1. **标题**：说明`；keypoints 为 null → 该节写「（未生成要点）」或省略该节（二选一，写清并保持稳定）。
> 6. **配置**：若新增任何键（预计不需要），同步 `.env.example` 与权威仓 `配置清单.md`。
>
> **判据（必须自测并给证据）**：
> - `-UnitOnly` 全绿（现有 231 + 新增用例，至少覆盖：keypoints 正常解析、缺失/坏形状容错、API 返回、导出含要点节）；
> - 可维护性 `0/0` exit 0、单文件 ≤300 行；
> - 用**已有真实任务**（如 231：10 分钟、4062 字）跑一次"重新结构化"或用新字节文件上传 **1 个任务**，确认：要点 3–15 条、每条 40–120 字、抽样 2 条能在转写里找到依据、导出 md 含要点节；截图详情页 + 导出文件；
> - 证据落 `D:\new\artifacts\T4.17\workbuddy\`；**单 commit**（`feat(T4.17): ...`）。
>
> **禁止**：删/改待办功能；改 ASR；动 T4.16b 的 temperature/截断/提示词（除新增要点规则）；前端引入外部资源；改 `.env` 持久值；push。

---

## T4.18 转写一句一行（AI 加标点）+ 导出勾选/全选（约 2.5–3h；0 配额单测 + ≤2 任务；后端+前端）**✅ 已完成（`abf4649`，2026-11-16，第 40 份报告，D-112）**

> **要证明的事**：
> ① 现在 ASR 输出没标点，详情页转写是一整段。用户要求**一句一行**；方案已定：**AI 加标点后再按句换行**（失败回退原始转写，不降级、不影响 `ai_tier`）。
> ② 导出的 markdown 要**让用户勾选导哪些内容**（摘要/关键词/要点/转写/待办），支持**一键全选**；默认勾选 摘要+关键词+要点+转写（**不含待办**，待办观察期）。
>
> **改动范围**：
> 1. **DB（Flyway 新版本，编号顺延）**：`ALTER TABLE task_result ADD COLUMN transcript_punctuated text;`（历史任务 null）。
> 2. **标点服务（新类或并入现有 AI 包）**：在结构化成功后、同一 worker 内**追加一次独立 LLM 调用**（CLOUD 可用时才做）：
>    - 输入：transcript；输出：**只加标点、不加不删不改字**的全文；
>    - 触发条件：transcript 非空且长度 ≤ `LLM_PUNCTUATE_MAX_CHARS`（新增配置键，默认 6000；设 0 可关闭）；
>    - **失败/超时/长度不符 → 存 null，任务照样 DONE**（D-068：LLM 失败不许拖垮任务）；不得因为标点失败而降级 ai_tier；
>    - 记录日志：耗时、字数、是否成功（不打印全文）。
> 3. **API**：`GET /tasks/{id}` 增加 `transcript_punctuated`（null 表示没有/未生成）；历史任务 null。
> 4. **前端详情页**：转写区优先用 `transcript_punctuated`，按 `。！？` 切句**一句一行**（无标点则回退现有 `mnSplitSentences` 逻辑）；
>    「复制全文」与关键词/待办依据定位都以**展示的文本**为准（定位算法保持现有 normalize+bigram 口径，不得回归）；纯文本无标点时按现有方式渲染。
> 5. **导出 md 可选择内容**：`GET /tasks/{id}/export?format=md&include=summary,keywords,keypoints,transcript,todos`
>    - 合法值集合固定为这 5 个；未知值 → 400 `INVALID_PARAM`（消息列合法值）；`include` 省略 → 默认 `summary,keywords,keypoints,transcript`（**默认不含待办**）；
>    - `include` 为空/全空 → 400；顺序始终固定为 摘要→关键词→要点→转写→待办（只输出被选中的节）；
>    - 转写节用 transcript_punctuated（有则用），按句换行；空 transcript 仍按现有规则处理（400 INVALID_PARAM）；
> 6. **前端导出区**：5 个复选框 + 「全选」+「下载 Markdown」；至少勾 1 项才可下载（否则按钮禁用+提示）；勾选状态只存页面内存即可；
>    手机端可正常操作。
> 7. **配置**：`LLM_PUNCTUATE_MAX_CHARS` 同步 `.env.example` + 权威仓 `配置清单.md`（§1 键表 + §2 快照注记口径同 T4.16b）。
>
> **判据**：
> - `-UnitOnly` 全绿（新增用例覆盖：标点成功/失败回退/null、长度超限跳过、include 解析/默认值/非法值/空值、导出各节有无、转写换行）；
> - 可维护性 0/0、单文件 ≤300 行；
> - 按约定跑 **≤2 个真实任务**（16 分钟以内音频各一；用一个"无标点 ASR 输出"素材，如 `artifacts\T4.16\samples\tier3\s2.mp4` 的改字节副本）验证：
>   transcript_punctuated 生成成功、详情页一句一行、复制全文=页面文本、导出勾选"全选"含 5 节 / 只勾"转写"只 1 节、非法 include 400；
> - 证据落 `D:\new\artifacts\T4.18\workbuddy\`；**单 commit**（`feat(T4.18): ...`）。
>
> **禁止**：改 T4.16b 三修；改 ASR 服务；把标点失败当任务失败；改 `.env` 持久值；push；多 commit。

---

## T4.19 会议待办语义 + medium 提示（约 40–60 分钟；0 配额；提示词 + 静态页 + 文档）**✅ 已完成（`48df935`，2026-11-16，第 41 份报告，D-113）**

> **要证明的事**：真实会议实测（task 235/236，8 条待办全可用）后确认待办功能保留，但补三处：
> ①会议模板提示词区分「**已议定待办 / 讨论中未定 / 纯知识**」，避免把"可以考虑…"算成待办；
> ②owner 只在原文**明确点名**负责人时才填（现有规则不动，只补一句强调 + 单测断言，未来说话人分离=T7.12 再增强）；
> ③会议类素材**建议用 medium 模型**：设置页加一行提示 + README 写明（**只做提示，不改默认值**）。
>
> **改动**：
> 1. `TodoService.promptSection()`：在现有三行规则后加一句（不删原文）：
>    「区分三类：已议定的待办（明确要某人/某团队去做）→ 列入；讨论中未定（如"可以考虑""再看一下"）与纯知识/观点 → 不列入；owner 只有原文明确点名负责人才填，否则 null。」
>    同步 `TodoServiceTest` 断言新文案；`TemplatePromptsTest` 若断言公共段一并更新。
> 2. 设置页（`static/settings.html`）：在 AI 档位区域加一行灰色小字提示：
>    「会议/远场录音建议使用 medium 语音模型（更准但更慢，需自备约 1.5GB 模型，配置 ASR_MODEL_NAME=medium）」；
>    不加交互、不发请求、手机 390 不溢出、对比度 ≥4.5:1、不引入外部资源。
> 3. `README.md`（或 `docs/接入指南` 对应节）：加"模型选择建议"三行——清晰单人录音 small 够用；会议/远场/多人建议 medium（RTF 约 0.38 vs 0.16，本机 CPU 实测）；medium 需自备模型文件。
>
> **判据**：`-UnitOnly` 全绿（含新断言）；可维护性 0/0；启动重建容器后截图设置页提示 + 手机 390 不溢出；单 commit（`feat(T4.19): ...`）；0 任务配额。
>
> **禁止**：改默认 ASR 模型/config 默认值；改前端其他部分；跑全量测试；push。

---

## T4.20 上传交互修复（约 1–1.5h；0 配额；**纯 static**；D-114）**✅ 已完成（`5be3a4e`，2026-11-16，第 43 份报告，D-116；我自写 Playwright 14/14 复验）**

> **要证明的事**（用户真机验收 T4.13 发现，已在代码核实机制）：
> ① 现在**分批次选文件会覆盖前一次**：`index.html` 的 `takeFiles()` 每次把 `mirror` 换成本次列表，`fileInput change` 又把 `mirror=null` → 只有"一次全选"才能都上传；
> ② 已选文件显示在**拖拽框下方**的独立 `<ul id="fileList">`，不在框内，看起来像另一个列表；
> ③ 任务列表/最近任务的「详情」是**同标签打开**，点开就把列表顶掉，想同时看多个详情得反复后退。
>
> **改动（只改 `static/index.html`、`static/tasks.html`，必要时 `style.css`）**：
> 1. **累积选择**：维护一个 `selected` 数组（元素 `{file, key}`，key = `name|size|lastModified`）；
>    - 文件选择器 `change` → 把新选的文件**追加**进 `selected`（按 key 去重，重复的静默跳过）；
>    - 拖拽 `drop` → 同样**追加**（不替换）；
>    - 上限 20（与 `MAX_BATCH_FILES` 一致）：超出的文件丢弃并提示"一次最多 20 个，已忽略 N 个"；
>    - 超过 200MB 的单文件拒绝并提示（保留现有 `MAX_UPLOAD_BYTES` 校验，但改为在选择时就提示并跳过）；
>    - 提交时用 `selected` 构造 FormData（**不要依赖 `input.files` 赋值**——老浏览器不支持，保留 mirror 作为兜底语义，但以 `selected` 为准）；
>    - **提交成功后清空 `selected`** 并把下拉区恢复初始文案；提交失败**保留**选择供重试。
> 2. **已选列表移进拖拽框内**：把 `<ul id="fileList">` 移到 `#dropZone` 内部（框底部），每行显示文件名 + 大小 + **「移除」按钮**；无选择时框内保持原提示（点击/拖拽文案）；`has-files` 时框内提示缩小为一行"已选 N 个文件，点击或拖拽可继续添加"。
> 3. **详情链接新标签页**：`tasks.html` 两处 `详情` 链接加 `target="_blank" rel="noopener"`；首页"最近 3 条"的详情链接（若有）同样处理；详情页已有返回/导航，不动。
> 4. 键盘可达性保持：拖拽框 `Enter/空格` 打开选择器；「移除」按钮 `Tab` 可达、`aria-label="移除 <文件名>"`；不引入外部资源。
>
> **判据（必须自测并给证据）**：
> - 桌面手测（Playwright 或真实浏览器）：选 2 个 → 再选 1 个 → 列表显示 **3 个**（不覆盖）；点某个「移除」→ 剩 2 个；拖拽追加 1 个 → 3 个；选第 21 个 → 提示忽略；提交后列表清空；
> - 上传**真的成功**：用 `artifacts\T4.13\files\` 里的文件，确认任务提交数 = 选中的文件数（可查 `/api/v1/tasks` 最新几条）；自验最多 **2 个**任务（配额 0 指本卡不改后端，但上传真跑会计任务配额——**允许 ≤2 个**，能复用已有任务就复用）；
> - 列表页「详情」点击后：新标签页打开 `task.html?id=…`，原列表页仍在；
> - 手机 390 宽：框内列表 + 移除按钮不溢出、可点；
> - `-UnitOnly` 全绿 exit 0、可维护性 0/0（纯静态也应跑，防误伤）；
> - 单 commit（`fix(T4.20): ...`）；证据落 `D:\new\artifacts\T4.20\workbuddy\`。
>
> **禁止**：改后端/契约/DB/.env；改其它页面布局；引入外部资源；跑全量测试；push；多 commit。

---

## T4.21 任务辨识信息 + 删除任务（约 4–5h；0 配额；后端+前端；D-114/D-115）**✅ 已通过（2026-11-16，`de031b2`，第 44 份报告，D-117；我独立 273/283 全绿 + 接口 48/49 + UI 31/31；遗留 P2 → T4.21b）**

> **要证明的事**：用户真机验收（T4.13）反馈"列表看不出内容、而且只能增不能删，越积越多"：
> ① 列表接口没有摘要/关键词/模板 → 长文件名+相似命名难分辨；
> ② 全仓**没有删任务接口**（只有删待办），列表也没有删除入口。
>
> **已确认口径（D-115）**：辨识三件套（关键词 chips ≤3 + `+N`、摘要首句 ≤40 字、模板徽章）+ 单条/多选/**全选**删除 + **一键清空失败任务**；二次确认写明"不可恢复、配额不退还"；**不做**"清空全部任务"、**不做**并排分栏。
>
> **改动**：
> 1. **列表接口只增字段**（`GET /api/v1/tasks` 的 items 元素，**只增不改**，D-041）：
>    - `keywords`（string 数组，来自 `task_result.keywords`，null/坏 JSON → `[]`）
>    - `summary_head`（`task_result.summary` 前 40 字，null 安全；是否加省略号由前端决定）
>    - `template`（`task.template`，旧任务 null）
>    - 实现：`TaskQueryService` 的 keyset 分页 SQL 加 `LEFT JOIN task_result r ON r.task_id = t.id`，三列一并查出（注意分页/排序不受影响）；详情接口 14 键**不动**。
> 2. **列表展示（`static/tasks.html`）**：每行在状态徽章**前面**加：
>    - 关键词 chips：最多 3 个（超出显示 `+N`），无关键词不显示；
>    - 摘要首句：`summary_head` 灰字一行（≤40 字 + 省略号），无摘要不显示；
>    - 模板徽章：会议/课程/访谈/播客/短视频（复用详情页 `TEMPLATE_TEXT` 映射；null 不显示）；
>    - 手机 390：chips/摘要单行省略（`text-overflow: ellipsis`），不横向溢出；**不加新请求**（列表接口一次拿全）。
> 3. **删除接口**（三条，契约写进 `接入指南.md`，响应字段 snake_case）：
>    - `DELETE /api/v1/tasks/{id}`：不存在 → 404 `TASK_NOT_FOUND`；`status` 为 PENDING/RUNNING/RETRYING → **409 `TASK_BUSY`**；成功 → 200 `{"deleted":1}`。
>    - `POST /api/v1/tasks/delete` body `{"ids":[…]}`：1–100 条；逐条返回 `{"results":[{"id":1,"deleted":true}|{"id":2,"deleted":false,"reason":"NOT_FOUND"|"TASK_BUSY"}]}`，**不整体失败**（口径同 T4.1 批量重试）；重复 id 去重。
>    - `POST /api/v1/tasks/delete-failed`：删当前用户全部 `status='DEAD'` 的任务（不限已加载行），返回 `{"deleted":N}`；`RETRYING` 不算失败、不删。
>    - **删除事务**：删 `task` / `task_result` / 关联 `dead_letter` / `webhook_delivery`（按 task_id）；**MinIO 对象删除 best-effort**（异常只 WARN + 记 taskId，**不回滚 DB 行**）；**配额不退还**（错误信息/文档写明）。
> 4. **前端删除入口（`static/tasks.html` + `static/task.html`）**：
>    - 列表：复用现有多选（`#checkAll` 已有）→ 操作条加「删除所选」（红色/危险样式）+「清空失败任务」按钮（无 DEAD 时禁用或隐藏）；**全选删除 = 勾选当前已加载行**，前端按 ≤100/批分批调 `POST /tasks/delete`；
>    - 详情页：底部加「删除此任务」按钮；
>    - 两个删除入口都要**二次确认**（`confirm` 或页内确认条）：文案"删除后不可恢复，已用配额不退还"；成功后列表就地刷新（保留当前筛选/搜索状态），详情页删除成功后跳回列表；
>    - 手机 390 可操作、按钮不溢出。
> 5. **测试**：`TaskQueryServiceTest`（三字段有/无/坏 JSON、分页不受影响）、`TaskControllerTest`（单条 404/409/成功、批量部分成功+重复 id、清空失败任务只删 DEAD）、`E2ETest`/`TaskListResponse` 契约（列表元素键集**只增**；详情 14 键不变）。
>
> **判据（必须自测并给证据）**：
> - `scripts\test.ps1 -UnitOnly` 全绿（含新增用例）；**改到契约 → 先 `docker compose build app && docker compose up -d app` 再跑全量 `scripts\test.ps1`**（K-68），记录 head.txt + 用例数；可维护性 0/0；
> - 真机/接口验证（≤3 任务配额）：列表能看到关键词/摘要首句/模板；删单条 → DB 里 `task`/`task_result` 行都没了、列表行消失；对运行中任务删 → 409；批量删 2 条 → 逐条结果；清空失败任务 → 只删 DEAD、返回条数；
> - 手机 390 宽：新元素不溢出、删除按钮可点；二次确认文案含"不可恢复/配额不退还"；
> - 证据落 `D:\new\artifacts\T4.21\workbuddy\`；单 commit（`feat(T4.21): ...`）。
>
> **禁止**：改详情接口既有的 14 键/导出契约；退还配额；MinIO 失败导致 DB 行残留；删 237/256 等验收证据任务（自验用新任务）；**做"清空全部任务"**；push；多 commit。

---

## T4.21b 清空失败任务补 MinIO 清理 + 防回归测试（约 0.5–1h；0 配额；后端；D-117 发现的 P2）**✅ 已通过（2026-11-16，`a1cc93f`，第 45 份报告，D-118；我独立 279/279 + 探针 19/19）**

> **要证明的事**：T4.21 验收时我用**真实对象探针**实锤——任务 286 转写 DONE 后 SQL 置 `DEAD`，走 `POST /api/v1/tasks/delete-failed`，DB 行（task/task_result）清 0，但 MinIO 里 `meetingnotes/tasks/286/lead-t421-c.mp3` **仍在**。同一 commit 的另外两条删除路径（单条/批量）都调了 `purgeObjectBestEffort`，**只有"清空失败任务"漏了** → 失败任务的音频会永久堆在对象存储里（用户"越删越多"的另一半）。
>
> **改动**（只做这一件事）：
> 1. `TaskDeleteService.deleteAllFailed()`：`deleteOne()` 已返回 `sourceUri`，对每个 `deleted=true` 的结果**在事务外**调 `purgeObjectBestEffort(id, sourceUri)`（口径与 `TaskDeleteController.deleteTask/deleteBatch` 一致：S3 失败只 WARN、不回滚 DB、不抛）。
> 2. `TaskDeleteService.deleteOneInTx()` 的状态查询加行锁：`SELECT status FROM task WHERE id = ? AND user_id = ? FOR UPDATE`（T4.21 验收 P3：读状态与删行同事务但无锁，仍有毫秒级竞态窗口）。
> 3. 测试（`TaskDeleteServiceTest` 补）：
>    - `deleteAllFailedPurgesObjectForEachDeletedTask`：2 个 DEAD → `verify(s3, times(2)).deleteObject(...)`（**可证伪：不调用即红**）；
>    - `deleteAllFailedS3FailureStillDeletesAllAndReturnsCount`：S3 抛 RuntimeException → 不向上抛、返回条数正确、DB DELETE 全部执行；
>    - 现有 7 例保持全绿；如成本低可顺手加 2 个 controller 直测（`new TaskDeleteController(mockService)` 断言 404/409 的 ApiException code/status、成功返回 `{"deleted":1}`），不做 MockMvc 也行。
>
> **判据（必须自测并给证据）**：
> - `scripts\test.ps1 -UnitOnly` 全绿（用例数 ≥273 + 新增），可维护性 `check-maintainability.ps1` **0/0**；
> - **真机探针（0 配额）**：找一个已 DONE 的任务（**别用 237/256 验收证据任务**），`docker compose exec -T postgres psql -U mn -d meetingnotes -c "UPDATE task SET status='DEAD', error_code='ASR_ERROR' WHERE id=<id>"` 置 DEAD → 记下 `source_uri` 对应 MinIO 目录存在（`docker compose exec -T minio sh -c "ls /data/meetingnotes/tasks/<id>"`）→ 调 `POST /api/v1/tasks/delete-failed`（`Content-Type: application/json` + body `{}`，带 Bearer）→ 断言：返回条数对、`task`/`task_result` 0 行、**MinIO 目录消失**；
> - 单 commit（`fix(T4.21b): ...`），不 push、不改列表三字段/详情 14 键/其它契约、不退还配额。
>
> **禁止**：把 purge 放进事务内（S3 慢/失败会拖长事务或造成回滚）；改前端；多 commit；删 237/256 等验收证据任务；为了验证再上传新任务（复用现有 DONE 任务即可，0 配额）。
>
> 证据落 `D:\new\artifacts\T4.21b\workbuddy\`（单测日志、探针命令 + 前后 `ls` 输出、DB 行查询）。

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
