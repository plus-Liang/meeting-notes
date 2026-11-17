# 交接：录音纪要平台（更新 2026-11-18 00:05，给下一个会话 / 新会话 WorkBuddy）

## 0. 当前位置

- HEAD 见 `git log -1`（当前为 T4.35b 验收文档提交；产品提交 **`f6d850f`**），工作区干净；**origin 与本地一致（无待推）**；栈 5/5 healthy。**D-122 批次已通过 17 张**（T4.23…T4.30 含收尾、T4.32a、T4.32b、T4.35a、T4.35b）。
- 配额：11-17 收盘 98/100 任务，**11-18 00:00 已日切重置**（新一天从 0 起）。任务总数 **263**（全 DONE、DEAD 0）；MinIO **269 前缀 = 263 + 历史 6 孤儿（74–79）**。
- **下一张 = T4.35c 前端播放器**：详情页 `<audio>`（src 走 `audio-token` 签发）+ 句子列表**点句 seek** + **当前句高亮跟随**；`segments=null` 与 `[]` **双降级**（不渲染播放器）；390 可操作；**用已有任务 552/535/237 验证，零新建任务**。之后 T4.36 说话人 → T4.37 登录；不做 T4.31 PWA / T4.33 INBOX / T4.34 RSS。
- **会话策略**：T4.35c **在 T4.35b 的同一个会话里继续**（35b 的 `audio-token`/`audio` 契约还在上下文，省重读）；但消息里要明确"这是新卡 T4.35c"。T4.36 视会话长度决定；**T4.37 必须新会话**。

## 1. 下一步（按顺序）

1. **T4.35c**：先看我给的新卡消息（每卡只读「卡片 + 本 handoff + AGENT.md §2/§4」，D-137 成本纪律）。
2. 之后 T4.36（手工说话人：详情页给句子打 speaker 标签 → `speaker_map`；纪要/转写按说话人分组）→ T4.37（登录/注册/会话 + 替换 27 处 `DEFAULT_USER_ID` + 数据隔离 + 配额按人；**必须把 userId 绑进音频签名**，见 T4.35b P3-1）。发布三件在批次之后；发布前清 MinIO 孤儿 74–79。

## 2. 别踩的坑（最高频）

1. **DONE 最后写**（D-069）；`transcript:""` 也算正常 DONE（D-048）；`todos/keypoints` null≠[]（D-072/D-111）；**`segments` 旧任务/PASTE 是 null、空音频是 `[]`，两者都要当"无播放器"**；`ai_tier` 诚实；三档级联 CLOUD→LOCAL→EXTRACTIVE。
2. **认证是横切**（K-46）：`/api/v1/**` 全要 `Bearer <API_TOKEN>`，只豁免 `/api/v1/share/` 和 **GET `/api/v1/tasks/\d+/audio`**（签名替代 Bearer）；**禁止**再放开别的 `/tasks` 路径。
3. **播放器 URL 不许带 API_TOKEN**：必须 `POST /tasks/{id}/audio-token` 拿签名 URL；`<audio>` 不能带 Authorization 头，所以才需要签名。
4. **实验不改 `.env`**：临时 override + **用完删除并恢复**（K-44）；改 compose 网络属性必须 `down` 再 `up`。**配额紧张时用 override 提高 `QUOTA_TASKS_PER_DAY`，别改 `.env`**。
5. **配置判据打在消费点**（K-51）；新增配置键三处同步（`application.yml` / `.env.example` / `配置清单.md`）；新增错误码**只增不改**。
6. **先 commit → 再 build/up → 再全量**（K-68/K-77）；`head.txt` 记**提交后** HEAD；全量前 `git status` 必须干净。**改 static/ 也要 rebuild app 镜像**（静态文件在镜像里）。
7. **Lead 不写产品代码**（K-63）；写码方单 commit、不 push。**改文档只改 `D:\new\docs\...` 权威版**（代码仓 `docs/` 是生成副本）。
8. **依赖库里历史数据的断言必须 keyset 区间**（K-81 → `rules/dev.md` 第 11 条 / `AGENT.md` 第 12 条）；UI 验证**优先用已有任务**（552/535/237），别为测试新建任务烧配额和 ASR 时间。
9. **别直接读 MinIO 数据目录做字节比对**（K-87：`part.1` 是 bitrot 编码）——走 S3 API 或与上传源字节比。
10. **容器探针环境坑**（K-86）：slim 镜像（ASR）无 curl → 用镜像自带 python 手拼 multipart；git-bash 的 MSYS 会转换 `docker exec` 里的 `/tmp`（`MSYS_NO_PATHCONV=1` 或 PowerShell）；探针文件 `docker cp` 进去、用完删除。
11. **容器到宿主的测试服务**：`host.docker.internal` 在容器里可能是 IPv6（K-85）→ 宿主服务双栈监听，Windows 上 `IPV6_V6ONLY=0` 必须在 `bind` 前设。
12. **外部工具/输出**：先判输出可解析再 parse（K-82）；重构命令调用别漏程序名（K-83）；子进程要 stderr 排空 + 从进程启动起算的看门狗。
13. **前端写操作必须"点一下再读 API"**（K-80；35c 的点句 seek 尤其要"点→读 `audio.currentTime`/高亮 `data-start`"）；全量 `ASR_SMOKE_AUDIO` 用真实语音 `D:\datasets\meeting-notes-samples\probe.wav`。

## 3. 关键文件

`state.md`（权威状态）· `tasks.md`（T4.32a/b、T4.35a/b 已 ✅ 行）· `tasks-starter.md`（**T4.35c 卡已就绪**）· `decisions.md`（**145 条**，最新 D-145）· `踩坑与复盘-面试用.md`（**K-01~K-87**）· `review/`（**60 份**，最新 `2026-11-17-T4.35b验收.md`）· `接入指南.md`（§3.9/§3.9a 链接导入、**§3.10 音频流**、§3.2 详情 15 键）· `配置清单.md`（含 `AUDIO_TOKEN_TTL_SEC`）· `rules/dev.md`（§4.1 第 11 条）· `scripts/test.ps1`（全量 447 / `-UnitOnly` 429）· `app/src/main/resources/static/{task.html,style.css,app.js}`（35c 要改的）· `tools/sync-docs-to-code.ps1`。

## 4. 未决

- **T4.35b P3 残余（接受）**：**音频 GET 未绑 user_id**（T4.37 必收）；PASTE 保护依赖签发闸；`HEAD /audio` 未实现、多段 Range 416；MinIO 416 兜底未注入故障；签名无吊销。
- T4.35a P3 残余：`[]`/`null` 双降级（35c 做）；segments 无索引/无服务端排序校验；`AsrClient` 不再走 Spring AI；ASR 分段未合并；ASR 超时未注入故障。
- T4.32b/T4.32a P3 残余、既有 T4.30/T4.29 残余同前（见 state.md）。
- MinIO 历史孤儿 74–79 发布前清理（可选）；`v1` 余量 0（D-032）。
