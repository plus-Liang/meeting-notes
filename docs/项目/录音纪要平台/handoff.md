# 交接：录音纪要平台（更新 2026-11-18 15:40，给下一个会话 / 新会话 WorkBuddy）

## 0. 当前位置

- 产品提交 **`d54766a`**（T4.36），工作区干净；**origin 已同步（含本次验收提交，与本地一致，待推 0）**；栈 5/5 healthy。**D-122 批次已通过 19 张**（T4.23…T4.30 含收尾、T4.32a、T4.32b、T4.35a、T4.35b、T4.35c、T4.36）。
- 配额（11-18 新的一天）：已用 **12 分钟 / 12 任务**（上限 200/100，余量足）。任务总数 **279**（全 DONE、DEAD 0）；MinIO **285 前缀 = 279 + 历史 6 孤儿（74–79）**；`task_result.speaker_map` 非空行 0（验证脏数据已清）。
- **下一张 = T4.37 登录/多用户（必须新会话）**：登录/注册/会话 + 替换 27 处 `DEFAULT_USER_ID` + 数据隔离 + 配额按人；**必须把 userId 绑进音频签名**（收 T4.35b P3-1）。之后发布三件（T4.10 README+录屏 → T4.9 → T4.11）；不做 T4.31 PWA / T4.33 INBOX / T4.34 RSS。
- **会话策略（按用户要求区分）**：T4.36 已在**同一 WorkBuddy 会话**完成（延续 `task.html` 句子列表）；**T4.37 必须新会话**（大卡横切、要收音频签名绑 userId）。

## 1. 下一步（按顺序）

1. 我先写 **T4.37 卡**（新会话第一张）；新会话开工前先跑三行（`git log -1`、`git status --short`、`Get-Content D:\new\artifacts\T4.36\workbuddy\head.txt`）。
2. T4.37 完成后发布三件在批次之后；发布前清 MinIO 孤儿 74–79。

## 2. 别踩的坑（最高频）

1. **DONE 最后写**（D-069）；`transcript:""` 也算正常 DONE（D-048）；`todos/keypoints` null≠[]（D-072/D-111）；**`segments` 旧任务/PASTE 是 null、空音频是 `[]`**；**`speaker_map` 旧任务/未标注/清除后是 null（落 NULL，不是 `{}`）**；`ai_tier` 诚实；三档级联 CLOUD→LOCAL→EXTRACTIVE。
2. **认证是横切**（K-46）：`/api/v1/**` 全要 `Bearer <API_TOKEN>`，只豁免 `/api/v1/share/` 和 **GET `/api/v1/tasks/\d+/audio`**（签名替代 Bearer）；**禁止**再放开别的 `/tasks` 路径（`PATCH /speaker-map` 已实测匿名 401）。
3. **播放器 URL 不许带 API_TOKEN**：必须 `POST /tasks/{id}/audio-token` 拿签名 URL；`<audio>` 不能带 Authorization 头。**T4.37 要把 userId 绑进签名**。
4. **实验不改 `.env`**：临时 override + **用完删除并恢复**（K-44）；改 compose 网络属性必须 `down` 再 `up`。配额紧张时用 override 提高 `QUOTA_TASKS_PER_DAY`，别改 `.env`。
5. **配置判据打在消费点**（K-51）；新增配置键三处同步（`application.yml` / `.env.example` / `配置清单.md`）；**新增详情键只增在最后**（现 **16 键**，T4.36 加了 `speaker_map`）。
6. **先 commit → 再 build/up → 再全量**（K-68/K-77）；`head.txt` 记**提交后** HEAD；全量前 `git status` 必须干净。**改 static/ 必须重建 app 镜像**（静态文件在镜像里；我验过服务端 sha256 == 工作区）。
7. **Lead 不写产品代码**（K-63）；写码方单 commit、不 push。**改文档只改 `D:\new\docs\...` 权威版**（代码仓 `docs/` 是生成副本，改完跑 `tools\sync-docs-to-code.ps1`）。
8. **依赖库里历史数据的断言必须 keyset 区间**（K-81 → `rules/dev.md` 第 11 条 / `AGENT.md` 第 12 条）；UI 验证**优先用已有任务**（552/535/237），别为测试新建任务烧配额和 ASR 时间。
9. **进度类断言先暂停再读**（K-88：点句会自动播放，等一会儿读 `audio.currentTime` 会把"正在播"误判成"seek 不准"；更稳的是断言 `seeked` 时刻 + 暂停后复核）。
10. **卡面里的列名/字段名先对真实 schema**（K-89）：T4.36 卡写 `updated_at`，`task_result` 实际只有 `created_at`——按既有 `saveTranscript` 口径落 `created_at = now()` 并写进文档，不为它建迁移；单测构造 null 值用 `LinkedHashMap` 不用 `Map.of`；上限值要测"恰好合法/上限+1"两侧。
11. **别直接读 MinIO 数据目录做字节比对**（K-87：`part.1` 是 bitrot 编码）。
12. **容器探针环境坑**（K-86）：slim 镜像（ASR）无 curl → python urllib 手拼 multipart；git-bash 的 MSYS 会转换 `docker exec` 里的 `/tmp`（`MSYS_NO_PATHCONV=1` 或 PowerShell）；探针文件 `docker cp` 进去、用完删。
13. **容器到宿主的测试服务**：`host.docker.internal` 在容器里可能是 IPv6（K-85）→ 宿主服务双栈，Windows 上 `IPV6_V6ONLY=0` 必须在 `bind` 前设。
14. **外部工具/输出**：先判输出可解析再 parse（K-82）；重构命令调用别漏程序名（K-83）；子进程 stderr 排空 + 从进程启动起算的看门狗。
15. **前端写操作必须"点一下再读 API"**（K-80）；全量 `ASR_SMOKE_AUDIO` 用真实语音 `D:\datasets\meeting-notes-samples\probe.wav`。

## 3. 关键文件

`state.md`（权威状态）· `tasks.md`（T4.32a/b、T4.35a/b/c、T4.36 已 ✅ 行）· `tasks-starter.md`（**T4.36 卡已 ✅，T4.37 卡待我写**）· `decisions.md`（**147 条**，最新 D-147）· `踩坑与复盘-面试用.md`（**K-01~K-89**）· `review/`（**62 份**，最新 `2026-11-18-T4.36验收.md`）· `接入指南.md`（§3.9/§3.9a 链接导入、§3.10 音频流、**§3.11 说话人标注**、§3.2 详情 **16 键**）· `配置清单.md` · `rules/dev.md`（§4.1 第 11 条）· `scripts/test.ps1`（全量 457 / `-UnitOnly` 439）· `app/src/main/resources/static/{task.html,style.css,app.js}`（35c/36 已改）· `app/src/main/java/.../web/SpeakerMapController.java` + `task/SpeakerMapService.java`（T4.36 新增）· `app/src/main/resources/db/migration/`（V1–V7，**V1 已有 speaker_map**）· `tools/sync-docs-to-code.ps1`。

## 4. 未决

- **推送**：origin 与本地一致（含 T4.36 验收提交），待推 0。2026-11-18 凌晨 FlClash 的 HTTPS 通道曾故障约 2 小时（HTTP 200、HTTPS 全 000），已恢复；以后同类现象先按"代理 TLS 故障"排查，不要动本地提交。
- **T4.36 P3 残余（接受）**：组序仅首现序不可拖拽、同名/别名不自动合并、菜单无 Escape/外点关闭、分组视图 `scrollIntoView nearest` 长列表需手动滚、缺 `speaker_map` 键视同清空（宽松）、键前导零归一化、自动说话人分离不做（先 spike 再定）、`speaker.prevMap` 无害死代码、缺 Content-Type 回 415（未写入 §3.11）。
- **T4.35c P3 残余（接受）**：滚动跟随增强、重渲染不保留滚动/高亮位置、无键盘快捷键、`share.html` 不加播放器、高亮精度 ≤250ms。
- **T4.35b P3 残余（接受）**：**音频 GET 未绑 user_id**（T4.37 必收）；PASTE 保护依赖签发闸；`HEAD /audio` 未实现、多段 Range 416；MinIO 416 兜底未注入故障；签名无吊销。
- T4.35a/T4.32b/T4.32a 残余同前（见 state.md）。
- MinIO 历史孤儿 74–79 发布前清理（可选）；`v1` 余量 0（D-032）。
