# 来源与版本：impeccable

- **上游**：https://github.com/pbakaus/impeccable
- **版本**：v4.9.2（SKILL frontmatter `version: 4.9.2`）
- **commit**：`d41d9860b0094dcb68030657da63461212835aaa`（2026-11-16）
- **许可**：Apache License 2.0（见同目录 `LICENSE`）
- **本项目复制范围**：`.dsh/skills/impeccable/SKILL.md` + `reference/*.md`（45 个参考 playbook）。
  **未复制**：`scripts/`（自包含引擎二进制/launcher）、`cli/`、crates、browser-bundle 等；本项目用 `npx -y impeccable`（npm 上 v4.9.2 已验证可用）运行 `detect` 检测器，playbook 直接读本目录 md。
- **本项目适配**：App UI（Operate 模式）；不引入其安装器/钩子（写码方不是 Claude Code/Cursor 插件环境）；不执行 live 模式。
- **更新方式**：重新 clone 上游 → 覆盖本目录 `SKILL.md` / `reference/` → 更新本文件 commit/版本 → 跑一次 `npx -y impeccable detect` 确认可运行。
