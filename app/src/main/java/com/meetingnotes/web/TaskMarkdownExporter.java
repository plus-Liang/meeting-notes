package com.meetingnotes.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 导出 Markdown 格式化（T3.7，独立方法便于以后加 PDF）：节顺序固定
 * {@code ## 摘要 / ## 关键词 / ## 要点 / ## 转写 / ## 待办}；T4.18 起按 {@code include} 选择集**只输出被选中的节**。
 *
 * <p>口径（保持稳定）：
 * <ul>
 *   <li>摘要/关键词：null/空 → 写（无）；</li>
 *   <li>要点（T4.17/D-109）：keypoints 为 null 或空 → **整节省略**，不输出「（无）」占位；
 *       有值时每条格式 {@code 1. **标题**：说明}；</li>
 *   <li>转写（T4.18）：内容由调用方给定（有 AI 标点文本则已按句换行）；null/空 → 写（无）；</li>
 *   <li>待办：null/空 → 写（无）；带 done 勾选状态（`- [x]`/`- [ ]`），owner/deadline 只在非 null 时追加；
 *       T4.18 默认选择集**不含待办**（观察期，D-109）。</li>
 * </ul>
 */
public final class TaskMarkdownExporter {

    /** include 合法值（固定 5 个，封闭集合；与导出节一一对应）。 */
    public static final String SECTION_SUMMARY = "summary";
    public static final String SECTION_KEYWORDS = "keywords";
    public static final String SECTION_KEYPOINTS = "keypoints";
    public static final String SECTION_TRANSCRIPT = "transcript";
    public static final String SECTION_TODOS = "todos";

    private TaskMarkdownExporter() {
    }

    public static String export(Set<String> include, String summary, List<String> keywords,
                                List<KeypointView> keypoints, String transcript, List<TodoItemView> todos) {
        List<String> sections = new ArrayList<>();
        if (include.contains(SECTION_SUMMARY)) {
            sections.add("## 摘要\n"
                    + (summary == null || summary.isBlank() ? "（无）" : summary) + "\n");
        }
        if (include.contains(SECTION_KEYWORDS)) {
            sections.add("## 关键词\n"
                    + (keywords == null || keywords.isEmpty() ? "（无）" : String.join(", ", keywords)) + "\n");
        }
        if (include.contains(SECTION_KEYPOINTS) && keypoints != null && !keypoints.isEmpty()) {
            StringBuilder kp = new StringBuilder("## 要点\n");
            for (int i = 0; i < keypoints.size(); i++) {
                KeypointView point = keypoints.get(i);
                kp.append(i + 1).append(". **").append(point.title()).append("**：").append(point.detail())
                        .append("\n");
            }
            sections.add(kp.toString());
        }
        if (include.contains(SECTION_TRANSCRIPT)) {
            sections.add("## 转写\n"
                    + (transcript == null || transcript.isBlank() ? "（无）" : transcript) + "\n");
        }
        if (include.contains(SECTION_TODOS)) {
            StringBuilder td = new StringBuilder("## 待办\n");
            if (todos == null || todos.isEmpty()) {
                td.append("（无）");
            } else {
                for (TodoItemView todo : todos) {
                    td.append("- [").append(todo.done() ? "x" : " ").append("] ").append(todo.action());
                    if (todo.owner() != null || todo.deadline() != null) {
                        td.append(" （");
                        boolean first = true;
                        if (todo.owner() != null) {
                            td.append("owner: ").append(todo.owner());
                            first = false;
                        }
                        if (todo.deadline() != null) {
                            td.append(first ? "" : " / ").append("deadline: ").append(todo.deadline());
                        }
                        td.append("）");
                    }
                    td.append("\n");
                }
            }
            sections.add(td.toString());
        }
        return String.join("\n", sections);
    }
}
