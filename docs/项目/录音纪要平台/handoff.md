# 交接：录音纪要平台（更新 2026-11-17 21:40，给下一个会话 / 新会话 WorkBuddy）

## 0. 当前位置

- HEAD 见 `git log -1`（当前为 T4.32a 验收文档提交；产品提交 **`641a6d1`**），工作区干净；**origin 与本地一致（无待推）**；栈 5/5 healthy；ASR `small`。**D-122 批次已通过 14 张**（T4.23…T4.30 含收尾、T4.32a）。
- 今日配额（11-17）已用 **67 分钟 / 61 任务**（上限 200/100）；任务总数 **226**（全 DONE、DEAD 0、LINK 2）；MinIO **232 前缀 = 226 + 历史 6 孤儿（74–79）**。
- **下一张 = T4.32b 链接导入**：L1 页面链接确认卡（og:title/时长）、L2 Content-Type 探测走现有下载管线、BYOF `FETCHER_CMD`（不配返回 `FETCHER_UNAVAILABLE`）、前端入口；**依赖已就绪**的 `MediaFetcher` 接口与 `POST /api/v1/tasks/link`。之后 T4.35a/b/c 播放器（V7）→ T4.36 说话人 → T4.37 登录；不做 T4.31 PWA / T4.33 INBOX / T4.34 RSS。

## 1. 下一步（按顺序）

1. **T4.32b**：先看我给的卡片消息（每卡只读「卡片 + 本 handoff + AGENT.md §2/§4」，D-137 成本纪律）。
2. 之后 T4.35a/b/c 播放器（V7 迁移）→ T4.36 说话人 → T4.37 登录。发布三件（T4.10/T4.9/T4.11）在批次之后；发布前清 MinIO 历史孤儿 74–79。

## 2. 别踩的坑（最高频）

1. **DONE 最后写**（D-069）；`transcript:""` 也算正常 DONE（D-048）；`todos/keypoints` null≠[]（D-072/D-111）；`ai_tier` 必须诚实；三档级联 CLOUD→LOCAL→EXTRACTIVE。
2. **认证是横切**（K-46）：`/api/v1/**` 全要 `Bearer <API_TOKEN>`，只豁免 `/api/v1/share/`；新增 `/tasks/link` 已被覆盖（匿名 401 验过）。
3. **实验不改 `.env`**：临时 override + **用完删除并恢复**（K-44）；改 compose 网络属性必须 `down` 再 `up`；恢复后比 env diff。
4. **配置判据打在消费点**（K-51）；新增配置键必须三处同步：`application.yml` / `.env.example` / `配置清单.md`。
5. **先 commit → 再 `docker compose build app && up -d app` → 再全量**（K-68/K-77）；`head.txt` 记**提交后** HEAD；全量前文件 `git status` 必须干净。
6. **Lead 不写产品代码**（K-63）；写码方单 commit、不 push。
7. **SSRF/地址类**：数字字面量 IP 不能只靠系统解析器（K-84：JDK 21 把 `0177.0.0.1` 按十进制解析成公网，守卫内要按 inet_aton 自规范化）；重定向**每跳**都要校验；`FETCH_ALLOW_HOSTS` 默认空（命中即跳过 IP 拦截）。
8. **前端写操作必须"点一下再读 API"**（K-80）；**E2E 断言不许依赖全库数据规模**（K-81）；**外部工具输出先判可解析、不只看 exit code**（K-82）。
9. **全量 E2E 的 `ASR_SMOKE_AUDIO` 要用真实语音**：`D:\datasets\meeting-notes-samples\probe.wav`；拿 `artifacts\T4.14\probe-1s.wav`（1 秒测试音）会让 `AsrSmokeTest` 假红 389/390。
10. **PS 细节**：含中文 `.ps1` 必须 UTF-8 BOM（K-54）；`D:\new` **不是** git 仓（只有代码仓是）；curl 拼 JSON 别用 PS 双引号（`\"` 不是转义），用 Python/here-string。

## 3. 关键文件

`state.md`（权威状态）· `tasks.md`（T4.32a 已 ✅ 行）· `tasks-starter.md`（**T4.32b 卡已就绪**）· `decisions.md`（**142 条**，最新 D-142）· `踩坑与复盘-面试用.md`（**K-01~K-84**）· `review/`（**57 份**，最新 `2026-11-17-T4.32a验收.md`）· `接入指南.md`（**§3.9 链接导入** + §3.4 码表）· `配置清单.md`（`FETCH_*` 5 行）· `scripts/test.ps1`（全量 390 / `-UnitOnly` 374）· `tools/sync-docs-to-code.ps1`。

## 4. 未决

- **T4.32a P3 残余（接受）**：`100.64/10` CGNAT、`192.0.0.0/24`、`198.18/15`、IPv6 NAT64/6to4 未拦；DNS rebinding TOCTOU 未做连接后复核；链接导入不校验 `TASK_MAX_SIZE_BYTES`（只受 `FETCH_MAX_BYTES`）；allow-hosts 靠部署纪律。
- T4.30 残余：60min/200MB 自动停与切后台未超长实测；`runFfprobe` 先读流再 waitFor 的既有阻塞风险。
- T4.29 残余：ACTIVE 三状态合并 + label/favorite 组合未真机断言；标签下拉来源=已加载行并集；meta 对非 DONE 可改（后端无状态闸）；浏览器交互层无自动化回归（P3-4）。
- MinIO 历史孤儿 74–79 发布前清理（可选）；`v1` 余量 0（D-032）。
