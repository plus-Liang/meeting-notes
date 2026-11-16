# 交接：录音纪要平台（更新 2026-11-16 23:35，给下一个会话 / 新会话 WorkBuddy）

## 0. 当前位置

- 代码 HEAD **`51760d8`**（docs；产品提交 `70c5eeb` T4.25 / `cbcf00c` T4.23b），工作区干净；产品与 docs **均已 push**（origin/main = `51760d8`）；5 服务 healthy；ASR `small`；开发期配额 200 分钟/100 任务；今日已用 **174 分钟 / 69 任务**（剩 26 分钟 / 31 任务）；任务总数 147（全 DONE、DEAD=0、最大 id 348）。
- 批次进度（D-122，经 D-126/D-127 裁剪）：**T4.23 ✅ / T4.23b ✅ / T4.24 ✅ / T4.24b ✅ / T4.25 ✅（5 张通过）**；不做 T4.31 PWA / T4.33 INBOX / T4.34 RSS；**下一张 T4.26 换模板重跑**。**节奏复核（D-130）**：实测单卡"commit→验收报告"6–15 分钟，剩余 12 张估 3–5 个有效工作日 → **12-16 交付窗口可行、不再裁范围**；下一复核点 = T4.28 后。
- 用户当前可看的成果：上传页模板选择器；列表服务端搜索（中文/未加载行）+ 真计数徽章；详情页摘要依据块（诚实 0/5、0/3，命中可点定位）；**设置页「我的使用」卡**（总任务/总时长/成功率/平均处理倍速〔含排队，非 RTF〕/档位分布）；模板徽章/chips/摘要首句；删除三件套；要点/一句一行/导出勾选。

## 1. 下一步（按顺序）

1. **T4.26 换模板重跑 F-17（~2–3h，改契约）**：`POST /api/v1/tasks/{id}/restructure` body `{"template":"course"}`——`TemplateResolver.validateExplicit`（非法 400）、任务不存在 404、非 DONE 409 `TASK_BUSY`、transcript 空 400；**只复用已存 transcript 重跑结构化，不重跑 ASR、不新建任务、不扣任务配额**；成功后 `task_result.template` 一并落库，**失败保留旧结果+旧模板**；并发用行锁/Redis 锁（第二个 409）；详情页「按模板重新生成」下拉 + 二次确认（提示覆盖摘要/关键词/待办/要点、不耗配额）；单测 + 真机 1 条 DONE（237/256 之一，0 新任务，配额不变）；**先 commit→build/up→全量**（K-68/K-77）。
2. **随后保留队列**：T4.27 zip 导出 → T4.28 只读分享（V5）→ T4.29 标签/收藏（V6）→ T4.30 浏览器录音 → **T4.32a FETCH+SSRF（单列卡）→ T4.32b 链接导入** → T4.35a/b/c 播放器（V7）→ T4.36 说话人 → T4.37 登录。
3. **发布材料（T4.10 README+录屏 → T4.9 → T4.11）在批次之后**；发布前清 MinIO 历史孤儿对象 74–79。

## 2. 别踩的坑（最高频；全表见 `踩坑与复盘-面试用.md` K-01~K-78）

1. **DONE 最后写**（D-069）；`transcript:""`=无语音仍 DONE（D-048）；`todos/keypoints` null vs `[]`（D-072/D-111）；`transcript_punctuated` null=未生成（D-112）；`ai_tier` 诚实；三档级联 CLOUD→LOCAL→EXTRACTIVE。
2. **认证横切（K-46）**：`/api/v1/**` 全要 `Bearer <API_TOKEN>`；豁免=actuator/静态页/api-docs/swagger；空 token fail-closed。新增 `/stats`、T4.26 的 `/restructure` 同样要 token。
3. **实验不改 `.env`**：外部 override + `docker compose -f` 叠加；改网络属性必须 `down` 再 `up`（K-44）；验完恢复并比 hash。
4. **配置生效验在消费点**（K-51）；别只看 health 200（K-41）；新增键同步 `.env.example` + `配置清单.md`。
5. **日志/输出落盘**（K-39/K-43）；exit code 用 `Start-Process -PassThru`（K-58）。
6. **收尾数字最后一步查**（K-45）；全量 E2E 每轮固定 +3 任务（A 1 + G 2）+ 冒烟 1。
7. **Lead 不写产品代码**（K-63）；验证前确认 `git status` 干净 + 服务端静态文件 sha256 == 工作区。
8. **先 commit → 再 `docker compose build app && up -d app` → 再全量**（K-68 + **K-77**）；回报写"head.txt = 提交后 HEAD"；Lead 发现顺序不对就自己重做 K-68。
9. **给 WorkBuddy 的卡别依赖 `cd`**（K-69）：写 `git -C <repo>` / `mvn -f <repo>\app\pom.xml`。
10. **宿主访问容器服务用 `127.0.0.1`**（K-70）；手写 JSON POST 带 `Content-Type: application/json`（K-73）。
11. **删除功能验收别只看 DB**（K-72）；重建/替换容器前先把旧日志落盘（K-74）。
12. **视觉验收"看+量"**（K-56）；改 token 先 grep 全部使用点（K-59）。
13. **上传幂等**：同字节重传 `reused:true` → A/B 必须换字节。**PS 细节**：含中文 `.ps1` 必须 UTF-8 BOM（K-54）；`D:\new` **不是** git 仓（只有代码仓是）。
14. **证据字节保真（K-50）**：Windows 写盘 LF→CRLF，比较要归一化；PS 5.1 的 `Invoke-WebRequest.Content` 会按错误编码解码中文，比字节用 `-OutFile` + `Get-FileHash`。
15. **字符串→数值分支测边界四件套**（K-76）；**相似度/包含判断先挡空串**（**K-78**：`indexOf("")===0` 会把空/纯标点判成"完全匹配"）；psql 前 `SET TIME ZONE 'Asia/Shanghai'`；证据行不删（D-073）；单写者（D-079）；变异双向（K-40）。

## 3. 关键文件

`state.md`（权威状态）· `tasks.md`（T4.23/T4.23b/T4.24/T4.24b/T4.25 ✅ + T4.26–T4.29 待做）· `tasks-starter.md`（T4.21–T4.26 卡 + T4.24b/T4.23b 卡；提前功能批次表已按 D-126/D-127 更新）· `decisions.md`（**129 条**，最新 **D-128/D-129**，含 D-126/D-127 范围修正）· `踩坑与复盘-面试用.md`（**K-01~K-78**）· `review\`（**49 份**，最新 `2026-11-16-T4.23b-T4.25验收.md`）· `接入指南.md`（§3.3 `q` / §3.3a counts / **§3.3b stats** / §3.6a 删除三端点）· `配置清单.md` · `scripts\test.ps1`（全量 302 / `-UnitOnly` 290）· `tools\sync-docs-to-code.ps1`。

## 4. 未决

- **T4.25 残余**：空库 null 语义仅单测覆盖（真机单用户有数据，无法造空库）；`success_rate` 当前 1.0（库里 0 DEAD）；`tiers` 新档位扩展未造数验证；设置页 stats 500 失败分支未注入故障；`avg_processing_ratio` 含排队已在界面/文档双声明（不是 RTF）。
- **T4.23 残余**：真实数据命中率 0/5、0/3 是 CLOUD 改写型摘要的口径必然（D-125 维持 ≥0.5，不降阈）；命中→点击只在注入合成摘要上验过；长逐字稿性能 2000 句×2 句 ≈ 46.5ms（不设门槛）。
- **T4.24b 残余**：20+ 位前导零数字按关键词处理（与文档字面略有出入，安全，不修）。
- **审计断链**：T4.21b 少 6 条、T4.22 少 2 条旧 E2E 任务（旧容器日志随重建丢失，K-74；证据行全在）。
- MinIO 桶内 6 个**早于删除功能上线**的历史孤儿对象（74–79）待发布前清理（可选）。
- T4.22 残余：真实端到端模板消费未跑；拖拽路径未单独断言。T4.21b 残余：`FOR UPDATE` 未做并发压测。T4.18 残余 P3：导出切句只按 `。！？!?`；只勾"要点"且无要点时导出空文件。
- 批量 413/429、Webhook SSRF、120s 读超时全链路、amr-wb 变体、跨机器恢复未验；**D-126/D-127 裁剪：T4.31 PWA / T4.33 INBOX / T4.34 RSS 不做（`app.inbox.*` 死配置保留为已知限制），T4.32a 保留单列卡**；**v1 余量 0**（D-032）。

## 5. 存储纪律（D-088）

样本/日志/override/缓存一律 **D 盘**（`D:\new\artifacts\`、`D:\cache\`、`D:\DockerData\`）；C 盘可用 ~41GB；模型在 `D:\huggingface`（medium 已补全；large-v3 仍是残片）。
