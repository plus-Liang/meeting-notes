package com.meetingnotes.task;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务列表只读查询（T2.10 / D-053）：keyset 分页（id < before_id ORDER BY id DESC LIMIT n），
 * 禁 OFFSET；user_id 过滤在服务端（D-040）。参数校验在 controller（薄）。
 */
@Service
public class TaskQueryService {

    /** 列表项：对外契约 snake_case（original_name / duration_sec / created_at / error_code）。 */
    public record TaskListItem(
            long id,
            String status,
            String originalName,
            Long durationSec,
            OffsetDateTime createdAt,
            String errorCode) {
    }

    /** 列表响应：items + next_before_id（还有下一页时为最后一项的 id，否则 null）。 */
    public record TaskListResponse(List<TaskListItem> items, Long nextBeforeId) {
    }

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final String bucket;

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
                SELECT id, status, source_uri, duration_sec, created_at, error_code
                FROM task WHERE user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim());
        }
        if (beforeId != null) {
            sql.append(" AND id < ?");
            args.add(beforeId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
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
                        rs.getString("error_code")));
            } else {
                hasMore[0] = true; // 第 limit+1 行存在 = 还有下一页
            }
        }, args.toArray());

        Long nextBeforeId = hasMore[0] && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
        return new TaskListResponse(items, nextBeforeId);
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
