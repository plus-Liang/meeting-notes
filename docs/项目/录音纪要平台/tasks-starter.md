# 任务卡（给写代码的 AI）—— 第一批：阶段 0 + 第 1 周前 3 项

> 用法：**一次只做一张卡**。做完贴证据（命令 + 输出 + exit code），等人确认后再做下一张。
> 权威任务总表是 `tasks.md`；本文件是"可直接执行版"，字段：目标 / 步骤 / 验证 / 通过标准 / 失败预案。
> 全部命令在 `D:\new\projects\meeting-notes` 下执行，并先设好环境（见 `AGENT.md` §4）。

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

## T1.1 代码仓骨架收尾

**目标**：仓库干净、可追溯。
**步骤**：确认 `.gitignore` 生效（`.env` 不被跟踪）；一次 commit 只做一件事；提交信息 `<type>(<scope>): <做了什么>`。
**验证**：`git status --short` 干净；`git log --oneline` 可读；`git ls-files | Select-String '\.env$'` 为空。

---

## T1.2 docker compose 一键起（pg / redis / minio / asr / app）

**验证**
```powershell
docker compose down -v
docker compose up -d
docker compose ps        # 全部 healthy
```

**通过标准**：干净环境下一次成功，全部服务 healthy（**不要用 `sleep` 等依赖，一律 healthcheck**）。

---

## T1.3 Flyway 建表生效

**验证**：`docker compose exec postgres psql -U $env:POSTGRES_USER -d $env:POSTGRES_DB -c '\dt'`
**通过标准**：看到 `task` / `task_result` / `glossary` / `speaker_profile` / `quota` / `dead_letter` / `webhook_delivery` 七张表；重复执行迁移不报错。

---

## T1.4 上传接口（校验 → MinIO → 写 task）

**要做**：`POST /api/v1/tasks`（multipart）；校验顺序：文件头 magic number → 大小 ≤200MB → 时长 ≤60min → 配额；
通过后存 MinIO（按 taskId 分目录），写 `task` 表（status=PENDING，content_hash 计算好）。

**验证**
```powershell
# 坏文件：把 exe 改名成 .mp3 → 期望 400 INVALID_MEDIA
# 好文件：期望 201 + {"task_id":...}
```
**通过标准**：两条各按预期；`content_hash` 落库。

---

## T1.5 worker 消费出转写结果

**要做**：worker 取 PENDING → RUNNING → 容器内 ffmpeg 抽 16k 单声道 → 调 ASR → 写 `task_result` → DONE。

**验证**：`GET /api/v1/tasks/{id}` → `status=DONE` 且 `transcript` 非空（用真实 5 分钟 mp3）。
**通过标准**：端到端一次成功；失败时状态迁移正确（不卡 RUNNING）。

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
