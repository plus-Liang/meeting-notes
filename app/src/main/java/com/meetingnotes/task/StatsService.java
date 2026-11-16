package com.meetingnotes.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用统计（T4.25，当前用户只读聚合，无新请求/无落库）：
 * <ul>
 *   <li>{@code tasks}：状态计数——复用 {@link TaskQueryService#counts}，与 GET /tasks/counts 同口径；</li>
 *   <li>{@code duration_sec_total}：全部任务 duration_sec 合计（媒体时长上传时已知；PASTE 任务为 null 不计入）；</li>
 *   <li>{@code success_rate} = done/(done+dead)，保留 3 位小数；分母 0（没有任何 DONE/DEAD 任务）→ null；</li>
 *   <li>{@code avg_processing_ratio}：只统计 DONE 且 duration_sec&gt;0，
 *       = AVG(EXTRACT(EPOCH FROM (updated_at - created_at)) / NULLIF(duration_sec, 0))，
 *       保留 3 位小数，无数据 → null。<b>含排队等待，不是 RTF</b>——界面文案必须写
 *       「平均处理倍速（含排队，近似值）」（D-125 同源的诚实口径，不误导）；</li>
 *   <li>{@code tiers}：DONE 任务按 task_result.ai_tier 分组（CLOUD/LOCAL/EXTRACTIVE；
 *       null——AI 未启用/旧任务——单列 "null"），仅统计 DONE（结构化只发生在 DONE，PENDING 无结果行）。</li>
 * </ul>
 */
@Service
public class StatsService {

    private final JdbcTemplate jdbc;
    private final TaskQueryService taskQueryService;

    /** 任务状态计数（与 /tasks/counts 同口径）。 */
    public record TaskStats(long total, long active, long done, long dead) {
    }

    /** 统计响应（snake_case 对外契约，键集固定；success_rate/avg_processing_ratio 无数据为 null）。 */
    public record StatsResponse(TaskStats tasks, long durationSecTotal, Double successRate,
                                Double avgProcessingRatio, Map<String, Long> tiers) {
    }

    public StatsService(JdbcTemplate jdbc, TaskQueryService taskQueryService) {
        this.jdbc = jdbc;
        this.taskQueryService = taskQueryService;
    }

    public StatsResponse stats(long userId) {
        TaskQueryService.TaskCounts c = taskQueryService.counts(userId);
        TaskStats tasks = new TaskStats(c.total(), c.active(), c.done(), c.dead());

        Long durationTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(duration_sec), 0) FROM task WHERE user_id = ?",
                Long.class, userId);
        Double ratio = jdbc.queryForObject(
                "SELECT AVG(EXTRACT(EPOCH FROM (updated_at - created_at)) / NULLIF(duration_sec, 0)) "
                        + "FROM task WHERE user_id = ? AND status = 'DONE' AND duration_sec > 0",
                Double.class, userId);

        Map<String, Long> tiers = new LinkedHashMap<>();
        jdbc.query("SELECT r.ai_tier, count(*) FROM task t LEFT JOIN task_result r ON r.task_id = t.id "
                        + "WHERE t.user_id = ? AND t.status = 'DONE' GROUP BY r.ai_tier ORDER BY r.ai_tier",
                rs -> {
                    String tier = rs.getString(1); /* 只取一次（映射 null 键用局部变量，不重复读列） */
                    tiers.put(tier == null ? "null" : tier, rs.getLong(2));
                }, userId);

        return new StatsResponse(tasks,
                durationTotal == null ? 0L : durationTotal,
                c.done() + c.dead() > 0 ? round3(c.done() / (double) (c.done() + c.dead())) : null,
                ratio == null ? null : round3(ratio),
                tiers);
    }

    /** 保留 3 位小数（卡口径）。 */
    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
