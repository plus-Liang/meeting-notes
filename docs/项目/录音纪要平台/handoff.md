# 交接：录音纪要平台（更新 2026-11-18 17:35，给下一个会话 / 新会话 WorkBuddy）

## 0. 当前位置

- 产品提交 **`437018f`**（T4.37a-fix），工作区干净；**origin 已同步（含 T4.37a/T4.37a-fix 验收文档与 T4.37b 卡，与本地一致，待推 0）**；栈 5/5 healthy。**D-122 批次已通过 20 张**（T4.23…T4.30 含收尾、T4.32a、T4.32b、T4.35a、T4.35b、T4.35c、T4.36、**T4.37a（首验不通过 → 收尾后转通过）**）。
- 配额（11-18）：已用 **36 分钟 / 36 任务**（上限 200/100，余量足）。任务总数 **299**（全 DONE、DEAD 0、user_id 全 1）；MinIO **305 前缀 = 299 + 历史 6 孤儿（74–79）**；`app_user` **1 行占位 admin 未认领**（等 T4.37b 首次访问创建）。
- **下一张 = T4.37b 登录/多用户前端（卡已写在 `tasks-starter.md`，直接粘卡消息即可；必须新开 WorkBuddy 会话）**：`login.html`（登录 + 首次创建管理员/注册 + `?next=` 站内防开放重定向）+ `auth.js` 401→登录页（删 token 弹窗）+ index/tasks/task/settings 页头当前用户/退出 + `style.css`；**只前端零后端**。之后发布三件（T4.10 → T4.9 → T4.11）；不做 T4.31 PWA / T4.33 INBOX / T4.34 RSS。
- **会话策略（按用户要求区分）**：T4.36、T4.37a、T4.37a-fix 均按规则用同一会话；**T4.37b 必须新会话**（纯前端层，与后端账号大卡上下文不相关且旧会话已很长）。

## 1. 下一步（按顺序）

1. 用户把 **T4.37b 卡**粘进**新的 WorkBuddy 会话**；开工前先跑三行（`git log -1`、`git status --short`、`Get-Content D:\new\artifacts\T4.37a-fix\workbuddy\head.txt`）。
2. 回报回来我独立验收（重点：未登录跳转、首建管理员、退出后旧令牌失效、两账号隔离、防开放重定向、390、服务端静态 sha256==工作区）；通过后进入发布三件。
3. 发布三件前清 MinIO 孤儿 74–79。

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
11. **请求身份 ThreadLocal 只在请求线程有效**（K-90）：`StreamingResponseBody`/异步回调/worker 线程读 `UserContext` 会炸；请求线程先解析 userId 再显式传入，worker 链路按 `taskId` 查 `user_id`；未设置宁可抛异常，不回退默认用户；横切改动靠全量 E2E 兜底。
12. **唯一约束的并发路径必须能回契约错误码**（K-91）："先查重再插入"不是并发安全保证——并发同名注册撞唯一索引变 500；契约写 409 就要在写路径 catch `DuplicateKeyException`（只吞唯一键冲突）；验证要补并发探针。
13. **别直接读 MinIO 数据目录做字节比对**（K-87：`part.1` 是 bitrot 编码）。
14. **容器探针环境坑**（K-86）：slim 镜像（ASR）无 curl → python urllib 手拼 multipart；git-bash 的 MSYS 会转换 `docker exec` 里的 `/tmp`（`MSYS_NO_PATHCONV=1` 或 PowerShell）；探针文件 `docker cp` 进去、用完删；宿主机 8080 偶有 `wslrelay` 与 Docker 抢端口 → 验收优先容器内直连。
15. **容器到宿主的测试服务**：`host.docker.internal` 在容器里可能是 IPv6（K-85）→ 宿主服务双栈，Windows 上 `IPV6_V6ONLY=0` 必须在 `bind` 前设。
16. **外部工具/输出**：先判输出可解析再 parse（K-82）；重构命令调用别漏程序名（K-83）；子进程 stderr 排空 + 从进程启动起算的看门狗。
17. **前端写操作必须"点一下再读 API"**（K-80）；全量 `ASR_SMOKE_AUDIO` 用真实语音 `D:\datasets\meeting-notes-samples\probe.wav`。

## 3. 关键文件

`state.md`（权威状态）· `tasks.md`（T4.32a/b、T4.35a/b/c、T4.36、T4.37a、T4.37a-fix 已 ✅ 行；T4.37b 已出卡行）· `tasks-starter.md`（**T4.36 ✅、T4.37a ✅（首验不通过→修复）、T4.37a-fix ✅、T4.37b 卡已就绪**）· `decisions.md`（**149 条**，最新 D-149）· `踩坑与复盘-面试用.md`（**K-01~K-91**）· `review/`（**64 份**，最新 `2026-11-18-T4.37a-fix验收.md`）· `接入指南.md`（§3.9/§3.9a 链接导入、§3.10 音频流（`u=` 三段签名）、§3.11 说话人标注、**§3.12 账号 API**、§3.2 详情 **16 键**）· `配置清单.md`（**41 个 app.* 键**，新增 `AUTH_REGISTRATION_ENABLED`）· `rules/dev.md`（§4.1 第 11 条）· `scripts/test.ps1`（全量 483 / `-UnitOnly` 465）· `app/src/main/java/.../web/{AuthController,AuthService,PasswordHasher,TokenService,ApiTokenFilter}.java` + `common/UserContext.java`（T4.37a 新增/改造）· `app/src/main/resources/static/`（**T4.37b 要改：新增 `login.html`，改 `auth.js` 与 index/tasks/task/settings 的页头**）· `app/src/main/resources/db/migration/`（V1–V8，**V8=app_user**）· `tools/sync-docs-to-code.ps1`。

## 4. 未决

- **推送**：origin 与本地一致（含 T4.37a/T4.37a-fix 验收文档与 T4.37b 卡），待推 0。2026-11-18 凌晨 FlClash 的 HTTPS 通道曾故障约 2 小时（HTTP 200、HTTPS 全 000），已恢复；以后同类现象先按"代理 TLS 故障"排查，不要动本地提交。
- **T4.37a-fix 已通过（`437018f`）**：并发同名 500→409、认领原子化（并发认领恰 1 个 ADMIN）；P3 残余见 state.md。
- **T4.37a P3 残余（接受）**：无登录限流、令牌无 TTL、多设备单令牌互挤、注册开关关闭仅单测（运行态未验）、并发登录最后写赢、`UserContext` 对新增异步路径的隐性要求（K-90）、>4 路极端并发未压测、首个访客可认领管理员（部署后立即创建管理员）。
- **T4.36 P3 残余（接受）**：组序仅首现序不可拖拽、同名/别名不自动合并、菜单无 Escape/外点关闭、分组视图 `scrollIntoView nearest` 长列表需手动滚、缺 `speaker_map` 键视同清空（宽松）、键前导零归一化、自动说话人分离不做（先 spike 再定）、`speaker.prevMap` 无害死代码、缺 Content-Type 回 415（未写入 §3.11）。
- **T4.35c P3 残余（接受）**：滚动跟随增强、重渲染不保留滚动/高亮位置、无键盘快捷键、`share.html` 不加播放器、高亮精度 ≤250ms。
- **T4.35b P3 残余（接受）**：**音频 GET 已绑 user_id（T4.37a 收口，验签+归属双闸）**；PASTE 保护依赖签发闸；`HEAD /audio` 未实现、多段 Range 416；MinIO 416 兜底未注入故障；签名无吊销。
- T4.35a/T4.32b/T4.32a 残余同前（见 state.md）。
- MinIO 历史孤儿 74–79 发布前清理（可选）；`v1` 余量 0（D-032）。
