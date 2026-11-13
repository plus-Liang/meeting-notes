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

## 3. 当前任务：阶段 0 底座验证（8h，**不通过不许写业务代码**）

完整任务卡见 `docs/项目/录音纪要平台/tasks-starter.md`。摘要：

| ID | 做什么 | 通过判据（必须有输出） |
|---|---|---|
| T0.1 | 起 ASR 服务（自建 OpenAI 兼容容器，见下） | `curl http://localhost:8000/v1/models` 返回 200 |
| T0.2 | curl 转写真实 mp3 | 返回 JSON，`text` 非空且中文带标点 |
| T0.3 | 记录 RTF 与资源占用 | 写进 `docs/项目/录音纪要平台/state.md` |
| T0.4 | Spring AI 最小转写跑通 | `mvn -q -Dtest=AsrSmokeTest test` → BUILD SUCCESS |
| T0.5 | 云 LLM 通道（智谱优先） | 打印出摘要 |

**T0 失败预案**：①自建容器起不来 → 换 whisper.cpp server ②仍不行 → 云 ASR ③全不行 → 先做 PASTE 入口跑通后续链路。

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

## 5. 每完成一个任务的回报格式（照这个贴给人类）

```
任务：T0.1
改了什么：<文件列表/新增容器>
验证命令：<原样命令>
实际输出：<关键几行输出，不要只说"成功了">
exit code：<0/非0>
剩余风险：<一句>
```

**规则**：没有命令输出 = 没完成。**禁止**用"已完成 / 应该可以了 / 看起来没问题"代替证据。

---

## 6. 卡住时停下来（不要硬撑或静默降级）

出现以下情况，**停下来写清楚**再等人回复：
1. 需要产品/业务决策（而 `decisions.md` 里没有先例）；
2. 需要 API key、花钱、装软件、要权限；
3. 同一问题连续 2 次修复失败；
4. 发现任务卡与 `spec.md` 根本冲突。

停下来时给：**卡点 + 2 个候选方案 + 你建议选哪个**。

---

## 7. 禁止清单

- ❌ 修改 `docs/` 里的需求/规格（那是人的决定）
- ❌ 删测试 / 跳测试（`@Disabled`、`-DskipTests`）/ 降低断言来让构建通过
- ❌ 为了跑通而把 ASR 换成云、把 LLM 换成写死文本（这会破坏"零成本可跑"）
- ❌ 提交 `.env`、密钥、大文件（`.gitignore` 已配，别绕过）
- ❌ 一次改多个任务（一个任务一个 commit，`<type>(<scope>): <做了什么>`）
- ❌ 手改 `docs/` 副本（它是从 `D:\new` 同步来的生成副本）
