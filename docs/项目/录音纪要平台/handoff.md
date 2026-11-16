# 交接：录音纪要平台（更新 2026-11-16 21:40，给下一个会话 / 新会话 WorkBuddy）

## 0. 当前位置

- 代码 HEAD **`a1cc93f`**（T4.21b），工作区干净，本地 main ahead **11**，未 push；5 服务 healthy；ASR `small`；开发期配额 **200 分钟/100 任务**；今日已用 **160 分钟 / 48 任务**（剩 40 分钟 / 52 任务）；任务总数 128（全 DONE、DEAD=0、最大 id 272）。
- **T4.13 真机 ✅ → T4.20 ✅ → T4.19/18/17 ✅ → T4.21 ✅ → T4.21b ✅（第 44/45 份报告，D-117/D-118）** → **下一张 = 文档同步 + push** → 发布三件。**D-109/D-110 代码已全部落地，T4.21 系列已全部收口。**
- 用户当前可看的成果：列表能看**模板徽章/关键词 chips/摘要首句**，能**删单条/多选/全选/清空失败任务**（含 MinIO 对象清理，二次确认写明"不可恢复、配额不退还"），详情页底部可删任务；之前的要点/一句一行/导出勾选/medium 提示都在。

## 1. 下一步（按顺序）

1. **文档同步 + push（下一张）**：跑 `tools\sync-docs-to-code.ps1` 把权威文档同步进代码仓 → `git add docs/` 单 docs commit → `git push`（本地 ahead 11 → push 后 origin 追上）；确认 docs commit 的 `git diff --name-only` **只含 `docs/`**；push 后核对 `git status` 干净、远端 HEAD 一致。
2. **T4.13 新 UI 复看（用户本人点，0 配额，可选）**：列表三件套、删除入口、详情删除按钮、手机 390。
3. **T4.10 README + 录屏 → T4.9 开源发布最小集 → T4.11 简历/面试定稿**（README 必写：改默认 `API_TOKEN`、批量 ≤20/2GB、Webhook 仅全局 URL、离线模式、F-22 清 secret 连带历史行、TZ 老卷 `ALTER DATABASE`、content_hash 幂等复用、ASR 模型建议、**删除不可恢复/配额不退还**）。发布前顺手清 MinIO 历史孤儿对象 74–79。
4. 第 5 周链接导入 L1/L2/BYOF + 收尾；12-15 简历、12-16 交付；交付后按 T7.1→T7.15 做 v1.1/v1.2（D-103）。

## 2. 别踩的坑（最高频；全表见 `踩坑与复盘-面试用.md` K-01~K-74）

1. **DONE 最后写**（D-069）；`transcript:""`=无语音仍 DONE（D-048）；`todos/keypoints` null vs `[]`（D-072/D-111）；`transcript_punctuated` null=未生成（D-112）；`ai_tier` 诚实；三档级联 CLOUD→LOCAL→EXTRACTIVE。
2. **认证横切（K-46）**：`/api/v1/**` 全要 `Bearer <API_TOKEN>`；豁免=actuator/静态页/api-docs/swagger；空 token fail-closed。
3. **实验不改 `.env`**：外部 override + `docker compose -f` 叠加；改网络属性必须 `down` 再 `up`（K-44）；验完恢复并比 hash。
4. **配置生效验在消费点**（K-51）；别只看 health 200（K-41）；新增键同步 `.env.example` + `配置清单.md`。
5. **日志/输出落盘**（K-39/K-43）；exit code 用 `Start-Process -PassThru`（K-58）。
6. **收尾数字最后一步查**（K-45）；全量 E2E 每次 +2~4 任务、冒烟 +1。
7. **Lead 不写产品代码**（K-63）；验证前确认 `git status` 干净 + 服务端静态文件 sha256 == 工作区。
8. **改契约必须跑全量，且先重建容器再跑**（K-68）；回报里写"构建时间 < 全量日志时间"（T4.21 顺序不严的教训）。
9. **给 WorkBuddy 的卡别依赖 `cd`**（K-69）：写 `git -C <repo>` / `mvn -f <repo>\app\pom.xml`。
10. **宿主访问容器服务用 `127.0.0.1`**（K-70）；**手写 JSON POST 必须带 `Content-Type: application/json`**（K-73，否则 415）。
11. **删除功能验收别只看 DB**（K-72）：DB 0 行是必要不充分，MinIO 对象要单独 `ls`；三条路径已由 T4.21b 补齐清理口径（清空失败任务也清了）。
12. **重建/替换容器前先把旧日志落盘**（**K-74**）：`docker compose up -d` 重建容器后旧容器日志即丢失，删除类操作会"审计断链"；写码方/验收回报里要写清"删过哪些任务 id"。
13. **视觉验收"看+量"**（K-56）；半透明底先 alpha 合成（K-57）；改 token 先 grep 全部使用点（K-59）。
14. **上传幂等**：同字节重传 `reused:true` → A/B 必须换字节（改字节副本）。**PS 细节**：含中文 `.ps1` 必须 UTF-8 BOM（K-54）；避免 `Set-Content -Encoding utf8`；`D:\new` 不是 git 仓。
15. **证据字节保真（K-50）**：Windows 写盘 LF→CRLF，比较要归一化；PS 5.1 的 `Invoke-WebRequest.Content` 会按错误编码解码中文，比字节要用 `-OutFile` + `Get-FileHash`。
16. **psql 前 `SET TIME ZONE 'Asia/Shanghai'`**；证据行不删（D-073）；单写者（D-079）；变异双向（K-40）。

## 3. 关键文件

`state.md`（权威状态）· `tasks.md`（W4 表，T4.21/T4.21b ✅）· `tasks-starter.md`（T4.17–T4.21b 卡）· `decisions.md`（**118 条**，最新 D-118）· `踩坑与复盘-面试用.md`（**K-01~K-74**）· `review\`（**45 份**，最新 `2026-11-16-T4.21b验收.md`）· `接入指南.md`（§3.6a 删除三端点）· `配置清单.md` · `scripts\test.ps1`（全量 283 / `-UnitOnly` 279）· `tools\sync-docs-to-code.ps1`。

## 4. 未决

- T4.21b 过程发现：6 条旧 E2E 任务删除无日志可追溯（K-74，证据行 237/256 等全在，不影响验收）；MinIO 桶内 6 个**早于删除功能上线**的历史孤儿对象（74–79）待发布前清理（可选）。
- T4.21b 残余：`FOR UPDATE` 未做并发压测；S3 抖动只 WARN 不重试（expected）。
- T4.21 残余：无 controller 单测（T4.21b 已补 4 例）；短摘要也加省略号；批量 >100 分批只用 2 条验。
- T4.18 残余 P3：导出切句只按 `。！？!?`（页面还含 `；;`/换行）；只勾"要点"且无要点时导出空文件；标点失败真机回退未验。
- T4.17/T4.15 残余：LOCAL 档要点未真机抽、EXTRACTIVE 不产要点、改词回流后 keypoints 旧词不替换；待办删除无二次确认、依据整句匹配。
- 批量 413/429、Webhook SSRF、120s 读超时全链路、amr-wb 变体、跨机器恢复未验；**v1 余量 0**（D-032）。

## 5. 存储纪律（D-088）

样本/日志/override/缓存一律 **D 盘**（`D:\new\artifacts\`、`D:\cache\`、`D:\DockerData\`）；C 盘可用 ~41GB；模型在 `D:\huggingface`（medium 已补全；large-v3 仍是残片）。
