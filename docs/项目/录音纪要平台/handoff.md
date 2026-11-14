# 交接：录音纪要平台（更新 2026-11-15 00:10，给下一个会话 / 新会话 WorkBuddy）

## 1. 下一步

1. **T3.9 已验收并推送**（`f880b6a` 代码 + docs；185/185 + 实机 129/130/131 通过）。
2. **T3.10 格式扩展（amr/opus）**：卡在 `tasks-starter.md`；样本 `D:\new\artifacts\t310-amr.amr` / `t310-opus.opus`（容器 ffmpeg 已实测可解）；
   只许 2 条实机任务；失败文案补"转成 mp3"；新配额日 2026-11-15 目前 4 分钟 / 3 任务（还有充足额度）。
3. 之后：**T3.8 周验收**（F-14/F-15/F-16/F-17/F-18/F-19 + 长音频/120s 超时）→ 第 4 周 T4.x → 第 5 周。

## 2. 别踩的坑

1. 对外 JSON 一律 snake_case（D-041）；业务码 7 + 通用码 5；`transcript:""`=无语音仍 DONE（D-048）；`todos` null vs `[]`（D-072）；`ai_tier` 诚实（D-020/D-075）。
2. **DONE 最后写**（D-069）：transcript → 结构化（一次调用）→ 最后 DONE；PASTE 无音频分支同守。
3. **三档是级联**（D-075）；`OFFLINE_MODE=true` 云档硬门禁（D-077）；本地档预算独立（D-081）。
4. **模板（T3.9）**：`task_result.template` 提交事务内先写；worker/结构化只更新各自列，**不得覆盖 template**；幂等命中不写（旧任务保留自己模板）；详情 12 键、`template` 在末尾。
5. **幂等键不含 template**（F-17）：同内容换模板会 reused 返旧任务；实机取证用 hash 变体（前加 10/20ms 静音）。
6. **行数 300/文件**：controller 248、StructuringService 280、worker 286、TaskService 267——新逻辑进独立小类，allowlist 只能 0 条。
7. **测试入口** `scripts\test.ps1`（现 185/185，`powershell -ExecutionPolicy Bypass -File`）；`mvn` 用全路径；PS5 无三元、`-Dtest=A,B` 要加引号、`cmd < file` 不支持、改源码禁 BOM（K-36）。
8. **psql 查配额/日切先 `SET TIME ZONE 'Asia/Shanghai'`**（默认 UTC 会话会显示前一天，误判"配额没重置"，K-36）。
9. `.env` 只写纯值（K-35），hash `2BCC440F1C9E`；**证据行不删**（D-073）；**回报数字必须复算**（K-20）；**一个仓同一时刻只允许一个写者**（D-079）。

## 3. 关键文件

`00-先看这个.md` · `开工准备清单.md` · `架构一页纸.md` · `可维护性要求.md` · `踩坑与复盘-面试用.md`（K-01~K-36）· `spec.md`/`plan.md`/`tasks.md`/`tasks-starter.md`（T3.10 卡）· `decisions.md`（**87 条**，最新 D-086）· `接入指南.md`（已补 template 字段/自动推荐/F-17 注意）· `review/2026-11-14-T3.9验收.md`（最新，22 份）· `配置清单.md` · `scripts/backup.ps1`/`restore.ps1` · `rules/dev.md`（§4.1）。

## 4. 未决

**F-14** 本地档（T3.8）· **F-15** 抽取式读词表（T3.8）· **F-16** 字幕编码边界（P3）· **F-17** 幂等 vs 换模板重跑（T4.1）· **F-18** EXTRACTIVE 模板分化（T3.8 判 spec §6.1）· **F-19** 模板只读查询（P3）· 认证 T4.6 · 真机 UI T4.13 · 长音频/LLM 真 120s 超时未测（T3.8）· `StructuringServiceTest` 的 varargs `never()` 可能空过（T3.8 补断言）。
