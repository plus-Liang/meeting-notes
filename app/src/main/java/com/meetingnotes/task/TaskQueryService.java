package com.meetingnotes.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务列表只读查询（T2.10 / D-053）：keyset 分页（id < before_id ORDER BY id DESC LIMIT n），
 * 禁 OFFSET；user_id 过滤在服务端（D-040）。参数校验在 controller（薄）。
 *
 * <p>T4.21（D-114）：列表项**只增**三个辨识字段（D-041，详情接口 14 键不动）——
 * {@code keywords}（task_result.keywords，null/坏 JSON → []）、{@code summary_head}（summary 前
 * {@value #SUMMARY_HEAD_CHARS} 字，null 安全）、{@code template}（task_result.template，旧任务 null）。
 * LEFT JOIN task_result（task_result.task_id 一对一，不产生行膨胀），分页/排序不变。
 */
@Service
public class TaskQueryService {

    /** summary_head 截断长度（卡口径 40 字）。 */
    static final int SUMMARY_HEAD_CHARS = 40;

    /** 列表项：对外契约 snake_case（D-041，T2.10 六键 + T4.21 三键，只增不改）。 */
    public record TaskListItem(
            long id,
            String status,
            String originalName,
            Long durationSec,
            OffsetDateTime createdAt,
            String errorCode,
            List<String> keywords,
            String summaryHead,
            String template) {
    }

    /** 列表响应：items + next_before_id（还有下一页时为最后一项的 id，否则 null）。 */
    public record TaskListResponse(List<TaskListItem> items, Long nextBeforeId) {
    }

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final String bucket;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskQueryService(JdbcTemplate jdbc,
                            @Value("${app.storage.bucket}") String bucket) {
        this.jdbc = jdbc;
        this.bucket = bucket;
    }

    /**
     * @param status   可空；给定时精确过滤（非法值自然匹配 0 行，不抛错）
     * @param beforeId 可空；keyset 游标
     * @param limit    已由 controller 校验（1..MAX_LIMIT）
     */
    public TaskListResponse list(long userId, String status, int limit, Long beforeId) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.status, t.source_uri, t.duration_sec, t.created_at, t.error_code,
                       r.keywords, r.summary, r.template
                FROM task t
                LEFT JOIN task_result r ON r.task_id = t.id
                WHERE t.user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND t.status = ?");
            args.add(status.trim());
        }
        if (beforeId != null) {
            sql.append(" AND t.id < ?");
            args.add(beforeId);
        }
        sql.append(" ORDER BY t.id DESC LIMIT ?");
        args.add(limit + 1); // 多取 1 行判断是否还有下一页

        List<TaskListItem> items = new ArrayList<>();
        boolean[] hasMore = {false};
        jdbc.query(sql.toString(), rs -> {
            if (items.size() < limit) {
                items.add(new TaskListItem(
                        rs.getLong("id"),
                        rs.getString("status"),
                        originalNameOf(rs.getString("source_uri")),
                        rs.getObject("duration_sec") == null ? null : rs.getLong("duration_sec"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("error_code"),
                        keywordsOf(rs.getString("keywords")),
                        summaryHeadOf(rs.getString("summary")),
                        rs.getString("template")));
            } else {
                hasMore[0] = true; // 第 limit+1 行存在 = 还有下一页
            }
        }, args.toArray());

        Long nextBeforeId = hasMore[0] && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
        return new TaskListResponse(items, nextBeforeId);
    }

    /** keywords 列（jsonb 文本）→ 字符串数组；null/空/坏 JSON → []（列表展示容错，不炸接口）。 */
    private List<String> keywordsOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = mapper.readTree(raw);
            if (!node.isArray()) {
                return List.of();
            }
            return mapper.readValue(raw, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
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
    private String originalNameOf(String sourceUri) {
        if (sourceUri == null || sourceUri.isBlank()) {
            return null;
        }
        int slash = sourceUri.lastIndexOf('/');
        return slash >= 0 ? sourceUri.substring(slash + 1) : sourceUri;
    }
}
