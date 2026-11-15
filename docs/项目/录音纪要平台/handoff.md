# 交接：录音纪要平台（更新 2026-11-15 12:55，给下一个会话 / 新会话 WorkBuddy）

## 1. 下一步

1. **W3 已全部收口**：T3.8 验收通过（`3b80f02`，D-089），24 份验收报告，192/192 绿；代码+docs 已推送。
2. **第 4 周 T4.x**：先写首卡（建议 **T4.1** 列表筛选/批量重试/设置页，**并入已批准的 F-15**：EXTRACTIVE 读词表，≤30 行 + 单测 + 不改公共面），
   再 T4.2/4.4/4.5/4.6 三类场景验证 + 认证 T4.6 + 交付材料；第 5 周链接导入 + 简历/面试（12-15 简历、12-16 交付）。
3. 配额：2026-11-15 已用 **65/120 分钟、10/50 任务**（17 日 00:00 本地日切后重置）；新卡开工前先查当日已用。

## 2. 别踩的坑

1. 对外 JSON 一律 snake_case（D-041）；业务码 7 + 通用码 5；`transcript:""`=无语音仍 DONE（D-048）；`todos` null vs `[]`（D-072）；`ai_tier` 诚实（D-020/D-075）。
2. **DONE 最后写**（D-069）；**三档是级联**（D-075）；`OFFLINE_MODE=true` 云档硬门禁（D-077）；本地档预算独立（D-081）。
3. **F-14 已定案为 v1 已知限制**（D-089）：1.5B 模型 512 截断/1024 畸形/2048 再截断 → 不要再花配额试"让 LOCAL 成功"，除非换模型（v2）。
4. **不许改 `.env` 持久值做实验**：用外部 override（`D:\new\artifacts\*.yml`）+ `docker compose -f` 临时叠加，验完 `docker compose up -d` 恢复基础栈 + 校验 hash `2BCC440F1C9E`。
5. **日志时效（K-39）**：每组实验**一结束**就 `docker compose logs app > D:\new\artifacts\logs-*.txt`——写码方本轮 F-14/超时日志就是重建容器时丢的。
6. **Docker 引擎（K-38）**：WorkBuddy 沙箱把 `wsl.exe` 列入黑名单、自己起不了 Docker；**由 DSH 侧启动 Docker Desktop**，不动宿主安全设置（桌面 `AutoStart=false`，睡眠后可能掉线）。
7. **模板（T3.9）**：`task_result.template` 提交事务内先写；worker/结构化只更新各自列不得覆盖；详情 12 键、`template` 末尾；幂等键不含 template（F-17 → T4.1）。
8. **行数 300/文件**：controller 248、StructuringService 280、worker 286、TaskService 267；allowlist 只能 0 条。
9. **测试入口** `scripts\test.ps1`（现 **192/192**，需 `powershell -ExecutionPolicy Bypass -File`；`mvn` 全路径 `E:\tools\apache-maven-3.9.9\bin\mvn.cmd`；PS5 无三元、`-Dtest=A,B` 要引号、改源码禁 BOM）。
10. **psql 先 `SET TIME ZONE 'Asia/Shanghai'`**（默认 UTC 显示前一天）；API 前缀是 **`/api/v1`**（我误用 `/tasks` 拿过一次 404）；`.env` 只写纯值（K-35）；证据行不删（D-073）；单写者（D-079）；数字复算（K-20/D-074）；**变异要双向**（K-40）。

## 3. 关键文件

`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md` · `踩坑与复盘-面试用.md`（K-01~K-40）· `spec.md`/`plan.md`/`tasks.md`/`tasks-starter.md`（下一张卡待写）· `decisions.md`（**90 条**，最新 D-089）· `接入指南.md` · `review/2026-11-15-T3.8验收.md`（最新，24 份）· `配置清单.md` · `scripts/backup.ps1`/`restore.ps1` · `rules/dev.md`（§4.1）

## 4. 未决

**F-17** 幂等 vs 重跑（T4.1）· **F-15** 抽取式读词表（T4.x，已批）· 认证 T4.6 · 真机 UI T4.13 · 默认 120s 读超时全程未跑 · amr-wb/手机私有变体未验 · 跨机器恢复/损坏备份/磁盘满未验 · **余量 0**（再加功能必须先砍一个）。

## 5. 存储与磁盘（2026-11-15 迁移，**后续开发沿用**）

- **已做（实测：5 服务 healthy、DB 54 任务、MinIO 导出与存档逐字节一致、mvn 192/192）**：
  1. Docker 数据 → **D:\DockerData\wsl**（26.76GB），原路径改 **junction**（Docker Desktop 设置未改）。
  2. `C:\Users\ljh\.cache\dsh-asr` → **D:\cache\dsh-asr** + junction；Maven 仓库 → **D:\cache\m2\repository**（`~/.m2/settings.xml`）。
  3. 清理：`docker builder prune -af`（8.9GB 可重建）、C 盘旧 pnpm/npm 缓存、TEMP。
  4. 用户环境变量：`TEMP/TMP=D:\cache\temp`、`PIP_CACHE_DIR`、`HF_HOME`、`GRADLE_USER_HOME`。
- **结果**：C 盘可用 **9.9GB → 41.45GB**；D 盘 347GB。
- **纪律**：样本/日志/override/临时/缓存一律 **D 盘**（`D:\new\artifacts\`、`D:\cache\`、`D:\DockerData\`）；不在 `C:\Users\ljh` 下新建大文件，必须用先报。
  仍占 C 的非开发项：Docker Desktop 程序本体（3.36GB）、Edge 缓存 ~1GB、用户 Videos/Downloads（未动）。
