package com.meetingnotes.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 待办依据校验（T3.3 ⑨ / D-076）：**三档统一**的后处理，治"模型编造"。
 *
 * <p>起因：本地 1.5B 实测把原文"下周三"编成 `2023-04-15`、"下个月初"编成 `2023-06-01`——
 * 提示词里的"严禁猜测"**不能保证**（K-21），必须在代码层兜住：
 * <ul>
 *   <li>{@code owner}：出现在 transcript 才保留，否则 null；</li>
 *   <li>{@code deadline}：时间词/日期串必须在 transcript 中出现才保留，否则 null——
 *       **具体日期原文没有该串 → null**，**相对时间词保留原词，绝不换算成绝对日期**；</li>
 *   <li>{@code action}：允许概括，但核心词要有依据（字符二元组命中率 ≥ 0.5），否则整条丢弃。</li>
 * </ul>
 * 比较前把 transcript 与候选值都做同样的归一化（去空白 + 去标点 + 小写），避免标点差异造成误杀。
 */
public final class TodoGrounding {

    /** action 的核心词依据阈值（D-076：二元组命中率 ≥ 0.5 才保留）。 */
    static final double MIN_ACTION_BIGRAM_HIT = 0.5;

    /** 参与归一化时丢弃的标点（中英文）。 */
    private static final String PUNCTUATION = "，。！？；：、,.!?;:\"'“”‘’（）()《》〈〉【】[]…—-~· \t\r\n";

    private TodoGrounding() {
    }

    /**
     * 逐条校验：owner/deadline 无依据 → null；action 无依据 → 整条丢弃。
     *
     * @return 校验后的新列表（不修改入参；todos 为 null 时返回空列表）
     */
    public static List<TodoService.TodoItem> validate(List<TodoService.TodoItem> todos, String transcript) {
        List<TodoService.TodoItem> kept = new ArrayList<>();
        if (todos == null || todos.isEmpty()) {
            return kept;
        }
        String normalizedTranscript = normalize(transcript);
        for (TodoService.TodoItem item : todos) {
            if (item == null || item.action() == null || item.action().isBlank()) {
                continue;
            }
            if (!actionGrounded(item.action(), normalizedTranscript)) {
                continue;
            }
            kept.add(new TodoService.TodoItem(item.action().trim(),
                    groundedOrNull(item.owner(), normalizedTranscript),
                    groundedOrNull(item.deadline(), normalizedTranscript)));
        }
        return kept;
    }

    /** 候选值必须在原文里有依据；空值/无有效字符（纯标点）一律 null。 */
    static String groundedOrNull(String value, String normalizedTranscript) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = normalize(value);
        if (normalized.isEmpty() || !normalizedTranscript.contains(normalized)) {
            return null;
        }
        return value.trim();
    }

    /** action 允许概括：字符二元组命中率 ≥ 0.5；不足两个字符时退化为整串包含判断。 */
    static boolean actionGrounded(String action, String normalizedTranscript) {
        String normalized = normalize(action);
        if (normalized.isEmpty()) {
            return false;
        }
        List<String> bigrams = bigrams(normalized);
        if (bigrams.isEmpty()) {
            return normalizedTranscript.contains(normalized);
        }
        long hits = bigrams.stream().filter(normalizedTranscript::contains).count();
        return (double) hits / bigrams.size() >= MIN_ACTION_BIGRAM_HIT;
    }

    /** 去空白 + 去标点 + 小写（同一条归一化口径，保证"比较的两边"同构）。 */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (PUNCTUATION.indexOf(c) >= 0) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 归一化后的字符二元组（去重保序）；与 {@link ExtractiveStructurer} 共用同一套口径。 */
    static List<String> bigrams(String normalizedText) {
        Set<String> unique = new LinkedHashSet<>();
        for (int i = 0; i + 1 < normalizedText.length(); i++) {
            unique.add(normalizedText.substring(i, i + 2));
        }
        return new ArrayList<>(unique);
    }
}
