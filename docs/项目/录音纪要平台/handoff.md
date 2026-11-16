# 交接：录音纪要平台（更新 2026-11-16 22:45，给下一个会话 / 新会话 WorkBuddy）

## 0. 当前位置

- 代码 HEAD **`01f1b71`**（T4.23 摘要依据；前一张 `fda7df0` T4.24b），工作区干净；产品提交**已 push 到 origin/main**（docs 同步提交紧随其后）；5 服务 healthy；ASR `small`；开发期配额 200 分钟/100 任务；今日已用 **170 分钟 / 63 任务**（剩 30 分钟 / 37 任务）；任务总数 141（全 DONE、DEAD=0、最大 id 327）。
- 批次进度（D-122，16 项）：**T4.24 ✅（1/16）+ T4.24b ✅（收口）+ T4.23 ✅（2/16）**；下一张 **T4.23b 微修 + T4.25 统计摘要**。
- 用户当前可看的成果：上传页模板选择器；列表**服务端搜索**（中文/未加载行都能搜）+ **真计数徽章**；**详情页摘要依据块**（诚实显示"找到 X/Y 句"/"未找到直接依据"，命中可点定位高亮）；模板徽章/关键词 chips/摘要首句；删除三件套；要点/一句一行/导出勾选。

## 1. 下一步（按顺序）

1. **T4.23b 微修（5–10min，卡在 `tasks-starter.md`，T4.25 开工前置）**：`groundWindowBest` 空归一化守卫（`indexOf("")===0` 会让「。。」/「……」被判命中=假依据，K-78）；hit=false 写"未找到直接依据"；Playwright 注入探针 4 例（`'。。'`/`'……'`/`'   '`/正例）；单 commit `fix(T4.23)`；不 push。
2. **T4.25 统计摘要（~3–4h，新端点，改契约 → 先 commit→build/up→全量）**：`web/StatsController` + `task/StatsService`：`GET /api/v1/stats` → `{"tasks":{total,active,done,dead},"duration_sec_total","success_rate","avg_processing_ratio","tiers":{CLOUD,LOCAL,EXTRACTIVE}}`；`success_rate=done/(done+dead)`、`avg_processing_ratio=AVG((updated_at-created_at)/NULLIF(duration_sec,0))` 只统计 DONE；设置页「我的使用」卡；口径写注释+`接入指南.md`；`StatsServiceTest`；真机取数与 psql 复算一致。
3. **随后按 D-122（经 D-126 裁剪）队列**：T4.26 换模板 → T4.27 zip → T4.28 分享（V5）→ T4.29 标签（V6）→ T4.30 录音 → **T4.32b 链接导入（含最小必要 SSRF 安全底座，原 32a 并入）** → T4.35a/b/c 播放器（V7）→ T4.36 说话人 → T4.37 登录。**不做：T4.31 PWA / T4.33 INBOX / T4.34 RSS**（D-126，`app.inbox.*` 保持死配置）。做完前 3–4 项复核一次交付预期。
4. **发布材料（T4.10 README+录屏 → T4.9 → T4.11）在批次之后**；发布前清 MinIO 历史孤儿对象 74–79。

## 2. 别踩的坑（最高频；全表见 `踩坑与复盘-面试用.md` K-01~K-78）

1. **DONE 最后写**（D-069）；`transcript:""`=无语音仍 DONE（D-048）；`todos/keypoints` null vs `[]`（D-072/D-111）；`transcript_punctuated` null=未生成（D-112）；`ai_tier` 诚实；三档级联 CLOUD→LOCAL→EXTRACTIVE。
2. **认证横切（K-46）**：`/api/v1/**` 全要 `Bearer <API_TOKEN>`；豁免=actuator/静态页/api-docs/swagger；空 token fail-closed。
3. **实验不改 `.env`**：外部 override + `docker compose -f` 叠加；改网络属性必须 `down` 再 `up`（K-44）；验完恢复并比 hash。
4. **配置生效验在消费点**（K-51）；别只看 health 200（K-41）；新增键同步 `.env.example` + `配置清单.md`。
5. **日志/输出落盘**（K-39/K-43）；exit code 用 `Start-Process -PassThru`（K-58）。
6. **收尾数字最后一步查**（K-45）；全量 E2E 每轮固定 +3 任务（A 建 1 + G 批量 2）+ 冒烟 1。
7. **Lead 不写产品代码**（K-63）；验证前确认 `git status` 干净 + 服务端静态文件 sha256 == 工作区。
8. **先 commit → 再 `docker compose build app && up -d app` → 再全量**（K-68 + **K-77**）；回报写"head.txt = 提交后 HEAD"；Lead 发现顺序不对就自己重做 K-68。
9. **给 WorkBuddy 的卡别依赖 `cd`**（K-69）：写 `git -C <repo>` / `mvn -f <repo>\app\pom.xml`。
10. **宿主访问容器服务用 `127.0.0.1`**（K-70）；手写 JSON POST 带 `Content-Type: application/json`（K-73）。
11. **删除功能验收别只看 DB**（K-72）：DB 0 行是必要不充分，MinIO 对象要单独 `ls`。
12. **重建/替换容器前先把旧日志落盘**（K-74）：否则删除类操作审计断链。
13. **视觉验收"看+量"**（K-56）；半透明底先 alpha 合成（K-57）；改 token 先 grep 全部使用点（K-59）。
14. **上传幂等**：同字节重传 `reused:true` → A/B 必须换字节。**PS 细节**：含中文 `.ps1` 必须 UTF-8 BOM（K-54）；`D:\new` **不是** git 仓（只有代码仓是）。
15. **证据字节保真（K-50）**：Windows 写盘 LF→CRLF，比较要归一化；PS 5.1 的 `Invoke-WebRequest.Content` 会按错误编码解码中文，比字节用 `-OutFile` + `Get-FileHash`。
16. **字符串→数值分支测边界四件套**（K-76）；**相似度/包含判断先挡空串**（**K-78**：`indexOf("")===0` 会把空/纯标点判成"完全匹配"）；psql 前 `SET TIME ZONE 'Asia/Shanghai'`；证据行不删（D-073）；单写者（D-079）；变异双向（K-40）。

## 3. 关键文件

`state.md`（权威状态）· `tasks.md`（T4.23/T4.24/T4.24b ✅ + T4.23b/T4.25 待做）· `tasks-starter.md`（T4.21–T4.24 ✅ 卡 + **T4.24b/T4.23b 卡** + T4.25 卡）· `decisions.md`（**125 条**，最新 **D-124/D-125**）· `踩坑与复盘-面试用.md`（**K-01~K-78**）· `review\`（**48 份**，最新 `2026-11-16-T4.24b-T4.23验收.md`）· `接入指南.md`（§3.3 `q`+超 64 位口径 / §3.3a counts / §3.6a 删除三端点）· `配置清单.md` · `scripts\test.ps1`（全量 298 / `-UnitOnly` 287）· `tools\sync-docs-to-code.ps1`。

## 4. 未决

- **T4.23 残余**：真实数据命中率 0/5、0/3 是 CLOUD 改写型摘要的口径必然（D-125 维持 ≥0.5，不降阈）；命中→点击只在注入合成摘要上验过；长逐字稿性能 2000 句×2 句 ≈ 46.5ms、20 句最坏 ~0.2–0.5s 记录不修；空归一化误判 → **T4.23b**。
- **T4.24b 残余**：20+ 位前导零数字按关键词处理（与文档字面略有出入，安全，不修）。
- **审计断链**：T4.21b 少 6 条、T4.22 少 2 条旧 E2E 任务（旧容器日志随重建丢失，K-74；证据行全在）。
- MinIO 桶内 6 个**早于删除功能上线**的历史孤儿对象（74–79）待发布前清理（可选）。
- T4.22 残余：真实端到端模板消费未跑；拖拽路径未单独断言。T4.21b 残余：`FOR UPDATE` 未做并发压测。T4.18 残余 P3：导出切句只按 `。！？!?`；只勾"要点"且无要点时导出空文件。
- 批量 413/429、Webhook SSRF、120s 读超时全链路、amr-wb 变体、跨机器恢复未验；**D-126 裁剪：T4.31 PWA / T4.33 INBOX / T4.34 RSS 不做（`app.inbox.*` 死配置保留为已知限制），T4.32a 安全底座并入 T4.32b**；**v1 余量 0**（D-032）。

## 5. 存储纪律（D-088）

样本/日志/override/缓存一律 **D 盘**（`D:\new\artifacts\`、`D:\cache\`、`D:\DockerData\`）；C 盘可用 ~41GB；模型在 `D:\huggingface`（medium 已补全；large-v3 仍是残片）。
