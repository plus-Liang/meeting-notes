# 状态：录音纪要平台

**更新**: 2026-11-14 00:55｜**阶段**: 阶段 1（T1.1–T1.6 ✅｜下一步 T1.7 / T1.8）

## 目标与排期（D-030）

给别人用的开源自托管「录音/网课/短视频 → 纪要 + 待办」Web 应用（我也是第一用户），用于 2026 寒假实习。
交付 **2026-12-16**｜简历 **12-15**｜**83h / 50 任务 / 5 周**｜仓库 `plus-Liang/meeting-notes`（ahead/behind 0/0）

## 实测基线

- ASR RTF **0.105 / 0.113**；DeepSeek 跑通；无 key 可启动（`ChatModel` Bean=0）；全栈 **5/5 healthy**
- 端到端：397s 中文音频 → `DONE` **31s** / 1853 字；停 ASR → **9s** `RETRYING×2 → DEAD`
- **T1.6 真机**：`GET /` **200/3399B**；三页共用 `style.css`、0 处硬编码地址；6s 正弦波 → `DONE` + `transcript:""`；
  停 ASR → `DEAD` + `error_code=ASR_ERROR`
- `mvn test` **17/17**（须 `.env` + `ASR_BASE_URL=http://localhost:8001`）；可维护性脚本 **exit 0**
- 语料 `D:\datasets\meeting-notes-samples`（4.93h）；JDK21+Maven3.9.9（`E:\tools`）

## 进度

- 阶段 0 全 ✅；阶段 1：**T1.1–T1.6 ✅**（验收报告 6 份，最新 `review/2026-11-14-T1.6验收.md`）
- 治理基线（11/14 音频，D-050/D-051）：五类腐化 + **review 四目标**（`rules/review.md` §2.0）+ 测试分层/文档巡检（`rules/dev.md`）
  + 模板 13/14 + 样式统一（T1.6 已落：三页共用一份 CSS）+ 开源协议门槛
- 可维护性：宪法 **VII（v1.1.0）** + `可维护性要求.md`（M-1~M-10）+ 脚本 exit 0；allowlist 恢复 **3 条真债**（T1.6 误登记已删）
- 库里有 T1.6 演示数据：task 4（DONE 空 transcript）、task 5（DEAD ASR_ERROR）；dead_letter 1 条

## 待办（按顺序）

1. **T1.7 配置自检**（`配置清单.md` §2 三条命令）→ **T1.8 上传校验单测 + worker 状态机测试**（S-1：RETRYING→DEAD 分支）
2. T3.3 `scripts/test.ps1`（固化 `.env`+`ASR_BASE_URL`，测试分层入口）→ T2.1 幂等（含 D-049 DEAD 重投）→ **T2.10 服务端列表 API（D-053）**
3. T4.1 真机验收（浏览器/移动端上传 + 60min 长音频）；T4.x 发布硬门禁（D-050 第 5 条）

## 阻塞 / 风险

- 无阻塞。风险：`content_hash` 无唯一约束（T2.1）；认证匿名（T4.6）；浏览器渲染/移动端未真机验（T4.1）；
  DEAD 重传原样返回（D-049，T2.1 修）；worker 状态机分支无单测（S-1，T1.8）

## 关键约束

目标给别人用（D-003R）→ README「陌生人 30 分钟跑通」；配置一个不落；不填 key 也能跑；
上限 60min / 批量 20 / 手机上传 P1 / 链接 L1+L2+BYOF；对外 JSON 一律 snake_case（D-041）；
错误码固定 **7 个**（D-047）；空 transcript = 未识别到语音（D-048）；二开前先看开源协议（D-051）；
**禁止手改代码仓里的 `docs/` 生成副本与 allowlist**（T1.6 已踩，见验收报告 F-1/F-2）
