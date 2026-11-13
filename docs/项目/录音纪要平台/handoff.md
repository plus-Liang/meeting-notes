# 交接：录音纪要平台

**更新**: 2026-11-13 18:20｜给谁：下一个会话 / 写代码的 AI（WorkBuddy）

## 1. 下一步

1. **T0.5**：云 LLM（智谱优先）通道跑通；**同时解决"无 key 也能启动"**（D-035：加 `LLM_ENABLED`，无 key 跳过 chat 自动配置）。
2. 之后进第 1 周：上传接口 → task 表 → worker → 结果页（任务卡见 `tasks-starter.md` / `tasks.md`）。

## 2. 别踩的坑

1. **`ASR_BASE_URL` 不带 `/v1`**（`http://asr:8000`）；Spring AI 自己追加 `/v1/audio/transcriptions`，带了就 404（D-034）。
2. 转写属性前缀是 **`spring.ai.openai.audio.transcription.*`**（必须带 `openai`）；`response-format` 必须 `json`。
3. ASR 已建好（`infra/asr/`，`av==13.1.0` 必须锁定）；容器内听 8000，宿主机走 `${ASR_PORT}:8000`（本机 8000 被占，用 8001 测）。
4. 中文必须 `initial_prompt`（否则繁体）；`small` 专有名词会错（"文件夹→文件家"），靠术语回流补。
5. **`LLM_BASE_URL` 空串会让完整上下文启动失败**（R3）——T0.5 必须处理，不能只填默认假地址。
6. 别做平台页面链接抽取（D-027R 只做 L1/L2/BYOF）；手机上传是 **P1**（D-029）；上限 60min / 批量 20（D-028）。
7. 本机 SAC 拦未签名二进制，媒体处理全容器内（D-010）；硬编码 / 砍测试 / 改备份语义 / 编数字一律不可。
8. 国内网络：第三方镜像要 `REGISTRY_PREFIX=docker.m.daocloud.io/`；`git push` 偶发 SSL 握手失败，**重试 2–3 次**即可。

## 3. 关键文件

详见 `00-先看这个.md`。要点：`spec.md`（WHAT）· `plan.md`（技术方案）· `tasks.md` / `tasks-starter.md`（执行）·
`decisions.md`（36 条，带 R 为修订条）· `T0.1-ASR验证结果.md`（阶段 0 实测）· `测试语料-清单.md`（真实音频 4.93h）。
代码仓：`AGENT.md`（写代码 AI 入口，含已验证事实 §4.1）· `infra/asr/` · 已推 GitHub `plus-Liang/meeting-notes`。

## 4. 未决

- T0.5 云 LLM 实测值（智谱 base_url / 模型名）待填进 D-007。
- 容器内 `http://asr:8000` 未实跑（等 T1.2 起全套 compose 验证）。
- `small` 模型质量是否够用；要不要补全 `medium`/`large-v3` 模型缓存。
- 你实际整理单文件耗时、手机里"短视频"数量（只影响 README 表述，不阻塞开发）。
