package com.meetingnotes.task;

import com.meetingnotes.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 每日配额（spec N8：超限 429 + Retry-After，不静默失败）。
 *
 * <p>消费点在任务创建时：used_minutes += ceil(duration/60)、used_tasks += 1。
 * 用「带守卫的原子 upsert」避免并发下超卖：守卫条件不满足时 INSERT 影响行数为 0。
 */
@Service
public class QuotaService {

    private final JdbcTemplate jdbc;
    private final long minutesPerDay;
    private final long tasksPerDay;

    public QuotaService(JdbcTemplate jdbc,
                        @Value("${app.quota.minutes-per-day}") long minutesPerDay,
                        @Value("${app.quota.tasks-per-day}") long tasksPerDay) {
        this.jdbc = jdbc;
        this.minutesPerDay = minutesPerDay;
        this.tasksPerDay = tasksPerDay;
    }

    /** @return 消费后的 (used_minutes, used_tasks)。超限抛 429 QUOTA_EXCEEDED。 */
    public long[] consume(long userId, int minutes) {
        if (minutes > minutesPerDay || 1 > tasksPerDay) {
            throw quotaExceeded();
        }
        Long usedMinutes = jdbc.query(
                """
                INSERT INTO quota (user_id, day, used_minutes, used_tasks)
                VALUES (?, CURRENT_DATE, ?, 1)
                ON CONFLICT (user_id, day) DO UPDATE SET
                    used_minutes = quota.used_minutes + EXCLUDED.used_minutes,
                    used_tasks   = quota.used_tasks + 1
                WHERE quota.used_minutes + ? <= ?
                  AND quota.used_tasks + 1 <= ?
                RETURNING used_minutes
                """,
                rs -> rs.next() ? rs.getLong(1) : null,
                userId, minutes, minutes, minutesPerDay, tasksPerDay);
        if (usedMinutes == null) {
            throw quotaExceeded();
        }
        return new long[]{usedMinutes, tasksPerDay};
    }

    private ApiException quotaExceeded() {
        return new ApiException(429, "QUOTA_EXCEEDED",
                "今日配额已用尽（" + minutesPerDay + " 分钟 / " + tasksPerDay + " 个任务），明天再试");
    }
}
