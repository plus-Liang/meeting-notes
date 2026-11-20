# 交付给 ZCode：怎么让它接手写代码

> 定位：**ZCode = 写代码的角色**（接替 WorkBuddy）。DSH 继续当 Lead：出卡、独立验收、写文档、发布。
> 你（人）只做三件事：**贴一条消息 / 把回报贴回给我 / 说继续**。
> 关键约束：**写代码的人不验收自己的代码**（K-63）——ZCode 写完回报，由 DSH 独立验。

---

## 0. 每次交接前准备（1 分钟）

```powershell
cd D:\new
powershell -NoProfile -ExecutionPolicy Bypass -File tools\sync-docs-to-code.ps1   # 文档同步进代码仓
cd D:\new\projects\meeting-notes
git status --short        # 必须是空
git log --oneline -3      # 确认 HEAD 与 交接.md 一致
```

**ZCode 的工作目录 = `D:\new\projects\meeting-notes`**（它只看得到自己打开的目录，所以文档必须已经 sync 进去）。
入口是仓库根 `AGENT.md`；项目规则、状态、交接、决策、踩坑都在 `docs\` 下。

---

## 1. 第一条消息（整段复制给 ZCode）

```text
你是本项目的写代码 AI。严格按仓库根 AGENT.md 执行，不要问我需求是什么。

先做三件小事再动手：
1) 读 AGENT.md
2) 读 docs/项目/录音纪要平台/交接.md（当前位置 + 下一步 + 15 条高频坑）
3) 读 docs/协作/交付给ZCode.md 的 §2「本轮任务卡：T4.14b」，那就是你这轮唯一要做的事

硬约束（违反即返工）：
- 在本地 main 上开发，1 个任务 1 个 commit，**不要 push、不要动 tag / Release / GitHub 设置**；
- 只改卡面允许的文件，其它文件（尤其 docs/、compose、CI、README）一律不碰；
- 含中文的 .ps1 必须存成 UTF-8 with BOM（PowerShell 5.1，无 BOM 会按 ANSI 读中文直接语法报错）；
- 本机环境：Windows PowerShell 5.1（没有 pwsh）；mvn 不在 PATH，测试走 scripts\test.ps1；
  Docker Hub 不稳，拉基础镜像用 docker.m.daocloud.io/ 前缀，minio 用 MINIO_IMAGE 华为云镜像；
- 不许为了让测试变绿而弱化断言、跳过用例或改验收标准。

做完按这个格式回报，不要只说"完成了"：
任务 / 改了什么（文件:行）/ 为什么这么改 / 验证命令 / 实际输出（关键几行）/ exit code /
测试三问：检查了哪些场景 ｜ 实际运行了哪些测试 ｜ 还有哪些没验证（不许留空）

如果卡住，停下来写：卡点 + 2 个候选方案 + 建议选哪个，不要静默降级。
同一个问题连续两轮没实质进展，按「证据链三件事」重新提问：错误场景 · 完整报错与实际结果 · 最近改动。
```

---

## 2. 本轮任务卡：T4.14b（模型下载「假完成」修复）

> 来源：T4.14 陌生人净室验收（`docs/协作/review/2026-11-20-T4.14陌生人跑通验收.md` §3 F1）
> 优先级 P1：影响陌生人首次启动体验

**现象（已实证）**
1. 全新克隆里跑 `scripts/download-model.ps1`（`HF_ENDPOINT=https://hf-mirror.com`），下载到约 302 MB 时网络 SSL 中断，exit 1。
2. 立刻重跑 → 输出 `[download-model] 模型 small 已存在（...\snapshots），跳过下载。`，exit 0。
3. 但 `models/hub/models--Systran--faster-whisper-small/snapshots/<rev>/` 下是 0 字节占位文件，`model.bin` 缺失。
4. 直接起栈 → asr 重启循环，日志才报 `[startup] 原始错误：The cached snapshot ... is incomplete: 1 file(s) are missing (model.bin)`。

**根因**：`scripts/download-model.ps1:21-27` 与 `scripts/first-run.ps1:22` 都把「`snapshots` 目录存在且非空」当成「模型已完整下载」。

**要求改动（只动这 2 个脚本）**
1. 完整性判断改为校验 `snapshots/<rev>/` 下 `model.bin`、`config.json`、`tokenizer.json`、`vocabulary.txt` 都存在且 `Length > 0`；`<rev>` 用 `Get-ChildItem $snapDir -Directory | Select-Object -First 1` 取，别硬编码 commit。
2. `download-model.ps1` 新增 `-Force`：先删 `models\hub\models--Systran--faster-whisper-<Model>` 再下载。
3. `first-run.ps1` 复用同一判断（两文件各自内联函数即可，不新建共享模块，保持脚本独立可跑）；判定不完整时打印
   `[first-run] 检测到 faster-whisper-<Model> 下载不完整（缺 model.bin），重新下载…` 并调 `download-model.ps1 -Force`。
4. 下载失败分支保留中文提示，可再加一行「若反复中断，可先删 ./models 再重跑」。
5. **不要动** `infra/asr/server.py`（它已能正确报 incomplete），也不要动 README / docs / compose / CI。

**自测（贴命令 + 输出 + exit code）**
- `git diff --stat HEAD~1..HEAD` 只含这 2 个脚本；
- 造半包：手工建 `models\hub\models--Systran--faster-whisper-small\snapshots\FAKE\model.bin`（0 字节）→ 跑 `download-model.ps1` 必须出现「下载不完整/开始下载」，**绝不能**出现「已存在，跳过」；
- 完整包：从 `D:\huggingface` 复制一份到 `./models`（或复用主栈模型目录）→ 跑脚本打印「已存在，跳过」，exit 0；
- `first-run.ps1` 在半包状态下必须触发重新下载（看到「开始下载」日志即可 Ctrl+C，别真等 461 MB）；
- `git status --short` 为空。

---

## 3. 回报后我（Lead）怎么验

ZCode 把回报贴回来后，我会独立复跑，不复用它的脚本：

1. `git diff HEAD~1..HEAD` 是否恰好 2 个脚本、有无越界文件；
2. 我自建半包/完整包两份夹具，分别验「必触发下载」「必跳过 exit 0」；
3. `-Force` 是否真的清空重下；
4. 两脚本在 PowerShell 5.1 下无语法错误、BOM 首字节 `239,187,191`；
5. 全量回归：`scripts\test.ps1 -UnitOnly` **493/0/0**、可维护性 0/0；
6. 落盘：第 79 份验收报告 + D-178 + （如有新坑）K-101，更新 状态/交接/任务清单/看板 → 同步 → commit → push → CI 绿。

**通过后**再进下一张卡（顺序见 `交接.md` §1）：补测 461MB 模型干净下载 → 3 位真实用户验证 → S7 沉淀。

---

## 4. 给 ZCode 的常用信息（卡住时直接给它）

| 项 | 值 |
|---|---|
| 代码仓 | `D:\new\projects\meeting-notes`（remote `https://github.com/plus-Liang/meeting-notes.git`） |
| 当前状态 | 见 `docs/项目/录音纪要平台/状态.md`（HEAD、CI、基线数字都在里面） |
| 测试入口 | `scripts\test.ps1 -UnitOnly`（内部用 `E:\tools\apache-maven-3.9.9\bin\mvn.cmd`）；全量另有 `ASR_SMOKE_AUDIO` 要求 |
| JDK / Maven | `$env:JAVA_HOME='E:\tools\jdk-21.0.2'`；`E:\tools\apache-maven-3.9.9\bin` |
| 本地模型缓存 | `D:\huggingface\hub\models--Systran--faster-whisper-{small,medium}`（可用 `ASR_MODEL_HOST_DIR` 指过去） |
| 镜像加速 | 基础镜像前缀 `docker.m.daocloud.io/`；minio 用 `MINIO_IMAGE=swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/minio/minio:latest` |
| 文档权威版 | `D:\new\docs\...`（改文档找 Lead，ZCode 不改文档） |
