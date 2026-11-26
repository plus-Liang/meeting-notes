package com.meetingnotes.task;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务列表只读查询（T2.10 / D-053）：keyset 分页（id < before_id ORDER BY id DESC LIMIT n），
 * 禁 OFFSET；user_id 过滤在服务端（D-040）。参数校验在 controller（薄）。
 * 纯辅助（q 归一化 / jsonb 解析 / 截断 / 文件名）已搬到 {@link TaskListSupport}（T4.96 只搬家不改语义）。
 *
 * <p>T4.42（D-160）：created_after/created_before 半开区间、total、label 多值 OR 并集（D-162）。
 * keyset 分页/排序固定 {@code id DESC} 不变，**不做**自定义排序。
 *
 * <p>T4.96/C4（v0.2 回收站，D-245）：列表**只增**可选参数 {@code trash}——默认 {@code false} 追加
 * {@code t.deleted_at IS NULL}（回收站任务对默认列表**不可见**，漏加即泄露）；{@code true} 时取反
 * （只看回收站），COUNT 用同一条件。列表项**末尾只增** {@code deleted_at} 与 {@code days_left}
 * （仅 trash=true 时计算：{@code max(0, retentionDays - 已过整天数)}，非 trash 恒 null）。
 */
@Service
public class TaskQueryService {

    /** Long.MAX_VALUE 的十进制字面量（T4.24b：判纯数字 q 能否安全 parseLong，防溢出 500）。 */
    private static final String MAX_ID_LITERAL = Long.toString(Long.MAX_VALUE);

    /** 列表项：对外契约 snake_case（D-041；T2.10 六键 + T4.21 三键 + T4.29 两键 + T4.43 一键 + T4.95/C2 error_message + T4.96/C4 deleted_at/days_left），只增不改、末尾追加。 */
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
            String errorMessage,
            OffsetDateTime deletedAt,
            Integer daysLeft) {
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
    private final int retentionDays; // T4.96：回收站保留期（days_left 计算用；sweeper 与此处共用同一配置键）

    public TaskQueryService(JdbcTemplate jdbc,
                            @Value("${app.storage.bucket}") String bucket,
                            @Value("${app.trash.retention-days:30}") int retentionDays) {
        this.jdbc = jdbc;
        this.bucket = bucket;
        this.retentionDays = retentionDays;
    }

    /**
     * @param status   可空；给定时精确过滤
     * @param beforeId 可空；keyset 游标（不进 total 的 COUNT）
     * @param q        可空；非空时 source_uri ILIKE 参数化匹配（%/_/\ 转义），纯数字 OR id 精确匹配；
     *                 已由 controller 去空格、校验长度（≤{@link TaskListSupport#Q_MAX_CHARS}）
     * @param labels   可空；T4.42 多标签筛选（OR 并集，D-162），参数化绑定，空列表视为未给
     * @param favorite 可空；T4.29 收藏筛选（true/false）
     * @param window   可空；T4.42 created_at 半开区间过滤
     * @param trash    T4.96/C4：false（默认）只看未删除（{@code deleted_at IS NULL}）；true 只看回收站
     * @param limit    已由 controller 校验（1..MAX_LIMIT）
     */
    public TaskListResponse list(long userId, String status, int limit, Long beforeId, String q,
                                 List<String> labels, Boolean favorite, TaskDateWindow window, boolean trash) {
        /* WHERE 片段与参数：列表与 total 的 COUNT 共用（COUNT 不含 before_id 游标——
           total 口径 = 该筛选组合下的总条数，与分页无关）。
           T4.96/C4：可见性条件与 trash 同源——默认列表绝不出现回收站任务（漏加即泄露）。 */
        StringBuilder where = new StringBuilder("t.user_id = ? AND t.deleted_at IS "
                + (trash ? "NOT NULL" : "NULL"));
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
                args.add(TaskListSupport.labelsJson(List.of(labels.get(i))));
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
                       COALESCE((SELECT d.reason FROM dead_letter d WHERE d.task_id = t.id ORDER BY d.id DESC LIMIT 1), NULL) AS error_message,
                       t.deleted_at
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
        OffsetDateTime now = OffsetDateTime.now(); // days_left 的基准（服务端时钟；deleted_at 由 DB 写入）
        jdbc.query(sql.toString(), rs -> {
            if (items.size() < limit) {
                OffsetDateTime deletedAt = rs.getObject("deleted_at", OffsetDateTime.class);
                items.add(new TaskListItem(
                        rs.getLong("id"),
                        rs.getString("status"),
                        TaskListSupport.originalNameOf(rs.getString("source_uri")),
                        rs.getObject("duration_sec") == null ? null : rs.getLong("duration_sec"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("error_code"),
                        TaskListSupport.keywordsOf(rs.getString("keywords")),
                        TaskListSupport.summaryHeadOf(rs.getString("summary")),
                        rs.getString("template"),
                        TaskListSupport.labelsOf(rs.getString("labels")),
                        rs.getBoolean("favorite"),
                        rs.getString("display_name"),
                        rs.getString("error_message"),
                        deletedAt,
                        daysLeftOf(deletedAt, now)));
            } else {
                hasMore[0] = true; // 第 limit+1 行存在 = 还有下一页
            }
        }, listArgs.toArray());

        Long nextBeforeId = hasMore[0] && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
        return new TaskListResponse(items, nextBeforeId, total == null ? 0L : total);
    }

    /** T4.96/C4：剩余保留天数 = max(0, retentionDays − 已过整天数)；仅 trash 模式（deleted_at 非空）有意义，
     *  非 trash（deleted_at=null）恒返回 null（键不省略）。 */
    private Integer daysLeftOf(OffsetDateTime deletedAt, OffsetDateTime now) {
        if (deletedAt == null) {
            return null;
        }
        long elapsedDays = Duration.between(deletedAt, now).toDays();
        return (int) Math.max(0, retentionDays - elapsedDays);
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
     * T4.96/B2（D-245）：**排除回收站任务**（deleted_at IS NULL）——台账侧栏徽标与默认列表
     * total 必须同口径，否则「徽标有数、点进去没有」；回收站计数走 trash=true 列表的 total。
     */
    public TaskCounts counts(long userId) {
        Map<String, Long> byStatus = new HashMap<>();
        jdbc.query("SELECT status, count(*) FROM task WHERE user_id = ? AND deleted_at IS NULL GROUP BY status",
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
}
