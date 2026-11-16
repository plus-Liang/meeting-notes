# 交接：录音纪要平台（更新 2026-11-16 22:25，给下一个会话 / 新会话 WorkBuddy）

## 0. 当前位置

- 代码 HEAD **`7a4fc30`**（T4.24 服务端搜索+真计数，D-123 通过），工作区干净；**origin/main = `1b5cba0`**，本地 `main` **ahead 1**（T4.24 待 push）；5 服务 healthy；ASR `small`；开发期配额 200 分钟/100 任务；今日已用 **166 分钟 / 57 任务**（剩 34 分钟 / 43 任务）；任务总数 135（全 DONE、DEAD=0、最大 id 315）。
- 主线：T4.13 真机 ✅ → T4.20 ✅ → T4.21 ✅ → T4.21b ✅ → T4.22 ✅（D-120）→ **D-122：第 1 档 6 项 + 第 2 档 8 项 + 摘要依据全部排期 T4.23–T4.37（持久目标）** → **T4.24 ✅（1/16，D-123）** → 下一张 **T4.24b 小修 + T4.23 摘要依据**。
- 用户当前可看的成果：上传页可选 5 套摘要模板；列表**服务端搜索**（能搜到没加载过的任务、中文文件名也行）+ **真计数徽章**（不再 200 上限）；模板徽章/关键词 chips/摘要首句；删单条/多选/全选/清空失败任务（含 MinIO 清理）；详情页删除、要点、一句一行、导出勾选。

## 1. 下一步（按顺序）

1. **T4.24b 收尾小修（下卡前置，20–30min，卡在 `tasks-starter.md`）**：`q` 超长纯数字（>Long.MAX）→ 500 改安全解析（超范围退化为普通关键词 ILIKE）；搜索框 `maxlength=100`；`接入指南.md` §3.3 补一句；单测 2 例 + E2E 1 断言；**commit → build/up → 全量**（K-68/K-77）；单 commit `fix(T4.24)`。
2. **T4.23 摘要级依据（~1–2h，纯 static，0 配额，D-121/D-104）**：摘要逐句 ↔ 原文 2–3 句窗口匹配（二元组覆盖率 ≥0.5），命中行可点定位高亮、未命中写"未找到直接依据"，文案固定"在原文中匹配到的句子"；只读 237/256，不新建任务。
3. **随后按 D-122 队列**：T4.25 stats → T4.26 换模板 → T4.27 zip → T4.28 分享（V5）→ T4.29 标签（V6）→ T4.30 录音 → T4.31 PWA → T4.32a FETCH/SSRF → T4.32b 链接导入 → T4.33 INBOX → T4.34 RSS → T4.35a/b/c 播放器（V7）→ T4.36 说话人 → T4.37 登录；做完前 3–4 项复核一次交付预期。
4. **发布材料（T4.10 README+录屏 → T4.9 → T4.11）在批次之后**；发布前清 MinIO 历史孤儿对象 74–79。

## 2. 别踩的坑（最高频；全表见 `踩坑与复盘-面试用.md` K-01~K-77）

1. **DONE 最后写**（D-069）；`transcript:""`=无语音仍 DONE（D-048）；`todos/keypoints` null vs `[]`（D-072/D-111）；`transcript_punctuated` null=未生成（D-112）；`ai_tier` 诚实；三档级联 CLOUD→LOCAL→EXTRACTIVE。
2. **认证横切（K-46）**：`/api/v1/**` 全要 `Bearer <API_TOKEN>`；豁免=actuator/静态页/api-docs/swagger；空 token fail-closed。T4.24 的 `/tasks/counts` 同样要 token（已验证 401）。
3. **实验不改 `.env`**：外部 override + `docker compose -f` 叠加；改网络属性必须 `down` 再 `up`（K-44）；验完恢复并比 hash。
4. **配置生效验在消费点**（K-51）；别只看 health 200（K-41）；新增键同步 `.env.example` + `配置清单.md`。
5. **日志/输出落盘**（K-39/K-43）；exit code 用 `Start-Process -PassThru`（K-58）。
6. **收尾数字最后一步查**（K-45）；全量 E2E 每次会建真实测试任务、冒烟 +1（今日 48→57 即两次全量）。
7. **Lead 不写产品代码**（K-63）；验证前确认 `git status` 干净 + 服务端静态文件 sha256 == 工作区。
8. **先 commit → 再 `docker compose build app && up -d app` → 再全量**（K-68 + **K-77**）；回报写"head.txt = 提交后 HEAD"；Lead 发现顺序不对就自己重做 K-68。
9. **给 WorkBuddy 的卡别依赖 `cd`**（K-69）：写 `git -C <repo>` / `mvn -f <repo>\app\pom.xml`。
10. **宿主访问容器服务用 `127.0.0.1`**（K-70）；**手写 JSON POST 必须带 `Content-Type: application/json`**（K-73）。
11. **删除功能验收别只看 DB**（K-72）：DB 0 行是必要不充分，MinIO 对象要单独 `ls`。
12. **重建/替换容器前先把旧日志落盘**（K-74）：否则删除类操作审计断链。
13. **视觉验收"看+量"**（K-56）；半透明底先 alpha 合成（K-57）；改 token 先 grep 全部使用点（K-59）。
14. **上传幂等**：同字节重传 `reused:true` → A/B 必须换字节。**PS 细节**：含中文 `.ps1` 必须 UTF-8 BOM（K-54）；`D:\new` **不是** git 仓（只有代码仓是）。
15. **证据字节保真（K-50）**：Windows 写盘 LF→CRLF，比较要归一化；PS 5.1 的 `Invoke-WebRequest.Content` 会按错误编码解码中文，比字节要用 `-OutFile` + `Get-FileHash`。
16. **字符串→数值的分支要测边界四件套**（**K-76**：`0`/正常/`MAX`/`MAX+1`，本次 `q` 20 位数字 500 就是漏了它）；psql 前 `SET TIME ZONE 'Asia/Shanghai'`；证据行不删（D-073）；单写者（D-079）；变异双向（K-40）。

## 3. 关键文件

`state.md`（权威状态）· `tasks.md`（T4.24 ✅ + T4.23/T4.25 待做 + T7.16）· `tasks-starter.md`（T4.21–T4.24 ✅ 卡 + **T4.24b 新卡** + T4.25 卡）· `decisions.md`（**123 条**，最新 **D-123**）· `踩坑与复盘-面试用.md`（**K-01~K-77**）· `review\`（**47 份**，最新 `2026-11-16-T4.24验收.md`）· `接入指南.md`（§3.3 `q` / §3.3a counts / §3.6a 删除三端点）· `配置清单.md` · `scripts\test.ps1`（全量 296 / `-UnitOnly` 285）· `tools\sync-docs-to-code.ps1`。

## 4. 未决

- **T4.24 残余**：`q` 超长纯数字 500（**T4.24b 前置必修**）；搜索框无 maxlength → 101 字输入走误导性 `renderFallback`（同修）；无响应序号守卫（P3 记录）；counts 与列表两次请求的瞬时不一致窗口（P3）；counts 无 >200 行实证（代码层 `GROUP BY` 无 LIMIT）；30s 计数刷新未等满周期；ACTIVE 非空 + 搜索未验；`ILIKE '%q%'` 无索引（上万行再上 `pg_trgm`）。
- **审计断链**：T4.21b 少 6 条、T4.22 少 2 条旧 E2E 任务（旧容器日志随重建丢失，K-74；证据行全在，不影响验收）。
- MinIO 桶内 6 个**早于删除功能上线**的历史孤儿对象（74–79）待发布前清理（可选）。
- T4.22 残余：真实端到端模板消费未跑；拖拽路径未单独断言。T4.21b 残余：`FOR UPDATE` 未做并发压测；S3 抖动只 WARN 不重试。
- T4.18 残余 P3：导出切句只按 `。！？!?`；只勾"要点"且无要点时导出空文件；标点失败真机回退未验。
- 批量 413/429、Webhook SSRF、120s 读超时全链路、amr-wb 变体、跨机器恢复未验；**v1 余量 0**（D-032）。

## 5. 存储纪律（D-088）

样本/日志/override/缓存一律 **D 盘**（`D:\new\artifacts\`、`D:\cache\`、`D:\DockerData\`）；C 盘可用 ~41GB；模型在 `D:\huggingface`（medium 已补全；large-v3 仍是残片）。
