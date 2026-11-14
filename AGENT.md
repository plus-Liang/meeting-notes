# AGENT.md —— 给「写代码的 AI」的入口（WorkBuddy / Codex / Cursor / Claude Code 都读这个）

> 你只负责**写代码**。需求、取舍、任务清单在 `docs/项目/录音纪要平台/`，**不许自己改需求**。
> 人类（项目作者）是产品经理；你按任务卡执行，每步给证据。

---

## 0. 项目一句话

**给别人用的**开源自托管「录音 / 网课 / 短视频 → 结构化纪要 + 待办」Web 应用（作者自己也是第一用户）。
技术栈：Java 21 + Spring Boot 3 + Spring AI + PostgreSQL + Redis + MinIO + Docker Compose。
**不是**飞书妙记的替代品，服务的是"手里一堆本地文件 / 数据不能出门 / 要接进自己系统 / 短内容囤积"这四类人。

---

## 1. 开工前必读（按顺序，别跳）

| 顺序 | 文件 | 只读哪几节 |
|---|---|---|
| 1 | `docs/项目/录音纪要平台/00-先看这个.md` | 全部（10 分钟看完全貌） |
| 2 | `docs/项目/录音纪要平台/spec.md` | §1 定位、§4 输入契约、§5 页面结构、§7 非功能要求 N1–N20 |
| 3 | `docs/项目/录音纪要平台/plan.md` | §3 架构、§5.1 三档 AI、§5.3 链接导入、§6 数据模型 |
| 4 | `docs/项目/录音纪要平台/配置清单.md` | 全部（配置项一个不落） |
| 5 | `docs/项目/录音纪要平台/tasks-starter.md` | **当前要做的任务卡（从这里开始）** |

其它文档（`decisions.md` / `接入指南.md` / `环境搭建说明.md` / 代码骨架.md）**用到再查**，不要一次全读。

---

## 2. 十条硬约束（违反任一条 = 返工，不接受"先做进去以后再改"）

1. **只做 Web 交付**：Spring Boot API + 4 个页面 + Swagger。**不做**小程序、桌面客户端、浏览器插件。
2. **不自研模型**：ASR / LLM 一律外部调用（OpenAI 兼容协议）。代码里不许出现模型权重、训练脚本。
3. **ASR 是独立服务**：Java 侧只调 `POST /v1/audio/transcriptions`，不许 JNI/内嵌模型。
4. **配置一个不落**：路径 / 端口 / key / 并发数 / 模型名全部走环境变量，`application.yml` 只用 `${ENV:default}`。硬编码一处即返工。
5. **零成本可跑**：不填任何 API key 也必须能跑通全流程（ASR 本地 + 摘要三档降级 CLOUD→LOCAL→EXTRACTIVE）。
6. **闭环必须有回流**：用户改错词 → 进术语表 → 下次转写生效。没有 ⑤修正/⑥沉淀 不算完成。
7. **媒体处理全在容器内**：宿主机不装 ffmpeg（本机 Windows Smart App Control 会拦未签名二进制）。
8. **单任务上限 60 分钟 / 200MB；批量上限 20**（真实数据实测：45% 的文件超过 30 分钟）。
9. **摘要模板只有 5 个**（会议 / 课程 / 访谈 / 播客 / 短视频），一份管线 + 5 套提示词，**不许为场景新增管线**。
10. **不做内置平台解析器**：链接只做 L1 公开元信息确认 + L2 直链探测 + BYOF 接口。**不许**集成 yt-dlp、不许逆向签名、不许存用户 cookie。

---

## 3. 当前进度与下一张卡（第 2 周收尾）

| 阶段 | 状态 |
|---|---|
| 阶段 0 底座（T0.1–T0.5） | ✅ 全过：RTF **0.105/0.113**、无 key 可启动（`ChatModel` Bean=0）、5/5 healthy |
| 阶段 1（T1.1–T1.9） | ✅ 全过（含 T1.9 测试入口）。T1.5 实测：397s 中文音频 → **31s** 出 1853 字；停 ASR **9s** 走完 `RETRYING×2 → DEAD` |
| 第 2 周（T2.1–T2.12） | ✅ 全过（T2.9 主体通过；3 处遗留见下） |
| **下一张卡** | **T3.0 W3 前置小修**（`tasks-starter.md`）：`test.ps1` 跳过 `.env` 的 `ASR_BASE_URL`（D-066）、`MAVEN_OPTS` 编码、compose `TZ`（D-067，人类点头后）；**之后 T3.1 摘要生成**（云 `ChatModel` → summary/keywords/ai_tier） |

任务总表 `tasks.md`；起栈 `docker compose up -d`（5/5 healthy 才算通过）。**一次只做一张卡，先贴证据再要下一张。**
完成后按 §5 格式回报；`state.md`/`handoff.md` 里的"下一步"与本节同步更新。

---

## 4. 本机环境（已实测，照这个来）

```powershell
# JDK 21 + Maven（已装在 E:\tools，不要改全局 JAVA_HOME）
$env:JAVA_HOME='E:\tools\jdk-21.0.2'
$env:PATH="E:\tools\apache-maven-3.9.9\bin;$env:JAVA_HOME\bin;$env:PATH"
mvn -v        # 期望：Maven 3.9.9 + Java 21.0.2

# Docker：Hub 不通，所有镜像必须加加速前缀
docker.m.daocloud.io/<image>

# Python 包：用清华源
pip install -i https://pypi.tuna.tsinghua.edu.cn/simple <pkg>
```

**本地已有资源（不要重复下载）**：
- `python:3.12-slim` 镜像（本机已有）
- faster-whisper 模型 **4.21 GB**：`D:\huggingface\hub\models--Systran--faster-whisper-{small,medium,large-v3}`

---

## 4.1 已验证的实现事实（别重复踩，2026-11-13 实测）

| 事实 | 说明 |
|---|---|
| **`ASR_BASE_URL` 不带 `/v1`** | `spring.ai.openai.audio.transcription.base-url = http://asr:8000`；Spring AI 1.x 内部追加 `/v1/audio/transcriptions`（`javap` 已核对）。写 `/v1` 会 404。见 D-034 |
| 转写属性前缀 | **`spring.ai.openai.audio.transcription.*`**（必须带 `openai`，少了整段配置不生效） |
| `response-format` | 必须 `json`（默认 `text`，自建 ASR 返回 `{"text":...}`，用 text 会解析失败） |
| ASR 服务 | `infra/asr/` 已建好并验证：`docker compose up -d asr` → healthy；`av==13.1.0` 必须锁定（19 会 TypeError） |
| ASR 端口 | 容器内固定 8000，宿主机走 `${ASR_PORT}:8000`；**本机 8000 常被别的项目占用 → 用 8001 测试** |
| 模型 | 本机 `D:\huggingface` 里只有 `small` 完整（461MB），`medium`/`large-v3` 不完整 |
| 中文 | 必须 `initial_prompt`（否则可能繁体）；`small` 专有名词会错，靠术语回流补 |
| pom 版本 | Boot 3.5.16 / Spring AI 1.1.8 / Java 21 / AWS SDK BOM 2.59.0（`s3` 必须由 BOM 管版本，否则 Maven 读不了 POM） |
| 测试命令 | `$env:JAVA_HOME='E:\tools\jdk-21.0.2'; $env:ASR_BASE_URL='http://localhost:8001'; $env:ASR_SMOKE_AUDIO='D:\datasets\meeting-notes-samples\probe.wav'; mvn -q -Dtest=AsrSmokeTest test` |
| 控制台编码 | Java `stdout.encoding=GBK`，用 **PowerShell** 跑验证（Bash 捕获中文会乱码） |
| **云 LLM（DeepSeek，T0.5 实测）** | `LLM_BASE_URL=https://api.deepseek.com` + `LLM_CHAT_COMPLETIONS_PATH=/v1/chat/completions` + `LLM_MODEL=deepseek-flash`；`GET /models` 实测返回 `deepseek-flash` / `deepseek-v4-pro`；`deepseek-chat` 只是别名。**thinking 模型 `LLM_MAX_TOKENS` 太小会返回空 content**（默认 1024） |
| **无 key 可启动（D-035/D-036）** | `LlmModelSwitchEnvironmentPostProcessor` 在启动早期算 `app.llm.enabled`；无 key 时 `spring.ai.model.chat=none`，`ChatModel` Bean 数为 0。**不要**用填假 base-url 或 exclude 自动配置类来实现 |
| **换供应商要注意 path** | 智谱 `base-url=.../api/paas/v4` 要配 `LLM_CHAT_COMPLETIONS_PATH=/chat/completions`（否则拼出 `/v4/v1/...`）；OpenAI/DeepSeek 用默认 `/v1/chat/completions` |
| **Flyway 列名** | `glossary.wrong_text` / `right_text`（D-037 改名，**原名 `"right"` 是 PG 保留字**）。真实 PG16 已验证：空卷 `up` → 7 表 + `flyway_schema_history` |
| **H2 测试绕过 Flyway** | `NoKeyStartupTest` 用 H2 + `flyway.enabled=false`，**SQL 改动它验不到** → 改迁移脚本必须手工在真实 PG16 跑一遍 |
| 本机环境坑 | ①宿主会注入 `SERVER__PORT`（撞端口）→ 用 `--server.port=18080` 覆盖；②curl 本机端口要 `--noproxy "*"`；③`docker run -v D:\...:/mig:ro` 偶发容器卡在 `Created` 起不来 → 改用 `docker cp`；④**宿主 shell 注入了 `http_proxy`/`HTTP_PROXY`（重复键）**，代理半死时 curl 连 `127.0.0.1` 都会静默失败（exit 1 无输出/被 SIGTERM）→ 先 `unset http_proxy HTTP_PROXY https_proxy HTTPS_PROXY all_proxy ALL_PROXY` 再 curl，或用 `env -u ...` 单条清除 |
| **镜像来源（D-038）** | `REGISTRY_PREFIX` 只拼接 `library/` 镜像（postgres/redis/maven/temurin）；**非 library 镜像（minio/ollama）加速源会 `denied`**，用 `MINIO_IMAGE`/`OLLAMA_IMAGE` 整体覆盖。本机：前缀 `docker.m.daocloud.io/`，minio/ollama 走 `swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/<image>` |
| **app 镜像（T1.2）** | 基础镜像 JDK **21**（原 17 与 pom 冲突）；apt 源在 deb822 格式 `/etc/apt/sources.list.d/*.sources`，换源必须 `grep -rlE ... /etc/apt | xargs -r sed -i`（`sed sources.list*` 会匹配到目录静默失效）；`ARG APT_MIRROR` 可覆盖 |
| **无 key 验证（D-039）** | 三条件：`LLM_ENABLED` 留空 + 三个 `LLM_*` 全空 + **独立容器探针**。**禁止 `mv .env`**：会让镜像变量一起消失、容器没重建，200 来自旧容器（假绿灯）。`LLM_ENABLED=true` 无 key 崩是预期（显式优先） |
| **连库** | `docker compose exec -T postgres psql -U mn -d meetingnotes`（`postgres` 角色不存在）；`down -v` 后 `up -d` 全 5 个服务 healthy 才算 T1.2 通过 |
| **T1.5 worker 事实** | Redis **BLPOP**（阻塞取，非轮询）单线程；容器内 ffmpeg 抽 16k 单声道 → 复用 `TranscriptionModel`；成功写 `task_result` + `DONE`；失败 `attempts+1` → `RETRYING` 重新入队（队尾）→ 耗尽 `DEAD` + `dead_letter`。**`error_code` 列宽 64 只放稳定码**（`ASR_ERROR`/`STORAGE_ERROR`），完整原因写 `dead_letter.reason`（TEXT）——把异常消息塞进 `error_code` 会让写回失败、任务反卡 RUNNING |
| **Spring AI 内部重试（D-046）** | `OpenAiAudioTranscriptionModel` 走 `RetryUtils.DEFAULT_RETRY_TEMPLATE`，**默认 10 次指数退避**（ASR 挂掉时单次尝试 7 分钟才交给外层）→ 已用 `spring.ai.retry.max-attempts=2` / `backoff 2s~10s` 收敛；**重试语义只归 worker 状态机** |
| **`GET /api/v1/tasks/{id}`（T1.5）** | 查询带 `user_id`（D-040）；不存在 → `404 TASK_NOT_FOUND`（固定码第 7 个，D-047）；`duration_sec` 等 INT4 列必须 `getLong + wasNull`（强转 `Long` 会 500）；`transcript: ""` = 未识别到语音、任务仍是 DONE（D-048） |
| **ASR 超时（T2.2/D-058）** | connect **5s** + read **900s**，由 `config/AsrHttpClientConfig` 统一设置（`ASR_CONNECT_TIMEOUT_SEC`/`ASR_TIMEOUT_SEC`）；**探活/其它调用不许复用 900s**（健康探活另用 2s，D-059）。停 ASR 到 DEAD 由 78s 降到 **7.7s** |
| **LLM 超时（D-068，T3.1 落）** | 现有 `RestClientCustomizer` 对所有 RestClient 是 900s —— LLM 同步调用会拖死单线程 worker。T3.1 改为**按 URI 路由**：`/v1/audio/transcriptions` 900s、chat 走 `app.llm.timeout-sec`（`LLM_TIMEOUT_SEC` 默认 **120s**）；摘要失败不阻塞 DONE |
| **健康检查（T2.4/D-059）** | `health/MinioHealthIndicator`（headBucket + 2s）与 `health/AsrHealthIndicator`（GET `{base-url}/health` + 独立 2s 工厂）；readiness 组 = `readinessState,db,redis,minio,asr`；任一依赖挂 → `/actuator/health` **503 DOWN**；测试里用 `management.health.minio/asr.enabled=false` 关闭 |
| **结构化日志（T2.7/D-062）** | `LOG_JSON`（默认 true）→ Boot 内建 `logging.structured.format.console=logstash`；`taskId` 是**独立字段**（MDC，处理结束清理）；查一个任务：`docker compose logs app \| grep '"taskId":"<id>"'`；`LOG_JSON=false` 回退可读文本 |
| **备份/恢复（T2.8/D-063）** | `scripts/backup.ps1`（`pg_dump -Fc` + MinIO volume tar + manifest 行数/sha256）、`scripts/restore.ps1 -Force`；**恢复前 postgres/minio 必须先 running**；备份**不含 `.env`**；自动备份交宿主 cron（README 有示例） |
| **错误码两层（D-047/D-057）** | 业务码 **7 个**（含 `TASK_NOT_FOUND`/`QUOTA_EXCEEDED`/`STORAGE_ERROR`/`ASR_ERROR`）+ 通用码 **5 个**（`INVALID_PARAM`/`NOT_FOUND`/`METHOD_NOT_ALLOWED`/`UNSUPPORTED_MEDIA_TYPE`/`INTERNAL_ERROR`）；兜底 handler **不得吞框架状态码** |
| **worker 并发（D-061）** | v1 **单消费线程串行**（`SmartLifecycle` + Redis BLPOP）；`app.asr.max-concurrency` 是 P2 计划中配置，别自己加线程池 |
| **测试入口坑（D-066，T3.0 修）** | `scripts/test.ps1` 若把 `.env` 的 `ASR_BASE_URL=http://asr:8000`（容器内地址）注入测试进程，宿主机解析不了 → 冒烟必红；应跳过该键、用 `localhost:${ASR_PORT}`。控制台 UTF-8 要 `argLine` 与 `MAVEN_OPTS` 两处都设 |
| **配额日切（D-067，T3.0 修）** | 配额日 = PG `CURRENT_DATE`、`Retry-After` = JVM `LocalDate.now()`，当前都是容器 **UTC** → 中国用户早 8 点才重置；compose 给 app/postgres 设 `TZ=Asia/Shanghai`（两边必须一致） |
| **摘要相关列已就绪** | `task_result` 已有 `summary`/`todos`(jsonb)/`keywords`(jsonb)/`ai_tier`/`template`，**T3.x 不需要新迁移**；`ChatModel` 由 `LlmModelSwitchEnvironmentPostProcessor` 决定（无 key 时 bean 不存在，摘要要跳过而不是 new） |

## 4.2 可维护性（宪法原则 VII，NON-NEGOTIABLE）

细则见 `docs/项目/录音纪要平台/可维护性要求.md`（M-1~M-10）。**每个 commit 前必跑**：

```powershell
cd D:\new; powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-maintainability.ps1
# 期望：结论：通过 ✅ / exit=0；不通过先修（已知技术债登记在 tools\maintainability-allowlist.txt，只减不增）
```

四条**违反即阻塞**：M-1 配置全外置（路径/端口/key/阈值不许写进代码）· M-2 分层单向（web → task/upload/worker → config，
共享契约放 `common/`）· M-6 错误处理统一（`ApiException` + 固定错误码、禁空 catch、外部调用有超时）·
M-8 测试可维护（新公共逻辑必须有测试、禁 `@Disabled`）。其余 M-3/4/5/7/9/10 见细则。

**现存技术债 2 条已登记**（`task/QuotaService`、`upload/AudioDurationProbe` 内层 import `web.*` 的 ApiException/DTO），
计划 T4.x 迁 `common/` 后清零，届时同步删 allowlist（`tools\maintainability-allowlist.txt` 只减不增）。

## 5. 每完成一个任务的回报格式（照这个贴给人类）

> 可复制的提示词模板（需求澄清 / 规划四问 / 扩范围先确认 / 测试三问 / 证据链报错）在
> `docs/协作/开发提示词模板.md`；方法出处见 `docs/协作/11月13日-音频总结.md`。

开工前先给**规划四问**（复杂任务/改 >3 文件时）：
```
改哪里：<文件路径列全>｜复用什么：<已有类/配置>｜影响哪些功能：<接口/DB/配置>｜怎么验证：<命令+期望+exit code>
```

完成后按这个格式回报：
```
任务：T0.1
改了什么：<文件列表/新增容器>（附 git diff --stat）
验证命令：<原样命令>
实际输出：<关键几行输出，不要只说"成功了">
exit code：<0/非0>
测试三问：检查了哪些场景 ｜ 实际运行了哪些测试 ｜ 还有哪些没验证（不许留空）
```

**规则**：没有命令输出 = 没完成。**禁止**用"已完成 / 应该可以了 / 看起来没问题"代替证据。
"还有哪些没验证"必须是非空清单——写"全部验证完毕"等同于没有验证（D-042）。

**范围限制不能只靠提示词**：改完自查 `git diff --stat`，出现任务卡范围外的文件就是违规；一卡一 commit 便于回滚。

---

## 6. 卡住时停下来（不要硬撑或静默降级）

出现以下情况，**停下来写清楚**再等人回复：
1. 需要产品/业务决策（而 `decisions.md` 里没有先例）；
2. 需要 API key、花钱、装软件、要权限；
3. **同一问题连续两轮没有实质进展** → 停止换写法试，改发"证据链三件事"：**错误场景（具体复现步骤）/ 完整报错与实际结果
   （出错位置、核心日志、期望结果）/ 最近改动**，让 AI 先分析原因、说明还缺什么证据；确认后再做最小修改并重测；
4. 要修改公共组件或扩大改动范围（`application.yml` 配置键 / `docker-compose.yml` / Flyway 迁移 / `/api/v1` 响应字段 /
   本 `AGENT.md`）→ 先说明**原因 + 影响 + 替代方案**，等确认；
5. 发现任务卡与 `spec.md` 根本冲突。

停下来时给：**卡点 + 2 个候选方案 + 你建议选哪个**。

---

## 7. 禁止清单

- ❌ 修改 `docs/` 里的需求/规格（那是人的决定）
- ❌ 删测试 / 跳测试（`@Disabled`、`-DskipTests`）/ 降低断言来让构建通过
- ❌ 为了跑通而把 ASR 换成云、把 LLM 换成写死文本（这会破坏"零成本可跑"）
- ❌ 提交 `.env`、密钥、大文件（`.gitignore` 已配，别绕过）
- ❌ 一次改多个任务（一个任务一个 commit，`<type>(<scope>): <做了什么>`）
- ❌ 手改 `docs/` 副本（它是从 `D:\new` 同步来的生成副本）
- ❌ **违反可维护性硬要求**：写死路径/端口/阈值、内层反向 import `web.*`、空 `catch`、`System.out`、
  无测试的新公共逻辑、凭空新增允许清单条目（宪法 VII；`tools\check-maintainability.ps1` 会拦）
- ❌ 未记 `decisions.md` 就新增依赖/改技术栈/改对外契约
- ❌ **删改已发布的对外字段/错误码**（`/api/v1` 只增不改；错误码现固定 7 个，D-041/D-047）
- ❌ **把异常消息塞进 `error_code` 等短列**（列宽 64，超长会让写回失败 → 任务反卡 RUNNING，完整原因写 `dead_letter.reason`）
