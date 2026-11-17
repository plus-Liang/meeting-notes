# 交接：录音纪要平台（更新 2026-11-17 22:40，给下一个会话 / 新会话 WorkBuddy）

## 0. 当前位置

- HEAD 见 `git log -1`（当前为 T4.32b 验收文档提交；产品提交 **`4153891`**），工作区干净；**origin 与本地一致（无待推）**；栈 5/5 healthy；ASR `small`。**D-122 批次已通过 15 张**（T4.23…T4.30 含收尾、T4.32a、T4.32b）。
- 今日配额（11-17）已用 **89 分钟 / 76 任务**（上限 200/100，**剩 24 任务**）；任务总数 **241**（全 DONE、DEAD 0、LINK 11）；MinIO **247 前缀 = 241 + 历史 6 孤儿（74–79）**。
- **下一张 = T4.35a ASR segments（句子级时间戳）**：`infra/asr/server.py` 增 `segments[{start,end,text}]`（旧三键 `text/duration/cost_sec` 逐字兼容）；Java 侧**一次 ASR 调用**同时取 text+segments（Spring AI `TranscriptionModel` 拿不到就换仓库内轻量 HTTP 客户端）；迁移 **V7** `task_result.segments jsonb`；`GET /tasks/{id}` **只增** `segments`（旧任务 null）。之后 T4.35b 播放器 API → T4.35c 前端播放器 → T4.36 说话人 → T4.37 登录；不做 T4.31 PWA / T4.33 INBOX / T4.34 RSS。

## 1. 下一步（按顺序）

1. **T4.35a**：先看我给的卡片消息（每卡只读「卡片 + 本 handoff + AGENT.md §2/§4」，D-137 成本纪律）。
2. 之后 T4.35b（音频地址+句时间 API，越权防护）→ T4.35c（`<audio>` 点句 seek/高亮/无 segments 降级/390 可操作）→ T4.36（手工说话人）→ T4.37（多用户/登录）。发布三件在批次之后；发布前清 MinIO 孤儿 74–79。

## 2. 别踩的坑（最高频）

1. **DONE 最后写**（D-069）；`transcript:""` 也算正常 DONE（D-048）；`todos/keypoints` null≠[]（D-072/D-111）；`segments` 旧任务是 **null**（不是 []）；`ai_tier` 必须诚实；三档级联 CLOUD→LOCAL→EXTRACTIVE。
2. **认证是横切**（K-46）：`/api/v1/**` 全要 `Bearer <API_TOKEN>`，只豁免 `/api/v1/share/`；新增 `/link/preview`、`use_byof` 已被覆盖（匿名 401 验过）。
3. **实验不改 `.env`**：临时 override + **用完删除并恢复**（K-44）；改 compose 网络属性必须 `down` 再 `up`；恢复后比 env diff。**ASR 镜像改动要 `build asr && up -d asr`**（app 与 asr 两个镜像都可能在卡内）。
4. **配置判据打在消费点**（K-51）；新增配置键三处同步：`application.yml` / `.env.example` / `配置清单.md`；新增错误码只增不改（码表现 10 个业务码）。
5. **先 commit → 再 build/up → 再全量**（K-68/K-77）；`head.txt` 记**提交后** HEAD；全量前 `git status` 必须干净。
6. **Lead 不写产品代码**（K-63）；写码方单 commit、不 push。**改文档只改 `D:\new\docs\...` 权威版**，代码仓 `docs/` 是生成副本（T4.32b P3-1：写码方曾改生成副本，我回灌）。
7. **SSRF**：数字字面量 IP 要在守卫内按 inet_aton 自规范化（K-84）；重定向**每跳**校验；`FETCH_ALLOW_HOSTS`/`FETCHER_CMD` 默认空（BYOF 是"关防护"式信任开关，靠部署纪律）。
8. **容器到宿主的测试服务**：容器里 `host.docker.internal` 可能是 **IPv6**（K-85）→ 宿主服务要**双栈监听**，Windows 上 `IPV6_V6ONLY=0` 必须在 `bind` 前设；宿主自检 200 ≠ 容器可达，先在容器内 curl 同一 URL。
9. **外部工具/输出**：先判输出可解析再 parse（K-82）；重构命令调用别漏程序名（K-83）；子进程要 `stderr` 排空 + 从进程启动起算的看门狗（T4.32b 已验证）。
10. **前端写操作必须"点一下再读 API"**（K-80）；**E2E 断言不许依赖全库数据规模**（K-81）；全量 `ASR_SMOKE_AUDIO` 用真实语音 `D:\datasets\meeting-notes-samples\probe.wav`（1 秒测试音会假红）。

## 3. 关键文件

`state.md`（权威状态）· `tasks.md`（T4.32a/T4.32b 已 ✅ 行）· `tasks-starter.md`（**T4.35a 卡已就绪**）· `decisions.md`（**143 条**，最新 D-143）· `踩坑与复盘-面试用.md`（**K-01~K-85**）· `review/`（**58 份**，最新 `2026-11-17-T4.32b验收.md`）· `接入指南.md`（§3.9 直链 / **§3.9a 预览+BYOF** / §3.4 码表 10 个）· `配置清单.md`（`FETCH_*` 5 行 + `FETCH_PREVIEW_MAX_BYTES`/`FETCHER_CMD`/`FETCHER_TIMEOUT_MS`）· `scripts/test.ps1`（全量 420 / `-UnitOnly` 404）· `tools/sync-docs-to-code.ps1` · `infra/asr/server.py`（T4.35a 要改）· `app/src/main/resources/db/migration/`（V1–V6，下一版 V7）。

## 4. 未决

- **T4.32b P3 残余（接受）**：`{out}` 模式不排空 stdout；预览 2MB 截断；无 Content-Type 直链判 unsupported；`FETCHER_CMD` 路径含空格不可用；BYOF 工具侧 SSRF/合规责任在工具；BYOF 413 与"exit 0 无产物"未真机验；预览与 `HttpMediaFetcher` 有重复实现。
- T4.32a 残余：`100.64/10`/NAT64/6to4 未拦；DNS rebinding TOCTOU；链接导入不查 `TASK_MAX_SIZE_BYTES`。
- T4.30 残余：60min/200MB 自动停与切后台未超长实测；`runFfprobe` 先读流再 waitFor 的既有阻塞风险。
- MinIO 历史孤儿 74–79 发布前清理（可选）；`v1` 余量 0（D-032）。
