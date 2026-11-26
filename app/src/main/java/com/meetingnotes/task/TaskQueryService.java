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
 *
 * <p>T4.42（D-160）：列表**只增**可选参数 {@code created_after}/{@code created_before}
 * （{@code YYYY-MM-DD}，服务端时区解释为半开区间 [当日 00:00, 次日 00:00)；格式非法或
 * after 日期晚于 before → 400 INVALID_PARAM，见 {@link TaskDateWindow#resolve}）；响应**只增**
 * {@code total}（同筛选条件 COUNT，不含 before_id 游标、键不省略）；{@code label} 支持多值
 * （重复 label 参数 → 各自 {@code @>} 后 OR 并集，**同维度并集/跨维度交集**（D-162），
 * 单值行为不变）。
 * keyset 分页/排序固定 created 相关的 {@code id DESC} 不变，**不做**自定义排序。
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

    /** 列表项：对外契约 snake_case（D-041；T2.10 六键 + T4.21 三键 + T4.29 两键 + T4.43 一键 + T4.95/C2 error_message（dead_letter 最新人话原因），只增不改、末尾追加）。 */
    public record TaskListItem(
            long id,
            String status,
            String originalName,
            Long durationSec,
            OffsetDateTime createdAt,
            String errorCode,
            List<String> keywords,
            String summaryHead,
            String template,
            List<String> labels,
            boolean favorite,
            String displayName,
            String errorMessage) {
    }

    /** 列表响应：items + next_before_id（还有下一页时为最后一项的 id，否则 null）+
     *  T4.42 total（同筛选条件 COUNT，键不省略；契约要求放响应末尾，record 声明序即 JSON 序）。 */
    public record TaskListResponse(List<TaskListItem> items, Long nextBeforeId, long total) {
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
     * @param status   可空；给定时精确过滤
     * @param beforeId 可空；keyset 游标（不进 total 的 COUNT）
     * @param q        可空；非空时 source_uri ILIKE 参数化匹配（%/_/\ 转义），纯数字 OR id 精确匹配；
     *                 已由 controller 去空格、校验长度（≤{@value #Q_MAX_CHARS}）
     * @param labels   可空；T4.42 多标签筛选——每个标签一个 jsonb {@code @>} 条件、OR 括号组 =
     *                 **OR 并集**（同维度并集/跨维度交集，D-162；单标签行为不变），
     *                 经 mapper 序列化参数化绑定（注入面同 q）；空列表视为未给
     * @param favorite 可空；T4.29 收藏筛选（true/false）
     * @param window   可空；T4.42 created_at 半开区间过滤
     * @param limit    已由 controller 校验（1..MAX_LIMIT）
     */
    public TaskListResponse list(long userId, String status, int limit, Long beforeId, String q,
                                 List<String> labels, Boolean favorite, TaskDateWindow window) {
        /* WHERE 片段与参数：列表与 total 的 COUNT 共用（COUNT 不含 before_id 游标——
           total 口径 = 该筛选组合下的总条数，与分页无关） */
        StringBuilder where = new StringBuilder("t.user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (status != null && !status.isBlank()) {
            where.append(" AND t.status = ?");
            args.add(status.trim());
        }
        if (q != null && !q.isBlank()) {
            /* T4.24：参数化模糊匹配，%/_/\ 按字面（ESCAPE '\'），绝不拼字符串进 SQL */
            String like = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            where.append(" AND (t.source_uri ILIKE ? ESCAPE '\\'");
            args.add("%" + like + "%");
            if (q.matches("\\d+") && parseableAsId(q)) {
                /* T4.24b：仅当能装进 long 才加 id 精确匹配；超 64 位范围（如 20 位数字）
                   按普通关键词只走 ILIKE，保持 200（不再抛 NumberFormatException 打 500） */
                where.append(" OR t.id = ?");
                args.add(Long.parseLong(q));
            }
            where.append(")");
        }
        if (labels != null && !labels.isEmpty()) {
            /* T4.42/D-162：多标签 = **OR 并集**（同维度多值取并集、跨维度 AND；D-162 裁决）——
               每个标签一个 t.labels @> ?::jsonb，OR 串成一个括号组；全程参数化（标签值绝不拼进 SQL）。
               单标签行为不变（单条件括号组）。 */
            where.append(" AND (");
            for (int i = 0; i < labels.size(); i++) {
                if (i > 0) {
                    where.append(" OR ");
                }
                where.append("t.labels @> ?::jsonb");
                args.add(labelsJson(List.of(labels.get(i))));
            }
            where.append(")");
        }
        if (favorite != null) {
            where.append(" AND t.favorite = ?");
            args.add(favorite);
        }
        if (window != null) {
            if (window.after() != null) {
                where.append(" AND t.created_at >= ?");
                args.add(window.after());
            }
            if (window.before() != null) {
                where.append(" AND t.created_at < ?");
                args.add(window.before());
            }
        }

        /* T4.42：total = 同筛选条件 COUNT（键不省略；查询失败不炸列表——queryForObject 对空表回 0 不回 null） */
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM task t WHERE " + where, Long.class, args.toArray());

        /* 注意：文本块会剥掉行尾空格，WHERE 后必须显式补空格再拼 where 片段（T4.42 首跑踩过 WHEREt）。
           T4.95/C2：error_message = dead_letter 最新一条 reason（可移植 LIMIT 子查询，H2/PG 都能跑，禁 LATERAL/RETURNING）；
           **后端不按状态裁剪**——DONE 也可能有历史 dead_letter 行（T4.87 复活后残留），前端按 status 判断展示。 */
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.status, t.source_uri, t.duration_sec, t.created_at, t.error_code,
                       r.keywords, r.summary, r.template, t.labels::text AS labels, t.favorite,
                       t.display_name,
                       COALESCE((SELECT d.reason FROM dead_letter d WHERE d.task_id = t.id ORDER BY d.id DESC LIMIT 1), NULL) AS error_message
                FROM task t
                LEFT JOIN task_result r ON r.task_id = t.id
                WHERE """).append(' ').append(where);
        List<Object> listArgs = new ArrayList<>(args);
        if (beforeId != null) {
            sql.append(" AND t.id < ?");
            listArgs.add(beforeId);
        }
        sql.append(" ORDER BY t.id DESC LIMIT ?");
        listArgs.add(limit + 1); // 多取 1 行判断是否还有下一页

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
                        rs.getString("template"),
                        labelsOf(rs.getString("labels")),
                        rs.getBoolean("favorite"),
                        rs.getString("display_name"),
                        rs.getString("error_message")));
            } else {
                hasMore[0] = true; // 第 limit+1 行存在 = 还有下一页
            }
        }, listArgs.toArray());

        Long nextBeforeId = hasMore[0] && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
        return new TaskListResponse(items, nextBeforeId, total == null ? 0L : total);
    }

    /** labels jsonb 文本 → List<String>（T4.29，坏 JSON/空 → []，口径同 keywords）。 */
    private List<String> labelsOf(String labelsJsonText) {
        if (labelsJsonText == null || labelsJsonText.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(labelsJsonText, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** List<String> → jsonb 数组文本（写入/筛选共用，天然转义）。 */
    private String labelsJson(List<String> labels) {
        try {
            return mapper.writeValueAsString(labels);
        } catch (Exception e) {
            return "[]";
        }
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
