# 交接：录音纪要平台（更新 2026-11-15 15:32，给下一个会话 / 新会话 WorkBuddy）

## 1. 下一步

1. **W3 全 ✅**（T3.8 收口，D-089）；**W4：T4.1 ✅**（`690b644`）、**T4.2 ✅**（`e9649d2`）、**T4.4 ✅**（无 commit，D-092）、**T4.5 ✅**（无 commit，D-093）、
   **T4.6 ✅**（`1c2aa73`，D-094：认证/OpenAPI/Webhook）、**T4.7 ✅**（`265b534`，D-095：批量提交）、**T4.8 ✅**（**零代码改动**，D-096：重启不丢；
   我另外自己 down/up 一次复现数据逐字节不丢，僵尸回收 task 202 实证，Webhook 重启续跑补上；新发现 **F-22**；验收后又发现 **F-23**：D-067 的 `TZ` 修正未真正生效（老卷 PG GUC 仍 UTC）→ 已 `ALTER DATABASE ... SET timezone` 修正，日切回到北京 00:00，T4.12 加预检）。
2. **下一张卡**：**T4.12 死配置体检**（卡已写进 `tasks-starter.md`：配置五方对齐 + **F-22 修复** + **F-21 启动预检** + **F-23 时区预检/新卷验证** + 3 个 BATCH_* 键 + 队列键评估），
   之后 T4.13 真机 UI（**T4.13 时一并点验 `/tasks.html` 筛选、`/settings.html`、浏览器粘 token、批量多选、Swagger Try-it-out**），再 T4.9/T4.10/T4.11 发布材料。
3. 配额：2026-11-15 已用 **80/120 分钟、47/50 任务**——**今日任务配额只剩 3**（本地日切 00:00 重置）；**全量 E2E 每跑一次吃 3 个任务**，
   T4.12 是代码卡必须跑全量 → **剩余 <3 就等日切**；配额紧时用 `-UnitOnly` + 现成证据行（K-36）；新卡开工前先查，**回报前最后一步再查一次**（K-45）。

## 2. 别踩的坑

1. 对外 JSON 一律 snake_case（D-041）；业务码 7 + 通用码 5；`transcript:""`=无语音仍 DONE（D-048）；`todos` null vs `[]`（D-072）；`ai_tier` 诚实（D-020/D-075）。
2. **DONE 最后写**（D-069）；**三档级联**（D-075）；`OFFLINE_MODE=true` 云档硬门禁（D-077）；F-14 本地档 v1 不可用（D-089，别再花配额试）。
3. **F-15 时序**（D-090）：词表替换必须在 `TodoGrounding` **之后**（`right_text` 不在原文，先替换会被依据校验误杀）；只对 EXTRACTIVE 生效。
4. **批量重试语义**：只复活 DEAD，复活口径与"重传复活"逐字一致（`attempts=0` + 清死信 + 重入队），**不扣配额**；非 DEAD 进 `skipped` 不整体失败。
5. **认证（T4.6 / K-46）**：`/api/v1/**` 全部要 `Authorization: Bearer <API_TOKEN>`；**豁免面** = actuator/静态页/`/v3/api-docs`/`/swagger-ui`。
   所有 curl、E2E（`E2E_API_TOKEN`）、浏览器（`auth.js` 会 prompt 粘一次）都要带 token；`API_TOKEN` 空串时拒绝所有（fail-closed）。
   **做横切改动（认证/序列化/错误码）先列全调用方清单**，收尾必须跑一次全量（K-46）。
6. **不许改 `.env` 持久值做实验**：用外部 override（`D:\new\artifacts\*.yml`）+ `docker compose -f` 叠加，验完恢复基础 compose + 校验 hash `2BCC440F1C9E`。
7. **配置/override 一律用文件工具写**，改后 `docker compose config --quiet` 预检 + `docker inspect` 核对容器 env；**别信 `/actuator/health` 的 200**（K-41）。
8. **日志时效（K-39）+ 命令输出落盘（K-43）**：每组实验一结束就 `docker compose logs app > D:\new\artifacts\logs-*.txt`；mvn/脚本输出也重定向落盘并记 exit code。
9. **Docker 引擎（K-38）**：WorkBuddy 沙箱把 `wsl.exe` 列黑名单、自己起不了 Docker；由 DSH 侧启动，不动宿主安全设置（桌面 `AutoStart=false`）。
10. **断网实验（K-44）**：改 `networks.default.internal` 必须 **先 `down`（不加 `-v`）再 `up -d`**；内网下宿主 8080=000，API 只能容器内 `docker compose exec -T app curl`；
    `enable_ip_masquerade=false` 不能断外网；`OFFLINE_MODE=true` 时云档失败会**先按 Spring AI 重试再级联降级**（约 2s，任务仍 DONE）。
11. **收尾数字最后一步查（K-45）**：全量测试含 E2E 会新增 1 条 PASTE 任务——配额/任务数必须在所有动作做完之后查（T4.4 与 T4.5 连续两次吃亏）。
12. **签名类证据验收方独立重算（K-47）**：Webhook 签名/哈希的"接收端 True/False"是自证；验收时要取原始字节用独立工具重算，并做"错密钥必须不等"的反向验证。
13. **测试入口** `scripts\test.ps1`（全量 **227/227**（E2E 8）、`-UnitOnly` **217/217**；需 `powershell -ExecutionPolicy Bypass -File`；`mvn` 全路径 `E:\tools\apache-maven-3.9.9\bin\mvn.cmd`；PS5 无三元、`-Dtest=A,B` 要引号、改源码禁 BOM；**读文件一律 `-Encoding UTF8`**；全量会自动注入 `E2E_API_TOKEN`）。
14. **E2E（T4.2/T4.6/T4.7）**：`E2ETest` 打运行中的容器，每次全量跑新建 3 个任务（A PASTE + G 2 个静音 wav）→ **配额紧时用 `-UnitOnly`**；栈不在必须红（不许 skip）；
    内网（T4.5）下宿主到 8080 不通，E2E 跑不了，属环境约束。
15. **测试别抢生产队列（K-42）**：`@SpringBootTest` 会装配真实 `TranscriptionWorker` 并 BLPOP 真实 Redis；需要全上下文的新测试必须 `@MockBean` 掉 worker 或用独立队列键。
16. **批量契约（T4.7 / K-48 / D-095）**：`POST /api/v1/tasks/batch` 请求合法即 **200，但 200 不代表全部成功**——逐项看 `items[].code`；
    `summary.accepted` **含 reused**、幂等项**不重复扣配额**、数量 >20 → 400、请求总体积 >2GB → 413；`estimated_sec` 只是预估。
17. **psql 先 `SET TIME ZONE 'Asia/Shanghai'`**；API 前缀 **`/api/v1`**；`.env` 只写纯值（K-35）；证据行不删（D-073）；单写者（D-079）；数字复算（K-20/D-074）；**变异要双向**（K-40）；**PS 里 .NET 静态文件 API 的相对路径按进程 CWD 解析**——改文档用 edit 工具或绝对路径。
18. **异步调度器必须重放"入队前置条件"（K-49）**：**F-22** = `WebhookDeliveryService.onTerminal` 挡了空 secret，但 `scanDue` 没挡 → 空 `WEBHOOK_SECRET` 时遗留/手工到期行被捡起，
    空密钥签名抛异常、按 1s/10s/60s **空烧 4 次**（attempts=4/last_status=0），日志 `HMAC-SHA256 不可用` 是**误导**（真因配置缺失）。不扣配额、不影响任务。**T4.12 修**；
    自建者清 secret 前先 `UPDATE webhook_delivery SET next_retry=NULL WHERE next_retry IS NOT NULL;`。**报错文案必须区分"配置缺失"与"组件坏了"**。
19. **证据载体自己会骗人（K-50）**：接收端字段名 `body_b64` 里存的其实是 UTF-8 文本（不是 base64）；快照文件用 `Set-Content` 再算 sha256 会因 BOM/尾换行得出不同值。
    **二进制一律 base64、字段名如实、哈希必须写清算的是哪层字节**；验签要"命中 + 错密钥不等"双向（K-47）。
20. **配置生效要验证在消费点（K-51 / F-23）**：D-067 设了 `TZ=Asia/Shanghai`、容器 `date` 也显示 CST，但 PG 老数据卷 `postgresql.conf` 写死 UTC → `SHOW timezone` 还是 UTC，配额实际在北京 08:00 才重置。
    判据：`SHOW timezone` + `CURRENT_DATE = (now() AT TIME ZONE 'Asia/Shanghai')::date`；老卷改法 `ALTER DATABASE ... SET timezone ...`（T4.12 加启动预检）。**别只看你设置它的地方，要看谁在消费它。**

## 3. 关键文件

`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md` · `踩坑与复盘-面试用.md`（K-01~K-51）· `spec.md`/`plan.md`/`tasks.md`/`tasks-starter.md`（下一张卡 **T4.12** 已写）· `decisions.md`（**98 条**，最新 D-097）· `接入指南.md`（认证/Webhook/batch 契约均已回填实测事实：§3.7 批量、§4.2/4.4 Webhook（含 **F-22 运维注意**）、§六 三条验收 ✅）· `配置清单.md`（§1.1 三个 BATCH_* 键、§1.4 F-21/F-22、§1.5 接入键已消费）· `review/2026-11-15-T4.8验收.md`（最新，31 份）· `scripts/backup.ps1`/`restore.ps1` · `rules/dev.md`（§4.1）

## 4. 未决

T4.12/T4.13/T4.9-11 · **今日任务配额只剩 3**（全量 E2E 一次吃 3，T4.12 建议日切后开工）· **浏览器真机渲染未验**（T4.13，含粘 token、批量多选、Swagger Try-it-out）·
**F-21 矛盾配置启动崩**（T4.12 改启动预检）· **F-22 空 secret 遗留行空烧重试**（T4.12 修）· **F-17 换模板重跑未做** · EXTRACTIVE 质量线 ·
**批量：413 2GB 未实测 / 批量内 429 未实测 / 批量字幕仅单测 / 预估系数未校准** · **Webhook v1：无单任务 callback_url / 无手动重投 / URL 无 SSRF 防护**（重启后续跑已由 T4.8 补验） · `bearer` 小写被拒 ·
**僵尸回收只测单任务/单副本**（多任务并发、结构化阶段中断未覆盖）· 并发重试非原子 · "进行中"合并视图无游标续页 · 默认 120s 读超时全程未跑 ·
amr-wb/手机私有变体未验 · 跨机器恢复/损坏备份/磁盘满未验 · **余量 0**（再加功能必须先砍一个）。

## 5. 存储与磁盘（2026-11-15 迁移，**后续开发沿用**）

- **已做（实测：5 服务 healthy、DB 64 任务、MinIO 导出与存档逐字节一致、mvn 209/209）**：
  1. Docker 数据 → **D:\DockerData\wsl**（26.76GB），原路径改 **junction**（Docker Desktop 设置未改）。
  2. `C:\Users\ljh\.cache\dsh-asr` → **D:\cache\dsh-asr** + junction；Maven 仓库 → **D:\cache\m2\repository**（`~/.m2/settings.xml`）。
  3. 清理：`docker builder prune -af`（8.9GB 可重建）、C 盘旧 pnpm/npm 缓存、TEMP。
  4. 用户环境变量：`TEMP/TMP=D:\cache\temp`、`PIP_CACHE_DIR`、`HF_HOME`、`GRADLE_USER_HOME`。
- **结果**：C 盘可用 **9.9GB → 41.45GB**；D 盘 347GB。
- **纪律**：样本/日志/override/临时/缓存一律 **D 盘**（`D:\new\artifacts\`、`D:\cache\`、`D:\DockerData\`）；不在 `C:\Users\ljh` 下新建大文件，必须用先报。
  仍占 C 的非开发项：Docker Desktop 程序本体（3.36GB）、Edge 缓存 ~1GB、用户 Videos/Downloads（未动）。
