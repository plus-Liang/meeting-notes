# 交接：录音纪要平台（更新 2026-11-17 23:30，给下一个会话 / 新会话 WorkBuddy）

## 0. 当前位置

- HEAD 见 `git log -1`（当前为 T4.35a 验收文档提交；产品提交 **`7cad12a`**），工作区干净；**origin 与本地一致（无待推）**；栈 5/5 healthy（app 与 asr 都是 T4.35a 构建，DB 已含 V7）。**D-122 批次已通过 16 张**（T4.23…T4.30 含收尾、T4.32a、T4.32b、T4.35a）。
- 今日配额（11-17）已用 **104 分钟 / 90 任务**（上限 200/100，**只剩 10 任务**；每轮全量约 +4–6 任务 → 后续卡尽量 0 配额）。任务总数 **255**（全 DONE、DEAD 0）；MinIO **261 前缀 = 255 + 历史 6 孤儿（74–79）**。
- **下一张 = T4.35b 播放器 API**：`POST /tasks/{id}/audio-token`（Bearer）签发短时签名 URL（**不把 API_TOKEN 放 URL**）；`GET /tasks/{id}/audio?sig=&exp=` 免 Bearer 但**验签+过期+绑定 taskId**，MinIO 流式转发 + **Range/206**（`<audio>` seek 必需）、越界 416、PASTE/非 DONE 4xx、不存在 404；`ApiTokenFilter` 只豁免**精确一条**路径（禁豁免整个 `/tasks/`）。之后 T4.35c 前端播放器 → T4.36 说话人 → T4.37 登录；不做 T4.31 PWA / T4.33 INBOX / T4.34 RSS。

## 1. 下一步（按顺序）

1. **T4.35b**：先看我给的卡片消息（每卡只读「卡片 + 本 handoff + AGENT.md §2/§4」，D-137 成本纪律）。
2. 之后 T4.35c（详情页 `<audio>` + 点句 seek + 当前句高亮 + `null`/`[]` 都不显示播放器 + 390 可操作）→ T4.36（手工说话人）→ T4.37（多用户/登录）。发布三件在批次之后；发布前清 MinIO 孤儿 74–79。

## 2. 别踩的坑（最高频）

1. **DONE 最后写**（D-069）；`transcript:""` 也算正常 DONE（D-048）；`todos/keypoints` null≠[]（D-072/D-111）；**`segments` 旧任务/PASTE 是 null、空音频是 `[]`**（两者都要当"无数据"处理）；`ai_tier` 诚实；三档级联 CLOUD→LOCAL→EXTRACTIVE。
2. **认证是横切**（K-46）：`/api/v1/**` 全要 `Bearer <API_TOKEN>`，只豁免 `/api/v1/share/`；T4.35b 新免 Bearer 的音频流**只能豁免精确正则一条**（签名替代 Bearer），别把整个 `/tasks/` 放开。
3. **实验不改 `.env`**：临时 override + **用完删除并恢复**（K-44）；改 compose 网络属性必须 `down` 再 `up`；恢复后比 env diff。**ASR 镜像改动要 `build asr && up -d asr`**。
4. **配置判据打在消费点**（K-51）；新增配置键三处同步（`application.yml` / `.env.example` / `配置清单.md`）；新增错误码**只增不改**（码表现 10 个业务码）。
5. **先 commit → 再 build/up → 再全量**（K-68/K-77）；`head.txt` 记**提交后** HEAD；全量前 `git status` 必须干净。
6. **Lead 不写产品代码**（K-63）；写码方单 commit、不 push。**改文档只改 `D:\new\docs\...` 权威版**（代码仓 `docs/` 是生成副本，T4.32b P3-1 教训）。
7. **依赖库里历史数据的断言必须用 `before_id` keyset/显式区间，禁止默认列表**（K-81 第二次复发 → `rules/dev.md §4.1` 第 11 条，T4.35a 全量首跑因此红过一次）。
8. **容器探针环境坑**（K-86）：slim 镜像（ASR）**没有 curl** → 用镜像自带 python 手拼 multipart；git-bash 的 MSYS 会转换 `docker exec` 里的 `/tmp` 路径 → `MSYS_NO_PATHCONV=1` 或用 PowerShell；探针文件 `docker cp` 进容器、用完删除。
9. **容器到宿主的测试服务**：`host.docker.internal` 在容器里可能是 **IPv6**（K-85）→ 宿主服务双栈监听，Windows 上 `IPV6_V6ONLY=0` 必须在 `bind` 前设。
10. **外部工具/输出**：先判输出可解析再 parse（K-82）；重构命令调用别漏程序名（K-83）；子进程要 stderr 排空 + 从进程启动起算的看门狗（T4.32b 已验证）。
11. **前端写操作必须"点一下再读 API"**（K-80）；全量 `ASR_SMOKE_AUDIO` 用真实语音 `D:\datasets\meeting-notes-samples\probe.wav`（1 秒测试音会假红）。

## 3. 关键文件

`state.md`（权威状态）· `tasks.md`（T4.32a/T4.32b/T4.35a 已 ✅ 行）· `tasks-starter.md`（**T4.35b 卡已就绪**）· `decisions.md`（**144 条**，最新 D-144）· `踩坑与复盘-面试用.md`（**K-01~K-86**）· `review/`（**59 份**，最新 `2026-11-17-T4.35a验收.md`）· `接入指南.md`（§3.9 直链 / §3.9a 预览+BYOF / §3.4 码表 / **§3.2 详情 15 键 + segments**）· `配置清单.md` · `rules/dev.md`（§4.1 第 11 条新规则）· `scripts/test.ps1`（全量 435 / `-UnitOnly` 417）· `tools/sync-docs-to-code.ps1` · `app/src/main/resources/db/migration/`（V1–V7）· `infra/asr/server.py`。

## 4. 未决

- **T4.35a P3 残余（接受）**：`[]` 与 `null` 都表示"无播放器"（35c 处理）；segments 无索引/无服务端排序校验；`AsrClient` 不再走 Spring AI；ASR 默认分段未做句长合并；ASR 超时链路未注入故障验证。
- **T4.32b P3 残余（接受）**：`{out}` 模式不排空 stdout；预览 2MB 截断；无 Content-Type 判 unsupported；`FETCHER_CMD` 路径含空格不可用；BYOF 工具侧 SSRF/合规责任在工具；BYOF 413 与 exit0 无产物未真机验；预览与 `HttpMediaFetcher` 有重复实现。
- T4.32a 残余：`100.64/10`/NAT64/6to4 未拦；DNS rebinding TOCTOU；链接导入不查 `TASK_MAX_SIZE_BYTES`。
- T4.30 残余：60min/200MB 自动停与切后台未超长实测；`runFfprobe` 先读流再 waitFor 的既有阻塞风险。
- MinIO 历史孤儿 74–79 发布前清理（可选）；`v1` 余量 0（D-032）；**配额只剩 10 任务**。
