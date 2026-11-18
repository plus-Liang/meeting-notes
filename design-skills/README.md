# design-skills —— 前端设计技能（给写代码的 AI 用）

> 本目录是**外部开源设计技能的仓库内副本**，不是项目自己的代码。T4.40 起，涉及"整站设计/视觉改版"的任务必须先读这里。
> 只读、不改其内容；升级时整目录替换并更新 `SOURCE.md` 的版本/commit。

## 目录

| 目录 | 是什么 | 版本 / 许可 | 来源 |
|---|---|---|---|
| `impeccable/` | **设计词汇 + 评审/审计/打磨 playbook + 62 条 slop 检测器**（`detect` 可扫源码或渲染页，确定性规则、无需 API key） | v4.9.2 / Apache-2.0 | https://github.com/pbakaus/impeccable |
| `taste-skill/` | **"设计品味"技能**：反 AI 味、单设计系统、等边距、真实视觉、英雄入场/克制动效、完成度 gate | v2.5.1 / MIT | https://github.com/Leonxlnx/taste-skill |

只复制了**技能定义（SKILL.md + references + LICENSE）**：impeccable 的可执行二进制/CLI 不 vendored（用 `npx -y impeccable` 即可，已验证 v4.9.2 可用）；taste-skill 的营销站资产（照片/着色器/组件库）不 vendored，原因见下。

## 本项目怎么用（重要：已做适配）

本项目是**离线自托管的 App（Operate 模式）**，不是营销落地页：

- **用 impeccable**：`init/document/critique/audit/polish/typeset/layout/harden/adapt` 等 playbook 直接适用；`npx -y impeccable detect` 作为**确定性评审门禁**。
- **用 taste-skill**：取它的**质量标准与验收方法**（一个系统、左右等边、真实状态视觉、安静微交互、在真实浏览器里看 1440/1024/768/390、完成度 gate）。**不套用**它的公司站/营销站模板、真实照片要求、hero 着色器场景（本项目无图片资产、离线自托管、桌面优先）。
- 硬约束（优先级高于技能建议）：**不引 CDN / 前端框架 / 外链字体 / 图标库 / 外部图片**；不改后端接口与行为；六页（index/tasks/task/settings/login/share）+ 抽屉 + 播放器统一；390/768 不回归；深色/浅色以 `PRODUCT.md` + `DESIGN.md` 定稿为准。
- 上下文文件：`PRODUCT.md`、`DESIGN.md` 放**仓库根**（impeccable 约定）。

## 检测门禁（T4.40+ 每张设计卡必跑）

```powershell
cd D:\new\projects\meeting-notes
npx -y impeccable detect app/src/main/resources/static --json   # 源码/静态 HTML 规则
npx -y impeccable detect http://127.0.0.1:8080/login.html --json # 渲染页（公开页面）
```

已登录页面（tasks/task/settings）用 Playwright 带 token 渲染后 `page.content()` 落盘，再 `npx -y impeccable detect file:///... --json`（或在 `--cdp` 的 Chromium 上扫描）。

**基线（T4.39 深色版，2026-11-18）**：`detect static/` = **14 条**（6 warning：`pulsing-dot`；8 advisory：`codex-grid-background`）；渲染 `login.html` = 1 advisory（grid）。新设计目标：**warning 0**；advisory 逐条处理或写明豁免理由（`impeccable-disable` 注释 + 原因）。

## 来源与许可

详见各子目录 `SOURCE.md`；两份均为 MIT / Apache-2.0 开源许可，保留原 LICENSE 文件。本目录内容版权归原作者。
