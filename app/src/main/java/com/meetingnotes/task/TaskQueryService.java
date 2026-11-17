package com.meetingnotes.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingnotes.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务列表只读查询（T2.10 / D-053）：keyset 分页（id < before_id ORDER BY id DESC LIMIT n），
 * 禁 OFFSET；user_id 过滤在服务端（D-040）。参数校验在 controller（薄）。
 *
 * <p>T4.21（D-114）：列表项**只增**三个辨识字段（D-041，详情接口 14 键不动）——
 * {@code keywords}（task_result.keywords，null/坏 JSON → []）、{@code summary_head}（summary 前
 * {@value #SUMMARY_HEAD_CHARS} 字，null 安全）、{@code template}（task_result.template，旧任务 null）。
 * LEFT JOIN task_result（task_result.task_id 一对一，不产生行膨胀），分页/排序不变。
 *
 * <p>T4.24：列表**只增**可选参数 {@code q}（服务端搜索）——source_uri ILIKE 参数化匹配
 * （%/_/\ 转义，ESCAPE '\'），纯数字时 OR id 精确匹配；与 status/before_id 可组合，
 * 分页语义不变。新增 {@link #counts(long)}：当前用户 GROUP BY 真计数（无上限）。
 */
@Service
public class TaskQueryService {

    /** summary_head 截断长度（卡口径 40 字）。 */
    static final int SUMMARY_HEAD_CHARS = 40;

    /** q 最大长度（卡口径 100 字符，超出 controller 报 400）。 */
    public static final int Q_MAX_CHARS = 100;

    /** Long.MAX_VALUE 的十进制字面量（T4.24b：判纯数字 q 能否安全 parseLong，防溢出 500）。 */
    private static final String MAX_ID_LITERAL = Long.toString(Long.MAX_VALUE);

    /** T4.24：q 归一化（去空格；空白 → null；超 {@value #Q_MAX_CHARS} 字符 → 400 INVALID_PARAM）。 */
    public static String normalizeQ(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String trimmed = q.trim();
        if (trimmed.length() > Q_MAX_CHARS) {
            throw new ApiException(400, "INVALID_PARAM",
                    "q 最长 " + Q_MAX_CHARS + " 字符");
        }
        return trimmed;
    }

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

    /** T4.24：状态计数响应（真计数无上限；active = PENDING+RUNNING+RETRYING 合计）。 */
    public record TaskCounts(long total, long active, long done, long dead) {
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
     * @param q        可空；非空时 source_uri ILIKE 参数化匹配（%/_/\ 转义），纯数字 OR id 精确匹配；
     *                 已由 controller 去空格、校验长度（≤{@value #Q_MAX_CHARS}）
     * @param limit    已由 controller 校验（1..MAX_LIMIT）
     */
    public TaskListResponse list(long userId, String status, int limit, Long beforeId, String q) {
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
        if (q != null && !q.isBlank()) {
            /* T4.24：参数化模糊匹配，%/_/\ 按字面（ESCAPE '\'），绝不拼字符串进 SQL */
            String like = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            sql.append(" AND (t.source_uri ILIKE ? ESCAPE '\\'");
            args.add("%" + like + "%");
            if (q.matches("\\d+") && parseableAsId(q)) {
                /* T4.24b：仅当能装进 long 才加 id 精确匹配；超 64 位范围（如 20 位数字）
                   按普通关键词只走 ILIKE，保持 200（不再抛 NumberFormatException 打 500） */
                sql.append(" OR t.id = ?");
                args.add(Long.parseLong(q));
            }
            sql.append(")");
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

    /** 纯数字串能否安全 parseLong：位数小于 MAX 或同位数字典序 ≤ MAX（全数字时字典序=数值序）。
     *  public：T4.24b（q 溢出按关键词）与 T4.27b（ids 溢出明确 400）共用同一判定，防 K-76 第三次复发。 */
    public static boolean parseableAsId(String digits) {
        return digits.length() < MAX_ID_LITERAL.length()
                || (digits.length() == MAX_ID_LITERAL.length() && digits.compareTo(MAX_ID_LITERAL) <= 0);
    }

    /**
     * T4.24：当前用户状态真计数（GROUP BY，无上限、不做客户端近似）。
     * active = PENDING+RUNNING+RETRYING 合计；未知状态计入 total 但不进四分类其余键。
     */
    public TaskCounts counts(long userId) {
        Map<String, Long> byStatus = new HashMap<>();
        jdbc.query("SELECT status, count(*) FROM task WHERE user_id = ? GROUP BY status",
                rs -> {
                    byStatus.merge(rs.getString(1), rs.getLong(2), Long::sum);
                }, userId);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long active = byStatus.getOrDefault("PENDING", 0L)
                + byStatus.getOrDefault("RUNNING", 0L)
                + byStatus.getOrDefault("RETRYING", 0L);
        return new TaskCounts(total, active,
                byStatus.getOrDefault("DONE", 0L), byStatus.getOrDefault("DEAD", 0L));
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
