# 交接：录音纪要平台（更新 2026-11-18 03:10，给下一个会话 / 新会话 WorkBuddy）

## 0. 当前位置

- 产品提交 **`bbe2ca3`**（T4.35c），工作区干净；**注意：本地领先 origin 2 个提交待推**（产品 + 验收文档）——2026-11-18 凌晨 FlClash TUN 的 HTTPS 通道故障（HTTP 200 但 HTTPS 全 000），`git push` 两种 TLS 后端都握手失败；**网络恢复后第一件事**：`git -C D:\new\projects\meeting-notes push origin main`。栈 5/5 healthy。**D-122 批次已通过 18 张**（T4.23…T4.30 含收尾、T4.32a、T4.32b、T4.35a、T4.35b、T4.35c）。
- 配额（11-18 新的一天）：已用 **8 分钟 / 8 任务**（上限 200/100，余量足）。任务总数 **271**（全 DONE、DEAD 0）；MinIO **277 前缀 = 271 + 历史 6 孤儿（74–79）**。
- **下一张 = T4.36 手工说话人**：`PATCH /tasks/{id}/speaker-map`（句子下标 → 标签，校验矩阵）+ 详情**只增第 16 键** `speaker_map`（旧任务 null）+ 前端句子 chip / 分组视图；**用已有任务 552（18 段）/535（35 段）/237（无 segments）验证，零新建任务**。之后 T4.37 登录/多用户；不做 T4.31 PWA / T4.33 INBOX / T4.34 RSS。
- **已核实（别白写迁移）**：`task_result.speaker_map JSONB` 在 **V1 建表就有**（`V1__init.sql` 第 31 行），T4.36 **不需要 V8 迁移**。
- **会话策略**：T4.36 **在同一个 WorkBuddy 会话里继续**（刚改完 `task.html` 句子列表，说话人 chip 就挂这套句子上）；**T4.37 必须新会话**（大卡横切、要收音频签名绑 userId）。

## 1. 下一步（按顺序）

1. **T4.36**：先看我给的新卡消息（每卡只读「卡片 + 本 handoff + AGENT.md §2/§4」，D-137 成本纪律）。
2. 之后 **T4.37 登录/多用户**（登录/注册/会话 + 替换 27 处 `DEFAULT_USER_ID` + 数据隔离 + 配额按人；**必须把 userId 绑进音频签名**）。发布三件在批次之后；发布前清 MinIO 孤儿 74–79。

## 2. 别踩的坑（最高频）

1. **DONE 最后写**（D-069）；`transcript:""` 也算正常 DONE（D-048）；`todos/keypoints` null≠[]（D-072/D-111）；**`segments` 旧任务/PASTE 是 null、空音频是 `[]`**；**`speaker_map` 旧任务/未标注是 null、`{}` 表示清除后的空标注**；`ai_tier` 诚实；三档级联 CLOUD→LOCAL→EXTRACTIVE。
2. **认证是横切**（K-46）：`/api/v1/**` 全要 `Bearer <API_TOKEN>`，只豁免 `/api/v1/share/` 和 **GET `/api/v1/tasks/\d+/audio`**（签名替代 Bearer）；**禁止**再放开别的 `/tasks` 路径。
3. **播放器 URL 不许带 API_TOKEN**：必须 `POST /tasks/{id}/audio-token` 拿签名 URL；`<audio>` 不能带 Authorization 头。
4. **实验不改 `.env`**：临时 override + **用完删除并恢复**（K-44）；改 compose 网络属性必须 `down` 再 `up`。配额紧张时用 override 提高 `QUOTA_TASKS_PER_DAY`，别改 `.env`。
5. **配置判据打在消费点**（K-51）；新增配置键三处同步（`application.yml` / `.env.example` / `配置清单.md`）；**新增详情键只增在最后**（现 15 键，T4.36 后 16 键）。
6. **先 commit → 再 build/up → 再全量**（K-68/K-77）；`head.txt` 记**提交后** HEAD；全量前 `git status` 必须干净。**改 static/ 必须重建 app 镜像**（静态文件在镜像里；我验过服务端 sha256 == 工作区）。
7. **Lead 不写产品代码**（K-63）；写码方单 commit、不 push。**改文档只改 `D:\new\docs\...` 权威版**（代码仓 `docs/` 是生成副本）。
8. **依赖库里历史数据的断言必须 keyset 区间**（K-81 → `rules/dev.md` 第 11 条 / `AGENT.md` 第 12 条）；UI 验证**优先用已有任务**（552/535/237），别为测试新建任务烧配额和 ASR 时间。
9. **进度类断言先暂停再读**（K-88：点句会自动播放，等一会儿读 `audio.currentTime` 会把"正在播"误判成"seek 不准"）。
10. **别直接读 MinIO 数据目录做字节比对**（K-87：`part.1` 是 bitrot 编码）。
11. **容器探针环境坑**（K-86）：slim 镜像（ASR）无 curl → python urllib 手拼 multipart；git-bash 的 MSYS 会转换 `docker exec` 里的 `/tmp`（`MSYS_NO_PATHCONV=1` 或 PowerShell）；探针文件 `docker cp` 进去、用完删。
12. **容器到宿主的测试服务**：`host.docker.internal` 在容器里可能是 IPv6（K-85）→ 宿主服务双栈，Windows 上 `IPV6_V6ONLY=0` 必须在 `bind` 前设。
13. **外部工具/输出**：先判输出可解析再 parse（K-82）；重构命令调用别漏程序名（K-83）；子进程 stderr 排空 + 从进程启动起算的看门狗。
14. **前端写操作必须"点一下再读 API"**（K-80）；全量 `ASR_SMOKE_AUDIO` 用真实语音 `D:\datasets\meeting-notes-samples\probe.wav`。

## 3. 关键文件

`state.md`（权威状态）· `tasks.md`（T4.32a/b、T4.35a/b/c 已 ✅ 行）· `tasks-starter.md`（**T4.36 卡已就绪**）· `decisions.md`（**146 条**，最新 D-146）· `踩坑与复盘-面试用.md`（**K-01~K-88**）· `review/`（**61 份**，最新 `2026-11-18-T4.35c验收.md`）· `接入指南.md`（§3.9/§3.9a 链接导入、§3.10 音频流、§3.2 详情 15 键→T4.36 变 16 键）· `配置清单.md` · `rules/dev.md`（§4.1 第 11 条）· `scripts/test.ps1`（全量 447 / `-UnitOnly` 429）· `app/src/main/resources/static/{task.html,style.css,app.js}`（35c 已改、36 继续改）· `app/src/main/resources/db/migration/`（V1–V7，**V1 已有 speaker_map**）· `tools/sync-docs-to-code.ps1`。

## 4. 未决

- **推送待办（P0 于交接前确认）**：本地领先 origin **2 个提交**（`bbe2ca3` 产品 + T4.35c 验收文档），因 FlClash HTTPS 故障推不上去；网络恢复后立刻 `git -C D:\new\projects\meeting-notes push origin main` 并确认 `git rev-parse HEAD == git rev-parse origin/main`。诊断：HTTP 80 正常、HTTPS 全 000（含 baidu），本机 7890 代理也出不去——不是 git/账号/权限问题；**别删本地提交、别 reset**。
- **T4.35c P3 残余（接受）**：高亮句出列表可视区时自动滚动会轻微牵动用户（可加"用户滚动后暂停跟随 5s"）；重渲染不保留滚动/高亮位置（日常无影响）；无键盘快捷键；`share.html` 不加播放器；高亮精度受 `timeupdate` ~4Hz（≤250ms）。
- **T4.35b P3 残余（接受）**：**音频 GET 未绑 user_id**（T4.37 必收）；PASTE 保护依赖签发闸；`HEAD /audio` 未实现、多段 Range 416；MinIO 416 兜底未注入故障；签名无吊销。
- T4.35a/T4.32b/T4.32a 残余同前（见 state.md）。
- MinIO 历史孤儿 74–79 发布前清理（可选）；`v1` 余量 0（D-032）。
