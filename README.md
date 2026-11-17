# MeetingNotes —— 录音 / 网课 → 结构化纪要 + 待办（自托管）

> **项目权威文档在 `D:\new\docs\项目\录音纪要平台\`**（spec / plan / tasks / decisions / 面试材料）。
> 本目录只放**代码**。开工前先读 `docs/项目/录音纪要平台/state.md` + `handoff.md`。
>
> **给写代码的 AI**：先读仓库根 **`AGENT.md`**（必读顺序 + 十条硬约束 + 当前任务 + 回报格式），
> 再读 `docs/项目/录音纪要平台/tasks-starter.md` 的第一张任务卡。**一次只做一张卡。**
>
> 定位：**不是**飞书妙记的替代品。服务三类人：①本地文件囤积者 ②数据不能出门的人 ③要接进自己系统的人。

---

## 快速开始

```powershell
cd D:\new\projects\meeting-notes
Copy-Item .env.example .env
docker compose up -d
# 访问 http://localhost:8080/swagger-ui.html
```

**不填任何 API key 也能跑通全流程**（本地 ASR + 本地/抽取式摘要），详见 `docs/项目/录音纪要平台/spec.md` N15。

**链接导入（T4.32）**：本仓库**不含任何平台解析器**（不集成 yt-dlp、不执行 JS、不带 cookie/签名）；贴直链可直接建任务，平台页面链接只做公开元信息预览（`POST /api/v1/link/preview`），BYOF（`FETCHER_CMD`）由使用者自装外部工具、自负合规责任——契约见 `docs/项目/录音纪要平台/接入指南.md` §3.9。

---

## 模型选择建议（ASR）

- **清晰单人录音**：`small`（默认，461MB，RTF≈0.16，本机 CPU 实测）——开箱即跑，够用。
- **会议 / 远场 / 多人**：`medium`（1457MB，RTF≈0.38，本机 CPU 实测）更准但更慢，需自备模型文件。
- 换档只改环境变量：`ASR_MODEL_NAME=medium` 后重启 asr 服务生效，不改代码；**默认保持 small**。

---

## 运维：查日志 / 备份 / 恢复

**查一个任务的全链路**（D-062：日志默认结构化 JSON，`taskId` 是独立字段）：

```bash
docker compose logs app | grep '"taskId":"123"'      # 一个任务的 5 个处理节点一把捞出
LOG_JSON=false docker compose up -d app             # 想回到人类可读文本时（改 .env 的 LOG_JSON 即可）
```

**备份**（DB + MinIO 音频对象 + 清单，产物在 `backups/<时间戳>/`，默认保留最近 7 份；**备份包不含 `.env`**）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup.ps1        # 立刻备份一次
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup.ps1 -Keep 14
```

**每天自动备份**（D-063：不做内置调度器，用宿主定时任务调上面这条命令）：

```powershell
# Windows：任务计划程序 → 每天 03:00 执行
schtasks /Create /TN "MeetingNotes-Backup" /SC DAILY /ST 03:00 /TR "powershell -NoProfile -ExecutionPolicy Bypass -File D:\new\projects\meeting-notes\scripts\backup.ps1" /F
```

```bash
# Linux/macOS：先装 pwsh，再 crontab -e
0 3 * * * cd /opt/meeting-notes && pwsh -NoProfile -File scripts/backup.ps1
```

> 备份必须落在**宿主目录**（默认 `./backups`）。放进 Docker volume 会被 `docker compose down -v` 一起删掉，等于没备份。

**恢复**（恢复会清空当前数据，必须 `-Force` 确认）：

```powershell
docker compose up -d                                  # 灾难后先让 postgres/minio 起来，再恢复
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore.ps1 -BackupDir backups\<时间戳> -Force
```

恢复脚本会按备份清单逐表核对行数并检查 health；失败码：`10` 缺 `-Force`、`11` 备份缺失/为空、`12` DB 恢复失败、`13` 对象恢复失败、`14` 自检不一致。

---

## 目录

```text
meeting-notes/
├─ docker-compose.yml      postgres / redis / minio / asr / app
├─ .env.example            全部配置（一个不落，见下）
├─ app/                    Spring Boot 3 应用
│  ├─ pom.xml
│  └─ src/main/
│     ├─ java/com/meetingnotes/
│     └─ resources/
│        ├─ application.yml
│        └─ db/migration/   V1__init.sql、V2__task_content_hash_unique.sql
└─ infra/asr/              ASR 服务（自建 faster-whisper，OpenAI 兼容 HTTP，见 D-031）
```

---

## 开发顺序（不许乱）

> 这是**计划顺序**；实际进度以 `AGENT.md` §3 与 `docs/项目/录音纪要平台/state.md` 为准（当前已完成到第 2 周，下一步 T2.9）。

1. **阶段 0**：`tasks.md` 的 T0.1–T0.5 —— 先把 ASR 服务与 Spring AI 跑通，**不通过不写业务**
2. 然后做 `PASTE` 入口（不依赖模型，最快验证编排链路）
3. 再接 `UPLOAD`
4. 最后接 `FETCH`（安全校验最多）

---

## 硬约束（违反即返工）

| 约束 | 出处 |
|---|---|
| 交付形态只做 Web，不做小程序/桌面端 | 宪法原则 I / D-001 |
| 不自研 ASR/LLM 模型，只做编排 | 宪法原则 II / D-005 |
| **所有配置走环境变量**，无硬编码路径/端口/key/并发数/模型名 | N11 / D-021 |
| 不填 key 也能完整跑通（三档 AI 降级） | N15 / D-020 |
| 单任务 ≤60 分钟（可配 120）/ ≤200MB；批量 ≤20 | spec §4 / D-028 |
| 媒体处理全在容器内（本机 SAC 拦未签名二进制） | D-010 |

---

## 配置（全量清单见 `docs/项目/录音纪要平台/配置清单.md`）

所有配置项必须有环境变量入口，`.env.example` 是唯一事实来源。**禁止以下写法**：

```java
// ❌ 禁止
String url = "http://localhost:8000/v1";
int concurrency = 2;
Path path = Paths.get("C:/Users/ljh/Videos");
```

```java
// ✅ 正确
@Value("${app.asr.base-url}") String url;
@Value("${app.asr.max-concurrency}") int concurrency;
```

自检：`grep -rn "localhost\|C:\\\\\|sk-\|127.0.0.1" app/src/main` 应为空（`application.yml` 里的 `${VAR:default}` 除外）。
