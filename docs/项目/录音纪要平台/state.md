# 状态：录音纪要平台

**更新**: 2026-11-17 23:30 ｜ **阶段**: **D-122 批次（按 D-126/D-127 裁剪）**：保留 T4.35b/T4.35c/T4.36/T4.37；不做 T4.31 PWA / T4.33 INBOX / T4.34 RSS；**已通过 16 张：T4.23 / T4.23b / T4.24 / T4.24b / T4.25 / T4.26 / T4.26b / T4.27 / T4.27b / T4.28 / T4.29 / T4.29b / T4.30（含收尾 T4.30b）/ T4.32a / T4.32b / T4.35a ✅**（其中 T4.29/T4.30 均为首验不通过→收尾卡修复后转通过）；下一张 = **T4.35b 播放器 API（短时签名音频流 + Range/206）** → T4.35c 前端播放器 → T4.36 说话人 → T4.37 登录
**目标**: 开源自托管「录音/网课/短视频 → 纪要 + 待办」Web 应用（给别人用、我是第一用户）
**仓库**: `plus-Liang/meeting-notes` ｜ 产品提交 **`7cad12a`**（T4.35a）；HEAD 见 `git log -1`（当前为验收文档提交，**origin 与本地一致、无待推**）｜ `git status` 干净

## 实测基线

- 栈 **5/5 healthy**（app 与 asr 均为 T4.35a 构建）；我独立跑：`-UnitOnly` **417/417** exit 0、全量 **435/435** exit 0、可维护性 **0 阻塞 / 0 提示** exit 0。
- **今日配额（11-17）：已用 104 分钟 / 90 任务**（上限 200/100，**只剩 10 任务**）。**每轮全量 E2E 约 +4–6 任务**；后续卡尽量 0 配额，若确需可开发期临时调高日任务上限并记决策（不要删证据行，D-073）。任务总数 **255**（全 DONE、DEAD=0）；MinIO **261 前缀 = 255 + 历史 6 孤儿（74–79）**，无新增孤儿。
- **验收报告 59 份**（最新 `review/2026-11-17-T4.35a验收.md`）；**决策 144 条**（最新 **D-144 T4.35a 通过**）；**坑 K-01~K-86**（K-85 宿主服务双栈、**K-86 容器探针两个 Windows 环境坑**；K-81 第二次复发已升级为 `rules/dev.md §4.1` 第 11 条铁律）。
- **执行队列**：**T4.35b 播放器 API** → T4.35c 前端播放器 → T4.36 说话人 → T4.37 登录。每张独立 commit + 验收报告 + 我独立复核；改契约先 commit→build/up→全量（K-68/K-77）。

## 进度

- 阶段 0/1 ✅、W1–W4 ✅；真机反馈派生 T4.20/T4.21/T4.21b ✅；T4.22 ✅；D-122 批次 **16 张通过**；发布三件（T4.10/T4.9/T4.11）在批次之后。
- **T4.35a（第 59 份，D-144）✅**：`7cad12a`（16 文件 +636/−76，单 commit）——`infra/asr/server.py` 增 `segments[{start,end,text}]`（一次遍历同时产出 text+segments、旧三键逐字兼容、空白句丢弃、3 位小数）；新增 `asr/AsrClient`（RestClient 复用既有 900s 路由超时，**一次调用同取**）+ `AsrResponseParser` 纯函数；迁移 **V7** `task_result.segments jsonb`；worker 同一 upsert 写 transcript+segments（DONE 最后写）；详情**只增第 15 键**（旧任务/PASTE=null，静音=`[]`）。**我独立**：ASR 原始响应四键 18 段全断言；真语音 task 552（18 段有序）→DONE、静音 553=`[]`、旧任务 237/PASTE 536=`null`；psql 直查 V7 success、列 jsonb nullable、三态 243/4/2 零回填。
- **T4.32b（第 58 份，D-143）✅**：`4153891`——`POST /link/preview`（复用 32a UrlGuard、逐跳重校、只读 2MB、og 纯函数）+ BYOF `ProcessMediaFetcher`（argv 不过 shell、看门狗从进程启动起算、超时/非 0→502）+ `/tasks/link` 可选 `use_byof`（未配→400 `FETCHER_UNAVAILABLE`，32a 语义零改动）+ 前端「从链接导入」四分支；真机 Phase A 10/10、B1 13/13、B2 stdout、Playwright 7/7 + 未配 BYOF 5/5。
- **T4.32a（第 57 份，D-142）✅**：`641a6d1`——`UrlGuard`（逐 IP + 数字字面量自规范化）、`MediaFetcher`+`HttpMediaFetcher`、`POST /tasks/link`（`source_type=LINK`）；反例 23/23 全 400 无 500、正例 task 500 transcript 498。

## 待办（按顺序）

0. ✅ **T4.35a 已通过**（`7cad12a`）：ASR segments 三态落地；残余见"阻塞/风险"P3。
1. **下一张 = T4.35b 播放器 API**：`POST /tasks/{id}/audio-token`（Bearer）签发短时签名 URL（**不把 API_TOKEN 放 URL**）；`GET /tasks/{id}/audio?sig=&exp=` **免 Bearer 但验签+过期+绑定 taskId**，MinIO 流式转发 + **Range/206**（`<audio>` seek 必需），越界 416、PASTE/非 DONE 4xx、不存在 404；`ApiTokenFilter` 只豁免**精确一条**路径（禁豁免整个 `/tasks/`）；文档 §3.10 + `AUDIO_TOKEN_TTL_SEC`。
2. 然后 T4.35c 前端播放器（点句 seek/当前句高亮/`null` 与 `[]` 都降级隐藏/390 可操作）→ T4.36 说话人 → T4.37 登录。
3. **发布材料（T4.10 README+录屏 → T4.9 → T4.11 简历）在批次之后**；发布前清 MinIO 历史孤儿对象 74–79。
4. **纪律**：新样本/日志/override/缓存一律 D 盘；重建容器前先落旧日志（K-74）；**先 commit → 再 build/up → 再全量**（K-77）；**Lead 不写产品代码**（K-63）；前端写操作"点→回读"（K-80）；**依赖历史数据的断言必须 keyset 区间、不许用默认列表**（K-81 → rules 第 11 条）；**改文档只改 `D:\new\docs\...` 权威版**（T4.32b P3-1）。

## 阻塞 / 风险

- **无阻塞；配额预警**：今日剩 **96 分钟 / 10 任务**（每轮全量 +4–6 任务）。push：origin 与本地一致（无待推）。
- **T4.35a P3 残余（接受）**：`[]`（空音频）与 `null`（旧任务/PASTE）都表示"无播放器"，35c 要同时处理；segments 无索引/无服务端排序校验（解析层已丢 `end<start`）；`AsrClient` 不再走 Spring AI（配置键仍复用）；ASR 默认分段未做句长合并（35c 可评估）；ASR 超时链路未注入故障验证。
- **T4.32b P3 残余（接受）**：`{out}` 模式不排空子进程 stdout；预览 2MB 截断；无 `Content-Type` 直链判 unsupported；`FETCHER_CMD` 路径含空格不可用；BYOF 工具侧 SSRF/合规责任在工具；BYOF 413 与"exit 0 无产物"未真机验；预览与 `HttpMediaFetcher` 有重复实现。
- **T4.32a P3 残余（接受）**：`100.64/10` 等特殊段与 IPv6 NAT64/6to4 未拦；DNS rebinding TOCTOU；链接导入不校验 `TASK_MAX_SIZE_BYTES`；`FETCH_ALLOW_HOSTS` 靠部署纪律。
- 既有残余：标签下拉来源=已加载行并集（P3-3）；浏览器交互层无自动化回归（P3-4）；录音 60min/200MB 自动停与切后台未超长实测；`runFfprobe` 先读流再 waitFor 的既有阻塞风险；F-14 本地档 v1 不可用（D-089）；Webhook v1 无 SSRF 防护；批量 413/429 未实测；**v1 余量 0（D-032 仍成立）**。
- **运维**：Docker Desktop `AutoStart=false`，睡眠/重启后引擎可能不在（K-38）；改 compose 网络属性必须 `down` 再 `up`（K-44）；所有 curl 带 Bearer；重建容器前先存日志（D-087/K-74）。
