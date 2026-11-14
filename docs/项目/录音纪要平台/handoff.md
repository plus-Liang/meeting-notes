# 交接：录音纪要平台（更新 2026-11-14 21:50，给下一个会话 / 新会话 WorkBuddy）

## 1. 下一步

1. **T3.9 收尾（只差 2 条实机）**：代码 `f880b6a` 已验（185/185、可维护性 exit 0、我独立变异红→绿、零配额实机全过），报告 `review/2026-11-14-T3.9验收.md` 状态"待实机定案"。
   **配额 00:00 重置后**：传 `D:\new\artifacts\腾讯会议_t39a.wav`（不传 template → meeting）与 `t39b_course.wav`（`template=course`），验 DONE/`ai_tier=CLOUD`/详情 `template`/summary 结构差异/日志；然后打 ✅、更新报告与 state，**推送本地两笔提交（`f880b6a` 代码 + docs）**。
2. **T3.10 格式扩展（amr/opus）**：卡已进 `tasks-starter.md`，样本已备（容器 ffmpeg 实测可解）；2 条实机任务等配额。
3. 之后：**T3.8 周验收**（F-14/F-15/F-16/F-17/F-18/F-19 + 长音频/120s 超时）→ 第 4 周 T4.x → 第 5 周。

## 2. 别踩的坑

1. 对外 JSON 一律 snake_case（D-041）；业务码 7 + 通用码 5；`transcript:""`=无语音仍 DONE（D-048）；`todos` null vs `[]`（D-072）；`ai_tier` 诚实（D-020/D-075）。
2. **DONE 最后写**（D-069）：transcript → 结构化（一次调用）→ 最后 DONE；PASTE 无音频分支同守。
3. **三档是级联**（D-075）；`OFFLINE_MODE=true` 云档硬门禁（D-077）；本地档预算独立（D-081）。
4. **T3.9 模板口径**：`task_result.template` 提交事务内先写；worker `saveTranscript` 与结构化 `persist` **只更新各自列，不得覆盖 template**；幂等命中路径不写（旧任务保留自己模板）。
5. **幂等键不含 template**（F-17）：同音频换模板会 reused 返旧任务；实机取证必须用 **hash 变体**（前加 10ms/20ms 静音，样本已备）。
6. **行数 300/文件**：controller 248、StructuringService 280、worker 286、TaskService 267——新逻辑进独立小类，allowlist 只能 0 条。
7. **配额满了不等于不能验收**（K-36）：用"必拒绝请求"（满配额新内容→429）证明事务回滚无脏行；真 DONE 证据等 00:00 重置再补，报告写"待实机定案"。
8. **PowerShell 5.1 小坑**：无三元 `? :`；`-Dtest=A,B` 逗号要整体加引号；`cmd < file` 不支持；改源码做变异用 `[IO.File]::WriteAllText(..., UTF8Encoding($false))`（禁 BOM）；`mvn` 用全路径 `E:\tools\apache-maven-3.9.9\bin\mvn.cmd`。
9. `.env` 只写纯值（K-35），当前 hash `2BCC440F1C9E`；测试入口 `scripts/test.ps1`（现 185/185）；**证据行不删**（D-073）；**回报数字必须复算**（K-20）；**一个仓同一时刻只允许一个写者**（D-079）。

## 3. 关键文件

`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md` · `踩坑与复盘-面试用.md`（K-01~K-36）· `spec.md`/`plan.md`/`tasks.md`/`tasks-starter.md`（T3.10 卡）· `decisions.md`（**87 条**，最新 D-086）· `接入指南.md`（template 字段待实机定案后补）· `review/2026-11-14-T3.9验收.md`（最新，22 份）· `配置清单.md` · `scripts/backup.ps1`/`restore.ps1` · `rules/dev.md`（§4.1）。

## 4. 未决

**T3.9 2 条实机**（待配额）· **F-14** 本地档（T3.8）· **F-15** 抽取式读词表（T3.8）· **F-16** 字幕编码边界（P3）· **F-17** 幂等 vs 换模板重跑（T4.1）· **F-18** EXTRACTIVE 模板分化（T3.8 判 spec §6.1）· **F-19** 模板只读查询（P3）· 认证 T4.6 · 真机 UI T4.13 · 长音频/LLM 真 120s 超时未测（T3.8）。
