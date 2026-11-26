package com.meetingnotes.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingnotes.common.ApiException;

import java.util.List;

/**
 * T4.96/C4：任务列表的纯辅助（从 {@link TaskQueryService} 搬家而来，**只搬家不改语义**——
 * 该类顶 M-3 300 行上限，腾行数给 trash 过滤与 days_left 计算）。
 * 全部无状态静态方法 / 常量：q 归一化与上限、summary 截断、labels/keywords jsonb 文本解析、
 * source_uri → 文件名。无 Spring / JDBC 依赖（可单测直调）。
 */
public final class TaskListSupport {

    /** summary_head 截断长度（卡口径 40 字）。 */
    static final int SUMMARY_HEAD_CHARS = 40;

    /** q 最大长度（卡口径 100 字符，超出 controller 报 400）。 */
    public static final int Q_MAX_CHARS = 100;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TaskListSupport() {
    }

    /** T4.24：q 归一化（去空格；空白 → null；超 {@value #Q_MAX_CHARS} 字符 → 400 INVALID_PARAM）。 */
    public static String normalizeQ(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String trimmed = q.trim();
        if (trimmed.length() > Q_MAX_CHARS) {
            throw new ApiException(400, "INVALID_PARAM", "q 最长 " + Q_MAX_CHARS + " 字符");
        }
        return trimmed;
    }

    /** keywords 列（jsonb 文本）→ 字符串数组；null/空/坏 JSON → []（列表展示容错，不炸接口）。 */
    static List<String> keywordsOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (!node.isArray()) {
                return List.of();
            }
            return MAPPER.readValue(raw, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** labels jsonb 文本 → List<String>（T4.29，坏 JSON/空 → []，口径同 keywords）。 */
    static List<String> labelsOf(String labelsJsonText) {
        if (labelsJsonText == null || labelsJsonText.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(labelsJsonText, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** List<String> → jsonb 数组文本（写入/筛选共用，天然转义）。 */
    static String labelsJson(List<String> labels) {
        try {
            return MAPPER.writeValueAsString(labels);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** summary 前 40 字（null 安全；截断后带省略号由前端决定，这里只给原始截断）。 */
    static String summaryHeadOf(String summary) {
        if (summary == null) {
            return null;
        }
        String trimmed = summary.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= SUMMARY_HEAD_CHARS ? trimmed : trimmed.substring(0, SUMMARY_HEAD_CHARS);
    }

    /** original_name 未单独存列，从 source_uri（s3://bucket/tasks/{id}/{文件名}）取文件名段。 */
    static String originalNameOf(String sourceUri) {
        if (sourceUri == null || sourceUri.isBlank()) {
            return null;
        }
        int slash = sourceUri.lastIndexOf('/');
        return slash >= 0 ? sourceUri.substring(slash + 1) : sourceUri;
    }
}
