# MeetingNotes —— 录音 / 网课 → 结构化纪要 + 待办（自托管）

> **项目权威文档在 `D:\new\docs\项目\录音纪要平台\`**（spec / plan / tasks / decisions / 面试材料）。
> 本目录只放**代码**。开工前先读 `docs/项目/录音纪要平台/state.md` + `handoff.md`。
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
│        └─ db/migration/V1__init.sql
└─ infra/asr/              ASR 服务说明（FunASR OpenAI 兼容）
```

---

## 开发顺序（不许乱）

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
| 单任务 ≤30 分钟 / ≤200MB | spec §4 |
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
