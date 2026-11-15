# 交接：录音纪要平台（更新 2026-11-15 13:55，给下一个会话 / 新会话 WorkBuddy）

## 1. 下一步

1. **W3 全 ✅**（T3.8 收口，D-089）；**W4：T4.1 ✅**（`690b644`，D-090：批量重试只复活 DEAD + 列表筛选 + 设置页 + F-15）、
   **T4.2 ✅**（`e9649d2`，D-091：E2E 5 场景纳入全量 209/209；修掉测试上下文抢真实队列，K-42）。
2. **下一张卡**：T4.4 零成本验证（N15，清 key → US-1 音频故事仍走通 → `ai_tier=EXTRACTIVE`），之后 T4.5 离线 / T4.6 接入面 / T4.7 批量 / T4.8 重启不丢；
   发布材料 T4.9/T4.10/T4.11 与真机 UI T4.13（**T4.13 时一并点验 `/tasks.html` 筛选与 `/settings.html`**——T4.1 唯一没验的面）。
3. 配额：2026-11-15 已用 **66/120 分钟、20/50 任务**（本地日切 00:00 重置）；新卡开工前先查当日已用。

## 2. 别踩的坑

1. 对外 JSON 一律 snake_case（D-041，全局 Jackson 已配）；业务码 7 + 通用码 5；`transcript:""`=无语音仍 DONE（D-048）；`todos` null vs `[]`（D-072）；`ai_tier` 诚实（D-020/D-075）。
2. **DONE 最后写**（D-069）；**三档级联**（D-075）；`OFFLINE_MODE=true` 云档硬门禁（D-077）；F-14 本地档 v1 不可用（D-089，别再花配额试）。
3. **F-15 时序**（D-090）：词表替换必须在 `TodoGrounding` **之后**（`right_text` 不在原文，先替换会被依据校验误杀）；只对 EXTRACTIVE 生效。
4. **批量重试语义**：只复活 DEAD，复活口径与"重传复活"逐字一致（`attempts=0` + 清死信 + 重入队），**不扣配额**；非 DEAD 进 `skipped` 不整体失败。
5. **不许改 `.env` 持久值做实验**：用外部 override（`D:\new\artifacts\*.yml`）+ `docker compose -f` 叠加，验完 `docker compose up -d` 恢复 + 校验 hash `2BCC440F1C9E`。
6. **配置/override 一律用文件工具写**，改后 `docker compose config --quiet` 预检 + `docker inspect` 核对容器 env；**别信 `/actuator/health` 的 200**（旧容器也会 200，K-41）。
7. **日志时效（K-39）**：每组实验一结束就 `docker compose logs app > D:\new\artifacts\logs-*.txt`，再切配置/重建容器。
8. **Docker 引擎（K-38）**：WorkBuddy 沙箱把 `wsl.exe` 列黑名单、自己起不了 Docker；由 DSH 侧启动，不动宿主安全设置（桌面 `AutoStart=false`）。
9. **测试入口** `scripts\test.ps1`（全量 **209/209**、`-UnitOnly` **202/202**；需 `powershell -ExecutionPolicy Bypass -File`；`mvn` 全路径 `E:\tools\apache-maven-3.9.9\bin\mvn.cmd`；PS5 无三元、`-Dtest=A,B` 要引号、改源码禁 BOM；**读文件一律 `-Encoding UTF8`**，否则中文会吞引号）。
10. **E2E（T4.2）**：`E2ETest` 打运行中的容器，每次跑新建 1 条 PASTE 任务（0 分钟）→ **配额紧时用 `-UnitOnly`**；栈不在必须红（不许 skip）；`E2E_BASE_URL` 可指向别的基址。
11. **测试别抢生产队列（K-42）**：`@SpringBootTest` 会装配真实 `TranscriptionWorker` 并 BLPOP 真实 Redis；需要全上下文的新测试必须 `@MockBean` 掉 worker 或用独立队列键，否则会偷走真实任务（假红/卡 PENDING）。
12. **psql 先 `SET TIME ZONE 'Asia/Shanghai'`**；API 前缀 **`/api/v1`**；`.env` 只写纯值（K-35）；证据行不删（D-073）；单写者（D-079）；数字复算（K-20/D-074）；**变异要双向**（K-40）；**PS 里 .NET 静态文件 API 的相对路径按进程 CWD 解析**（不是 `cd` 后的位置）——改文档用 edit 工具或绝对路径。

## 3. 关键文件

`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md` · `踩坑与复盘-面试用.md`（K-01~K-42）· `spec.md`/`plan.md`/`tasks.md`/`tasks-starter.md`（下一张卡 T4.4 待写）· `decisions.md`（**92 条**，最新 D-091）· `接入指南.md`（**已补 T4.1 三个新端点，并删掉两个不存在的端点**）· `review/2026-11-15-T4.2验收.md`（最新，26 份）· `配置清单.md` · `scripts/backup.ps1`/`restore.ps1` · `rules/dev.md`（§4.1）

## 4. 未决

T4.4~T4.13 全部 · E2E 不含音频端到端（T4.4/T4.5 补）· **浏览器真机渲染未验**（T4.13）· 并发重试非原子（已知限制）· "进行中"合并视图无游标续页（已知限制）· 默认 120s 读超时全程未跑 · amr-wb/手机私有变体未验 · 跨机器恢复/损坏备份/磁盘满未验 · **余量 0**（再加功能必须先砍一个）。

## 5. 存储与磁盘（2026-11-15 迁移，**后续开发沿用**）

- **已做（实测：5 服务 healthy、DB 64 任务、MinIO 导出与存档逐字节一致、mvn 209/209）**：
  1. Docker 数据 → **D:\DockerData\wsl**（26.76GB），原路径改 **junction**（Docker Desktop 设置未改）。
  2. `C:\Users\ljh\.cache\dsh-asr` → **D:\cache\dsh-asr** + junction；Maven 仓库 → **D:\cache\m2\repository**（`~/.m2/settings.xml`）。
  3. 清理：`docker builder prune -af`（8.9GB 可重建）、C 盘旧 pnpm/npm 缓存、TEMP。
  4. 用户环境变量：`TEMP/TMP=D:\cache\temp`、`PIP_CACHE_DIR`、`HF_HOME`、`GRADLE_USER_HOME`。
- **结果**：C 盘可用 **9.9GB → 41.45GB**；D 盘 347GB。
- **纪律**：样本/日志/override/临时/缓存一律 **D 盘**（`D:\new\artifacts\`、`D:\cache\`、`D:\DockerData\`）；不在 `C:\Users\ljh` 下新建大文件，必须用先报。
  仍占 C 的非开发项：Docker Desktop 程序本体（3.36GB）、Edge 缓存 ~1GB、用户 Videos/Downloads（未动）。
