# 交付给 WorkBuddy：怎么让它直接接手写代码

> 目标：你（人）只当产品经理，做三件事——**贴一条消息 / 看一个结果 / 说继续**。
> 代码由 WorkBuddy 写，规则和验收标准由本项目文档提供。

---

## 0. 先做一次准备（1 分钟，只需一次）

```powershell
cd D:\new
powershell -NoProfile -ExecutionPolicy Bypass -File tools\sync-docs-to-code.ps1
```

作用：把 **项目文档 24 份 + 验收报告 5 份**同步进代码仓 `D:\new\projects\meeting-notes\docs\`（含 `AGENT.md` 之外的 `tools/`），
这样 WorkBuddy 打开代码仓就能读到需求（它通常只能看到自己的工作目录）。

**然后**：把 **`D:\new\projects\meeting-notes`** 设为 WorkBuddy 的工作目录（打开这个文件夹）。

---

## 1. 第一条消息（复制粘贴，别的都不用说）

```text
你是本项目的写代码 AI。请严格按 AGENT.md 执行。

第一步只做这件事（不要多做）：
1) 读 AGENT.md（仓库根目录）
2) 读 docs/项目/录音纪要平台/00-先看这个.md
3) 读 docs/项目/录音纪要平台/tasks-starter.md 里的【T0.1】一张卡
4) 按 T0.1 的步骤新建 infra/asr/ 下的文件，把 ASR 服务做成自建 OpenAI 兼容容器

约束（违反即返工）：
- 配置全部走环境变量，不许硬编码
- 不许改成云 ASR（必须本地、零成本、可离线）
- 不许改 docs/ 里的需求
- 本机 Docker Hub 不通，任何镜像都要加前缀 docker.m.daocloud.io/
- JDK 21：$env:JAVA_HOME='E:\tools\jdk-21.0.2'；Maven：E:\tools\apache-maven-3.9.9\bin
- 本地已有模型不要下载：D:\huggingface\hub\models--Systran--faster-whisper-{small,medium,large-v3}

做完按这个格式回报，不要只说"完成了"：

任务 / 改了什么 / **规划四问（改哪里·复用啥·影响啥·怎么验证）** / 验证命令 / 实际输出（关键几行）/ exit code /
**测试三问：检查了哪些场景 ｜ 实际运行了哪些测试 ｜ 还有哪些没验证（不许留空）**

如果卡住，停下来写：卡点 + 2 个候选方案 + 建议选哪个。不要静默降级。
如果同一个问题你连续两轮没有实质进展，别继续换写法试——按"证据链三件事"重新提问：
**错误场景（具体复现步骤）· 完整报错与实际结果 · 最近改动**（模板见 `D:\new\docs\协作\开发提示词模板.md` 模板 7）。
```

---

## 2. 之后每一轮怎么推进（模板）

每完成一张卡，**先把证据贴回来**（你自己看一遍），确认没问题再发下一条：

```text
T0.1 通过了。现在只做 T0.2（用 curl 转写真实 mp3），按 tasks-starter.md 的 T0.2 执行，做完给证据。
```

再下一轮：

```text
T0.2 通过。现在只做 T0.3（实测 RTF），跑 3 个不同长度文件，给出 RTF 和 8.44h 的推算耗时。
```

**核心节奏：一次一张卡。** 任务卡全在 `docs/项目/录音纪要平台/tasks-starter.md`（已写 **16 张**：T0.1–T0.5、T1.1–T1.9、T2.1、T2.10），
其余在 `tasks.md`（总共 **53 个任务**，带 DoD 与验证命令）。

### 2.1 当前这一轮（T1.9）直接粘这个

```text
T1.7/T1.8 已验收通过（报告 docs/协作/review/2026-11-14-T1.7-T1.8验收.md，mvn 23/23）。现在只做 T1.9（scripts/test.ps1 测试入口），
按 docs/项目/录音纪要平台/tasks-starter.md 的【T1.9】段执行，先写规划四问+范围声明再动手。注意六条：
1) 只加脚本，不动任何 Java / 配置 / compose / Dockerfile；
2) 脚本读 .env 注入进程环境（不打印 value，只打印 key 名）；ASR_BASE_URL 默认 http://localhost:8001，但外部已设置时以外部为准；
3) maven 定位顺序：$env:MAVEN_HOME/$env:MAVEN_BIN → E:\tools\apache-maven-3.9.9\bin\mvn.cmd → 都没有就明确报错 exit 2；
4) 支持 -UnitOnly 跳过 2 个 *SmokeTest；打印 surefire 用例数；exit code 透传；
5) 中文注释的 .ps1 必须存 UTF-8 带 BOM（PS 5.1 否则乱码报错）；
6) 反例必须进：把 ASR_BASE_URL 指到 http://localhost:59999 → 冒烟用例必须红（证明脚本真注入了地址），跑完清掉。
做完贴：改了什么（git diff --stat）/ 两种模式的实际输出 + 用例数 + exit code / 反例输出 / check-maintainability.ps1 exit code / 测试三问（不许留空）。
```

---

## 3. 你只需要做三种"验收动作"（不需要懂代码）

| 场景 | 你怎么验 |
|---|---|
| 起了个服务 | 让它给你 `curl` 的输出，你看有没有 JSON / 状态码 200 |
| 修了个 bug | 让它给你**复现命令 + 修好后的输出**；只给"已修复"就退回 |
| 完成一张卡 | 看它给的 `exit code` 是不是 0，再看"实际输出"那一栏有没有内容 |

**判定口诀：没有命令输出 = 没完成。** 这句话已经写进 `AGENT.md` §5，你可以直接引用。

---

## 4. 三个坑（第一次用一定会踩）

### 坑 1：别一次把 53 个任务都发给它
上下文会被灌爆，它会开始"合理想象"。**永远一次一张卡。**

### 坑 2：别让它改 `docs/`
那是需求与取舍的唯一权威（`docs/` 里是同步副本，真版在 `D:\new\docs\项目\录音纪要平台\`）。
它想改需求时，让它**停下来问你**。

### 坑 3：它会偷偷"降级"来让测试通过
典型表现：把本地 ASR 换成云、把 LLM 调用换成返回固定文本、把断言改松。
`AGENT.md` §7 已列禁止清单，但**你要看它每次改了什么文件**（让它列文件清单）。

---

## 5. 如果我（DSH 这边）要复核它的产出

把 WorkBuddy 的回报贴回来给我，我按 `spec.md` 的 N1–N20 与 `constitution.md` 六条原则逐条核对，
输出"符合 / 违反 + 证据"的 review 报告。这样你相当于有两个 AI：一个写、一个查。

---

## 6. 如果 WorkBuddy 支持"项目规则文件"

| 它的行为 | 你要做的 |
|---|---|
| 自动读 `AGENT.md` / `AGENTS.md` / `CLAUDE.md` / `.cursorrules` | 什么都不用做，仓库根已有 `AGENT.md` |
| 只读它自己的"项目说明"设置项 | 把 `AGENT.md` 全文粘进去（约 90 行） |
| 什么都不读 | 每条消息都先说"先读 AGENT.md，读完复述你要做的任务再动手" |

---

## 7. 交付清单（这次已经给你准备好的东西）

| 位置 | 内容 |
|---|---|
| `D:\new\projects\meeting-notes\AGENT.md` | **写代码 AI 的入口**：必读顺序 + 十条硬约束 + 环境 + 回报格式 + 禁止清单 |
| `D:\new\projects\meeting-notes\docs\` | 项目文档 24 份 + 5 份验收报告副本（同步脚本生成，勿手改） |
| `docs\项目\录音纪要平台\tasks-starter.md` | **任务卡 T0.1–T0.5 + T1.1–T1.9 + T2.1 + T2.10（16 张）**，每张含步骤/验证命令/通过标准/失败预案；最新一张是 **T1.9 测试入口** |
| `docs\项目\录音纪要平台\tasks.md` | 全部 50 个任务 + DoD + 依赖顺序 |
| `docs\项目\录音纪要平台\环境搭建说明.md` | 本机环境实测 + Docker/Python 源怎么配 |
| `tools\sync-docs-to-code.ps1` | 文档改了之后重新同步（一条命令） |
| `docs\协作\开发提示词模板.md`（权威在 `D:\new\docs\协作\`） | **7 个可复制提示词**：需求澄清 / 选型架构 / 三份文档 / 规划四问 / 扩范围先确认 / 测试三问 / 证据链报错 |

---

## 8. 一句话总结

> **把 `D:\new\projects\meeting-notes` 作为工作目录打开 → 粘第 1 节那条消息 → 每轮只推进一张卡 → 只看"验证命令 + 实际输出 + exit code"。**
> 需求不用你解释（它读 `AGENT.md` + `docs/`）；判断不用你懂代码（有 DoD 与验证命令）。

---

## 9. 存储纪律（2026-11-15 起，必须遵守）

- 本机 C 盘曾只剩 9.9GB：已把 Docker 数据（`D:\DockerData\wsl`，junction 保持原路径）、模型缓存（`D:\cache\dsh-asr`）、Maven 仓库（`D:\cache\m2`）迁到 D 盘，C 盘现 41GB 可用。
- WorkBuddy 开发时：**样本 / 日志 / override / 临时 / 构建产物一律放 D 盘**（项目资料用 `D:\new\artifacts\`，缓存用 `D:\cache\`）；**不许**在 `C:\Users\ljh` 下新建大文件、下载大模型/数据集。
- 临时实验配置（override yml 等）放 `D:\new\artifacts\`，**不进仓库**；验完恢复基础 compose。
- 若必须用 C 盘，先在回报里说明原因。

---

## 10. 两条运维约定（2026-11-15 起）

1. **Docker 引擎掉线 → 直接喊 Lead，别动宿主安全设置**（K-38）：你的沙箱把 `wsl.exe` 列入 Program Blacklist，所以你自己起不来 Docker Desktop；DSH 侧一条命令就能拉起（同机不同权限）。报"`dockerDesktopLinuxEngine` 管道不存在 / 8000/8001 无监听"即可，卡点 5 分钟内解除。
2. **每组实验一结束就存日志**（K-39）：`docker compose logs app > D:\new\artifacts\logs-<实验名>.txt`，再切配置/重建容器。T3.8 的 F-14 与超时实验就是重建时丢了 WARN，最后只能靠 DB + Lead 重放补证——不要重复这个代价。
3. **当前进度**：W3 已收口（T3.8 验收通过，`3b80f02`；D-089）。**下一张卡（T4.x）还没写**——Lead 写完会按老流程把"粘贴块"发你；在此之前不要自己在仓库里挑活干。
