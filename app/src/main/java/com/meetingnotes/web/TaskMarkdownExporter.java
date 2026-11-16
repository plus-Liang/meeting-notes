package com.meetingnotes.web;

import java.util.List;

/**
 * 导出 Markdown 格式化（T3.7，独立方法便于以后加 PDF）：四节固定顺序
 * {@code ## 摘要 / ## 关键词 / ## 要点 / ## 待办}（T4.17/D-109）；字段 null/空 → 该节写（无）；
 * 待办带 done 勾选状态（`- [x]`/`- [ ]`），owner/deadline 只在非 null 时追加。
 *
 * <p>**要点节例外**（T4.17 口径，保持稳定）：keypoints 为 null 或空列表 → **整节省略**，
 * 不输出「（无）」占位——历史任务/未生成不出现空节；有值时每条格式 `1. **标题**：说明`。
 */
public final class TaskMarkdownExporter {

    private TaskMarkdownExporter() {
    }

    public static String export(String summary, List<String> keywords,
                                List<KeypointView> keypoints, List<TodoItemView> todos) {
        StringBuilder md = new StringBuilder();
        md.append("## 摘要\n")
                .append(summary == null || summary.isBlank() ? "（无）" : summary)
                .append("\n\n");
        md.append("## 关键词\n")
                .append(keywords == null || keywords.isEmpty() ? "（无）" : String.join(", ", keywords))
                .append("\n\n");
        if (keypoints != null && !keypoints.isEmpty()) {
            md.append("## 要点\n");
            for (int i = 0; i < keypoints.size(); i++) {
                KeypointView kp = keypoints.get(i);
                md.append(i + 1).append(". **").append(kp.title()).append("**：").append(kp.detail())
                        .append("\n");
            }
            md.append("\n");
        }
        md.append("## 待办\n");
        if (todos == null || todos.isEmpty()) {
            md.append("（无）");
        } else {
            for (TodoItemView todo : todos) {
                md.append("- [").append(todo.done() ? "x" : " ").append("] ").append(todo.action());
                if (todo.owner() != null || todo.deadline() != null) {
                    md.append(" （");
                    boolean first = true;
                    if (todo.owner() != null) {
                        md.append("owner: ").append(todo.owner());
                        first = false;
                    }
                    if (todo.deadline() != null) {
                        md.append(first ? "" : " / ").append("deadline: ").append(todo.deadline());
                    }
                    md.append("）");
                }
                md.append("\n");
            }
        }
        return md.toString();
    }
}
