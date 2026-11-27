# 交付给 ZCode：怎么让它接手写代码

> 定位：**ZCode = 写代码的角色**（与 WorkBuddy 同一个 GLM 5.3 模型，只是换平台）。
> DSH 仍是 Lead：出卡、独立验收、写文档、发布。**写代码的人不验收自己的代码（K-63）。**
> 你（人）只做三件事：**贴一条消息 / 把回报贴回给我 / 说继续**。

---

## 0. 每轮交接前我（Lead）做的准备

```powershell
cd D:\new
powershell -NoProfile -ExecutionPolicy Bypass -File tools\sync-docs-to-code.ps1
cd D:\new\projects\meeting-notes
git status --short          # 必须空
git log --oneline -3        # 与 交接.md 的基线一致
git worktree list           # 只应有主仓
```

**ZCode 的工作目录 = `D:\new\projects\meeting-notes`**（它只看得到自己打开的目录，所以文档必须已 sync 进去）。
入口是仓库根 `AGENT.md`；状态/交接/决策/踩坑都在 `docs\项目\录音纪要平台\` 下。

---

## 1. 每轮开工的第一条消息（整段复制给 ZCode）

> 由 `D:\new\tools\handoff\card2prompt.ps1 -Task T4.NNN -Target zcode -Save` 生成，
> 已含「七步读现状 + 卡面全文 + 硬约束 + 回报格式」。**不要手写、不要裁剪卡面。**

固定前导（生成器已内置）：

```text
你是本项目的写代码 AI。严格按仓库根 AGENT.md 执行，不要问我需求是什么。
先按顺序读（只读点名的条目，不要通读全部文档）：
1) AGENT.md
2) docs/项目/录音纪要平台/状态.md
3) docs/项目/录音纪要平台/交接.md
4) docs/协作/全局看板.md 里「进行中」那一行
5) 卡面点名的决策 D-xxx 与坑 K-xxx（各读 1 条）
6) 卡面 §0 指定的工作目录约定（worktree 就照做，不许直接在 main 上改）
先回一段现状复述（3–5 句），然后直接开工，不要停下等我确认。
```

---

## 2. 硬约束（违反即返工）

1. **在卡面 §0 指定的 worktree 里改**（惯例 `..\meeting-notes-wt-tNNN` + 分支 `feat/TNNN-xxx`），
   基线 commit 必须是卡面写的那个；**1 张卡 = 1 个 commit，不 push、不建 tag、不动 GitHub 设置**。
2. **只改白名单里的文件**。白名单没写但编译/测试必需的连带（构造器参数、mock matcher、H2 schema、SQL 断言列），
   **必须先在回报里点名再改**；纯注释/格式化不占白名单。
3. **不改验收标准**：不许弱化断言、跳过用例、加 `@Disabled`、把 `assertEquals` 改 `assertNotNull`、删用例。
4. 含中文的 `.ps1` 必须 **UTF-8 with BOM**（PowerShell 5.1 无 BOM 会按 ANSI 读中文直接语法错）；源码文件一律 LF、无 BOM。
5. 环境：Windows PowerShell 5.1（**没有 pwsh**）；`mvn` 不在 PATH，测试一律走 `scripts\test.ps1 -UnitOnly`；
   拉基础镜像用 `docker.m.daocloud.io/` 前缀，minio 用 `MINIO_IMAGE=swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/minio/minio:latest`。
6. **可移植性**：主路径只写 H2 + PG 都认的 SQL。禁 PG 专有写法（`RETURNING`、`interval '1 day'`、`percentile_cont`）；
   时间差一律在 Java 算（`Clock`/`Timestamp` 绑定），不要在 SQL 里算。
7. **行数硬上限**：单个 Java 文件 ≤300 行（门禁 M-3 会拦）；`tasks.html` 已顶格，改前先看卡面预算。
8. **前端**：禁 `window.confirm/alert/prompt`（M-13 拦）、禁 `innerHTML` 拼用户数据，一律 `textContent`。
9. **不跑发布链**：`sync-docs-to-code.ps1`、`deploy-main.ps1`、主栈 compose、`git push`、CI 触发全归 Lead。
10. 卡住就停：写「卡点 + 2 个候选方案 + 建议选哪个」，不要静默降级或绕过验收。

---

## 3. 回报格式（六段，缺一段算没交）

```text
1. 现状复述（3–5 句）：项目是什么、当前基线、本卡解决什么
2. 改了什么：文件:行 + 为什么这么改（含契约对应关系）
3. 怎么证明：每条验收项的命令 + 关键输出 + exit code（不要只说"通过"）
4. 偏差：白名单外连带 / 卡面矛盾的处置 / 主动声明的近似实现
5. 测试三问：检查了哪些场景 ｜ 实际运行了哪些测试 ｜ 还有哪些没验证（不许留空）
6. 行数账与现场：关键文件行数、worktree/分支、commit hash、是否 push、是否清场
```

同一个问题连续两轮没实质进展，按「证据链三件事」重新提问：错误场景 · 完整报错与实际结果 · 最近改动。

---

## 4. 常见的返工原因（都是真发生过的）

| 返工原因 | 正确做法 |
|---|---|
| 自报「门禁三写法 0/0」但实测红（文件 302 行） | 改完 SQL/加完行**必须自己复跑**三种写法门禁，贴输出 |
| 只测主路径，没测「同一份数据在两个接口里口径一致」 | 只要新增/修改统计口径，必须同时核 counts / stats / list 三处同值 |
| 布局改动只看文本存在，不看几何 | 列表追加子元素必须验「直接子元素数不变、动作列同排」 |
| 失败态验证只看结果，不看时间 | 重试/退避类必须观测**间隔**（秒级时间戳），不能只看次数 |
| 环境变量写进 `.env` 就以为生效 | compose 的 app 段不转发自定义变量，要在 override 的 `environment:` 显式列，并 `printenv` 确认进容器 |
| 幂等键吃掉第二次实验 | 需要同内容二次上传时，先删除+彻底删除（purge）再传 |
| `docker stop` 当崩溃 | 崩溃路径必须 `docker kill`（exit 137）；`docker stop` 走优雅停机 |
| worktree 没 `.env`，容器起不来 | 从主仓复制 `.env`（含 `API_TOKEN`、`ASR_MODEL_HOST_DIR`），它已 gitignore |
| 改了 `infra/asr/server.py` 却没重建 asr 镜像 | 改服务端脚本必须重建对应镜像，否则静默不生效 |

---

## 5. 常用信息（卡住时直接看）

| 项 | 值 |
|---|---|
| 代码仓 | `D:\new\projects\meeting-notes`（remote `https://github.com/plus-Liang/meeting-notes.git`） |
| 当前基线 | 见 `docs/项目/录音纪要平台/状态.md`（HEAD / CI 号 / 单测数 / 任务数 / 模型字节） |
| 测试入口 | `scripts\test.ps1 -UnitOnly`（内部用 `E:\tools\apache-maven-3.9.9\bin\mvn.cmd`） |
| 门禁 | `tools\check-maintainability.ps1 -Repo <仓库根>`（三种写法都要过） |
| JDK / Maven | `$env:JAVA_HOME='E:\tools\jdk-21.0.2'`；`E:\tools\apache-maven-3.9.9\bin` |
| 本地模型缓存 | `D:\huggingface\hub\models--Systran--faster-whisper-small`（`ASR_MODEL_HOST_DIR=D:/huggingface`） |
| 测试音频 | `D:\datasets\meeting-notes-samples\`（含 33.88 MB 真实语音，转写需数分钟） |
| 文档权威版 | `D:\new\docs\...`（**ZCode 不改文档**，除非卡面白名单明确包含某一份） |

---

## 6. 分工红线

- **Lead（DSH）**：出卡、写提示词、独立验收（自建夹具、不复用你的脚本）、写报告/决策/坑、双仓发布、部署、CI。
- **ZCode**：只写代码 + 自测 + 六段回报；**不 push、不部署、不改验收标准、不改文档（除白名单）**。
- 一张卡一轮，**同一文件不允许两个写者**（需要并行时必须文件不重叠，且由 Lead 分配）。
