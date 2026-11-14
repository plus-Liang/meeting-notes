# 交接：录音纪要平台（更新 2026-11-15 00:55，给下一个会话 / 新会话 WorkBuddy）

## 1. 下一步

1. **T3.10 已验收并推送**（`0abbe0e`；amr=132 / opus=133 实机 DONE，187/187，我独立变异红→绿）。
2. **T3.8 第 3 周验收（W3 收口）**：卡已进 `tasks-starter.md`——补测试缺口（畸形 JSON 矩阵 / LOCAL 异常降级 / T3.9 的假绿断言）+ **56.7min 长音频实机**（样本 `D:\datasets\meeting-notes-samples\a_1113b.wav`）+ **真 120s 超时实测**（外部 override，不改 `.env`）+ **F-14 本地档实验** + **F-14～F-19 逐条结论**。配额：今日 8 分钟/5 任务，本卡 ≤57 分钟 / ≤4 任务。
3. 之后：第 4 周 T4.x → 第 5 周（链接导入 + 收尾 + 简历材料）。

## 2. 别踩的坑

1. 对外 JSON 一律 snake_case（D-041）；业务码 7 + 通用码 5；`transcript:""`=无语音仍 DONE（D-048）；`todos` null vs `[]`（D-072）；`ai_tier` 诚实（D-020/D-075）。
2. **DONE 最后写**（D-069）：transcript → 结构化（一次调用）→ 最后 DONE；PASTE 无音频分支同守。
3. **三档是级联**（D-075）；`OFFLINE_MODE=true` 云档硬门禁（D-077）；本地档预算独立（D-081）；**不许改 `.env` 持久值做实验**（用外部 override 文件 + `docker compose -f` 临时叠加，验完恢复）。
4. **模板（T3.9）**：`task_result.template` 提交事务内先写；worker/结构化只更新各自列，不得覆盖；幂等命中不写；详情 12 键、`template` 末尾。
5. **幂等键不含 template**（F-17）：同内容换模板会 reused 返旧任务；实机取证用 hash 变体。
6. **行数 300/文件**：controller 248、StructuringService 280、worker 286、TaskService 267；allowlist 只能 0 条。
7. **测试入口** `scripts\test.ps1`（现 **187/187**，需 `powershell -ExecutionPolicy Bypass -File`）；`mvn` 全路径 `E:\tools\apache-maven-3.9.9\bin\mvn.cmd`；PS5 无三元、`-Dtest=A,B` 要加引号、`cmd < file` 不支持、改源码禁 BOM（K-36）。
8. **psql 查配额/日切先 `SET TIME ZONE 'Asia/Shanghai'`**（默认 UTC 显示前一天，K-36）。
9. **证据/日志**：重建容器前先 `docker compose logs app > D:\new\artifacts\logs-*.txt`（D-087：132/133 日志随容器重建丢失）。
10. `.env` 只写纯值（K-35），hash `2BCC440F1C9E`；**证据行不删**（D-073）；**回报数字必须复算**（K-20）；**一个仓同一时刻只允许一个写者**（D-079）。

## 3. 关键文件

`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md` · `踩坑与复盘-面试用.md`（K-01~K-37）· `spec.md`/`plan.md`/`tasks.md`/`tasks-starter.md`（T3.8 卡）· `decisions.md`（**89 条**，最新 D-088）· `接入指南.md` · `review/2026-11-15-T3.10验收.md`（最新，23 份）· `配置清单.md` · `scripts/backup.ps1`/`restore.ps1` · `rules/dev.md`（§4.1）。

## 4. 未决

T3.8 全部项 · **F-14** 本地档（T3.8 实验）· **F-15** 抽取式读词表（评估）· **F-16** 字幕边界（不修/写文档）· **F-17** 幂等 vs 重跑（T4.1）· **F-18** EXTRACTIVE 模板分化（T3.8 判 spec §6.1）· **F-19** 模板只读查询（P3）· 认证 T4.6 · 真机 UI T4.13 · 长音频/120s 超时（T3.8）。

## 5. 存储与磁盘（2026-11-15 迁移，**后续开发沿用**）

- **背景**：C 盘一度只剩 9.9GB（Docker WSL 数据 26.77GB + 缓存全在 C）。
- **已做（不破坏开发，已实测：6 容器 healthy、DB 49 任务、MinIO 导出与存档逐字节一致、mvn 187/187）**：
  1. Docker 数据迁到 **D:\DockerData\wsl**（26.76GB），原路径改为 **junction**（Docker Desktop 无感，settings 未改）；
     顺带 `docker builder prune -af` 释放 8.9GB 构建缓存（可重建）。
  2. `C:\Users\ljh\.cache\dsh-asr`（2.45GB 模型缓存）→ **D:\cache\dsh-asr** + junction。
  3. Maven 本地仓库 → **D:\cache\m2\repository**（用户级 `~/.m2/settings.xml` 指定 `localRepository`）。
  4. 删除 C 盘陈旧缓存：pnpm 旧 store 1.99GB（活动 store 已在 `D:\.pnpm-store`）、npm 旧 cache 33MB（活动 cache 在 `E:\dsh\cache\npm`）、TEMP 旧文件。
  5. 用户环境变量：`TEMP/TMP=D:\cache\temp`、`PIP_CACHE_DIR=D:\cache\pip`、`HF_HOME=D:\cache\hf`、`GRADLE_USER_HOME=D:\cache\gradle`（对新开的进程生效）。
- **结果**：C 盘可用 **9.9GB → 41.45GB**（+31.5GB）；D 盘 347GB 可用。
- **纪律**：样本/日志/override/临时/构建缓存一律放 **D 盘**（`D:\new\artifacts\`、`D:\cache\`、`D:\DockerData\`），**不许在 `C:\Users\ljh` 下新建大文件**；
  如必须用 C 盘先报。仍在大盘上的非开发项：Docker Desktop 程序本体（C:\...\Programs\DockerDesktop 3.36GB，未迁）、Edge 缓存 ~1GB、用户 Videos/Downloads（未动）。
