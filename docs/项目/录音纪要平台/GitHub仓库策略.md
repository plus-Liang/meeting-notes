# GitHub 仓库策略（要不要建、什么时候公开）

> 结论：**要建，现在就建，但先建 Private，等"零成本可跑通 + 有 demo"再转 Public。**

---

## 1. 为什么必须建（4 条，按重要性）

| # | 理由 | 说明 |
|---|---|---|
| 1 | **"给别人用"没有 URL 就不成立** | D-003R 已把目标定为"给别人用的开源项目"。本地仓库对别人和面试官等于不存在 |
| 2 | **异地备份** | 目前代码只有一份在 `D:\new\projects\meeting-notes`（+ 一个 pack zip）。硬盘挂了/换机就没了，GitHub 是最便宜的异地备份 |
| 3 | **简历/面试的硬通货** | 面试官会点链接看 README、commit 节奏、issue、Release。本地 commit 历史没人看得到 |
| 4 | **发布需要它** | D-026 的"最小发布集"（`v0.1.0` tag、Release、issue 模板）只能建在托管平台上 |

**反例（不建的代价）**：项目做完只能录屏给面试官看 → 说服力弱一半，而且没有异地备份。

---

## 2. 建仓步骤

### 2.1 网页上建（30 秒）

- 仓库名：`meeting-notes`（**ASCII，不要中文**）
- 描述：`Self-hosted meeting/lecture/short-video transcription → structured minutes & TODOs. Local ASR, zero-cost by default.`
- 可见性：**Private**
- **不要**勾"Add README / .gitignore / LICENSE"（本地已经有了，勾了会冲突）

### 2.2 本地关联并推上去

```powershell
cd D:\new\projects\meeting-notes
git remote add origin https://github.com/<你的用户名>/meeting-notes.git
git branch -M main
git push -u origin main
```

### 2.3 国内网络注意（先测，别直接推）

```powershell
# 能不能连上 GitHub（超时/失败很常见）
git ls-remote https://github.com/<你的用户名>/meeting-notes.git
```

- 通 → 直接推；
- 不通 → 用代理（`git config --local http.proxy http://127.0.0.1:<端口>`）或改用 **Gitee 镜像**做备份（见 §6）。

---

## 3. ⚠️ 建仓前必须先处理：commit 作者邮箱

**实测现状（2026-11-13）**：

- 已有 7 个 commit 的作者是 **`ljh <ljh@local>`**（我提交时用 `-c user.email='ljh@local'` 指定的假邮箱）→ **没有隐私问题**；
- 但你的**全局 git 配置**是 `15360364827@163.com`（手机号邮箱）→ **以后你自己直接 `git commit`，用的就是这个手机号邮箱**，一旦公开仓库，它会出现在之后每一个 commit 上，并且永久可见、被搜索引擎索引。

**所以只需要做一件事：在代码仓里设 local 配置（不改全局，也不动已有历史）**

```powershell
cd D:\new\projects\meeting-notes
git config --local user.name  "你的GitHub用户名"
git config --local user.email "12345678+你的GitHub用户名@users.noreply.github.com"
git log --format='%h  %an <%ae>' -1     # 确认生效（下一次提交才会用新邮箱）
```

`noreply` 地址在 GitHub → Settings → Emails 里能找到（形如 `12345678+用户名@users.noreply.github.com`）。

**已有历史要不要改写？** 不需要——它们都是 `ljh@local` 假邮箱，没有泄露。
（如果将来发现某次提交用了真邮箱：推之前用 `git filter-branch` 改写，推了之后就很难清干净。）

---

## 4. Private → Public 的判据（Definition of Ready）

**6 条全满足再转公开**：

- [ ] `docker compose up` 一键起，且**不填任何 key** 能跑通上传→转写→纪要（N15）
- [ ] README 首屏有：一句话定位 + 1 张截图或 GIF + 三步启动 + **「你不该用本项目的场景」**
- [ ] `LICENSE`（MIT）、`CONTRIBUTING.md`、issue/PR 模板都在
- [ ] 没有密钥泄露：`git log -p | Select-String 'sk-|password|secret'` 为空
- [ ] commit 历史干净（作者邮箱已换成 noreply，提交信息能看懂）
- [ ] `v0.1.0` tag 已打，CHANGELOG 已写

> 达不到就先 Private 继续推——**Private 也是备份，不损失任何东西**。

---

## 5. 日常节奏（别搞复杂）

| 时机 | 动作 |
|---|---|
| 每完成一张任务卡 | `git commit`（一个任务一个 commit） |
| 每天收工 | `git push`（拿到异地备份） |
| 每周 | `git log --oneline` 自己扫一眼，确认提交信息能看懂 |
| v0.1 完成 | 打 tag `v0.1.0` → GitHub Release（贴 GIF + 快速开始）→ 转 Public |
| 以后每个版本 | `CHANGELOG.md` 加一节 + tag + Release |

**禁止**：`push --force` 到 main、提交 `.env`/密钥、把大媒体文件提交进去（`.gitignore` 已配）。

### 5.1 日常提交：一条命令（`tools/save.ps1`）

已放好一个安全版提交脚本（默认只暂存**已跟踪**文件的改动，避免盲 `git add -A`）：

```powershell
cd D:\new\projects\meeting-notes

# 改的是已有文件（最常见）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\save.ps1 -Message "feat(asr): 加 OpenAI 兼容转写服务"

# 有新文件（会先列出新文件，要你确认 y）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\save.ps1 -Message "feat(api): 新增任务查询接口" -All

# 只想提交到本地、先不推
powershell -NoProfile -ExecutionPolicy Bypass -File tools\save.ps1 -Message "wip" -NoPush
```

它做的事：显示 `git status` → 暂存 → 显示暂存清单 → commit → push（没有 remote 时只提示、不报错）。

---

## 6. 备选：Gitee 镜像（国内可访问 + 快）

如果 GitHub 推送很不稳定：

```powershell
# 建一个 Gitee 私有仓，再加一个远端（两个远端可同时推）
git remote add gitee https://gitee.com/<你的用户名>/meeting-notes.git
git push -u gitee main
```

注意：**只在一个平台用 Release 和 issue**（推荐 GitHub），Gitee 只做备份/镜像，否则两边都会变成半死不活。

---

## 7. 与本项目决策的关系

- **D-003R**：目标是"给别人用" → 必须有公开地址 → 建仓是前置条件；
- **D-026**：最小发布集 = 一键脚本 + 示例数据 + 截图/GIF + LICENSE/CONTRIBUTING/issue 模板 + CHANGELOG/tag
  → **前 3 项已准备好（社区文件都已提交）**，剩"示例数据 + 截图/GIF"要在 v0.1 完成时补；
- **D-031**：本机 Docker Hub 不通 → **GitHub Actions 里不受影响**（CI 跑在 GitHub 的机器上），但本地拉镜像仍要加前缀。
