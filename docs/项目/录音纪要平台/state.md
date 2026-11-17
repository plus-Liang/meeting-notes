# 状态：录音纪要平台

**更新**: 2026-11-17 22:40 ｜ **阶段**: **D-122 批次（按 D-126/D-127 裁剪）**：保留 T4.35a–c/T4.36/T4.37；不做 T4.31 PWA / T4.33 INBOX / T4.34 RSS；**已通过 15 张：T4.23 / T4.23b / T4.24 / T4.24b / T4.25 / T4.26 / T4.26b / T4.27 / T4.27b / T4.28 / T4.29 / T4.29b / T4.30（含收尾 T4.30b）/ T4.32a / T4.32b ✅**（其中 T4.29/T4.30 均为首验不通过→收尾卡修复后转通过）；下一张 = **T4.35a ASR segments（句子级时间戳）** → T4.35b 播放器 API → T4.35c 前端播放器 → T4.36 说话人 → T4.37 登录
**目标**: 开源自托管「录音/网课/短视频 → 纪要 + 待办」Web 应用（给别人用、我是第一用户）
**仓库**: `plus-Liang/meeting-notes` ｜ 产品提交 **`4153891`**（T4.32b）；HEAD 见 `git log -1`（当前为验收文档提交，**origin 与本地一致、无待推**）｜ `git status` 干净

## 实测基线

- 栈 **5/5 healthy**；我独立跑：`-UnitOnly` **404/404** exit 0、全量 **420/420** exit 0、可维护性 **0 阻塞 / 0 提示** exit 0。
- **今日配额**（11-17）：已用 **89 分钟 / 76 任务**（上限 200/100，**剩 24 任务** → 后续卡尽量 0 配额）。任务总数 **241**（全 DONE、DEAD=0、LINK 11）；MinIO **247 前缀 = 241 任务 + 历史 6 孤儿（74–79）**，无新增孤儿。
- **验收报告 58 份**（最新 `review/2026-11-17-T4.32b验收.md`）；**决策 143 条**（最新 **D-143 T4.32b 通过**）；**坑 K-01~K-85**（本批 K-80 点→回读、K-81 断言不许依赖全库、K-82 外部工具输出不许裸 parseDouble、K-83 重构漏程序名、K-84 数字字面量 IP 自规范化、**K-85 容器里 host.docker.internal 可能是 IPv6 → 宿主测试服务须双栈**）。
- **执行队列**：**T4.35a ASR segments（V7）** → T4.35b 播放器 API → T4.35c 前端播放器 → T4.36 说话人 → T4.37 登录。每张独立 commit + 验收报告 + 我独立复核；改契约先 commit→build/up→全量（K-68/K-77）。

## 进度

- 阶段 0/1 ✅、W1–W4 ✅；真机反馈派生 T4.20/T4.21/T4.21b ✅；T4.22 ✅；D-122 批次 **15 张通过**；发布三件（T4.10/T4.9/T4.11）在批次之后。
- **T4.32b（第 58 份，D-143）✅**：`4153891`（17 文件 +1353/−21，单 commit）——`POST /link/preview`（复用 32a `UrlGuard`、逐跳重校、只读 2MB、og 纯函数解析、相对封面补全、`kind=direct/page/unsupported`）、BYOF `ProcessMediaFetcher`（argv 不过 shell、`{url}` 独立 token、`{out}` 可选、stdout 受 max-bytes、**看门狗从进程启动起算**、stderr 尾部、超时/非 0 → 502）、`/tasks/link` 可选 `use_byof`（未配 → 400 `FETCHER_UNAVAILABLE`，32a 语义零改动）、前端「从链接导入」四分支、配置 3 键、码表只增到 10、README 责任边界。**我独立**：真机 Phase A 10/10、B1 13/13（BYOF 201→DONE task 522 transcript 498、超时看门狗、非 0 stderr、不绕过 UrlGuard、幂等回 129）、B2 stdout task 525 DONE；Playwright 7/7（526 直链、527 og 卡→BYOF、内网错误、零溢出）+ 未配 BYOF 5/5（灰按钮+3 步指引）；恢复 env diff 仅 HOSTNAME。
- **T4.32a（第 57 份，D-142）✅**：`641a6d1`——`UrlGuard`（逐 IP + 数字字面量自规范化）、`MediaFetcher` + `HttpMediaFetcher`（每跳校验/超时/超大小/匿名 GET）、`POST /tasks/link`（`source_type=LINK`）；反例 23/23 全 400 无 500、正例 task 500 transcript 498。
- 节奏复核（D-130）：距 12-16 约 30 天 → **交付窗口可行、不再裁范围、不调日期**；真正风险 = relay 间隔 + 大卡（35b/36/37）返工。**配额已用 76/100 任务**，剩余卡以 0 配额优先。

## 待办（按顺序）

0. ✅ **T4.32b 已通过**（`4153891`）：预览确认卡 + BYOF 外部抓取；残余见"阻塞/风险"P3。
1. **下一张 = T4.35a ASR segments**：`infra/asr/server.py` 返回 `segments[{start,end,text}]` 且旧三键逐字兼容；Java 侧一次调用同时取 text+segments（若 Spring AI 拿不到就换仓库内轻量 HTTP 客户端，**不许二次调 ASR**）；迁移 **V7** `task_result.segments jsonb`；详情**只增** `segments`（旧任务 null）。
2. 然后 T4.35b 播放器 API（预签名/流式音频 + 详情 segments，越权防护）→ T4.35c 前端播放器（点句 seek、当前句高亮、无 segments 降级、390 可操作）→ T4.36 说话人 → T4.37 登录。
3. **发布材料（T4.10 README+录屏 → T4.9 → T4.11 简历）在批次之后**；发布前清 MinIO 历史孤儿对象 74–79。
4. **纪律**：新样本/日志/override/缓存一律 D 盘；重建容器前先落旧日志（K-74）；**先 commit → 再 build/up → 再全量**（K-77）；**Lead 不写产品代码**（K-63）；前端写操作"点→回读"（K-80）；断言不许依赖全库规模（K-81）；**改文档只改 `D:\new\docs\...` 权威版，不要改代码仓生成副本**（T4.32b P3-1）。

## 阻塞 / 风险

- **无阻塞**。今日配额剩 **111 分钟 / 24 任务**；push：**origin 与本地一致（无待推）**。
- **T4.32b P3 残余（接受）**：`{out}` 模式不读子进程 stdout（工具狂写可能塞管道到超时）；预览 2MB 截断会漏极靠后 og 标签；无 `Content-Type` 直链判 unsupported（UI 无入口）；`FETCHER_CMD` 路径含空格不可用；BYOF 工具侧自行访问地址的 SSRF/合规责任在工具；BYOF 413 与"exit 0 无产物"未真机验（单测覆盖）；预览抓取逻辑与 `HttpMediaFetcher` 有重复实现（v1 接受）。
- **T4.32a P3 残余（接受）**：`100.64/10` 等特殊段与 IPv6 NAT64/6to4 未拦；DNS rebinding TOCTOU；链接导入不校验 `TASK_MAX_SIZE_BYTES`（只受 `FETCH_MAX_BYTES`）；`FETCH_ALLOW_HOSTS` 靠部署纪律。
- 既有残余：标签下拉来源=已加载行并集（P3-3）；浏览器交互层无自动化回归（P3-4）；录音 60min/200MB 自动停与切后台未超长实测；`runFfprobe` 先读流再 waitFor 的既有阻塞风险；F-14 本地档 v1 不可用（D-089）；Webhook v1 无 SSRF 防护；批量 413/429 未实测；**v1 余量 0（D-032 仍成立）**。
- **运维**：Docker Desktop `AutoStart=false`，睡眠/重启后引擎可能不在（K-38）；改 compose 网络属性必须 `down` 再 `up`（K-44）；所有 curl 带 Bearer；重建容器前先存日志（D-087/K-74）。
