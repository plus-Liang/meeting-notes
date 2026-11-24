package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;

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
    private final TransactionTemplate transactionTemplate;

    public QuotaService(JdbcTemplate jdbc,
                        @Value("${app.quota.minutes-per-day}") long minutesPerDay,
                        @Value("${app.quota.tasks-per-day}") long tasksPerDay,
                        TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.minutesPerDay = minutesPerDay;
        this.tasksPerDay = tasksPerDay;
        this.transactionTemplate = transactionTemplate;
    }

    /** @return 消费后的 (used_minutes, used_tasks)。超限抛 429 QUOTA_EXCEEDED。 */
    public long[] consume(long userId, int minutes) {
        if (minutes > minutesPerDay || 1 > tasksPerDay) {
            throw quotaExceeded();
        }
        Long[] used = jdbc.query(
                """
                INSERT INTO quota (user_id, day, used_minutes, used_tasks)
                VALUES (?, CURRENT_DATE, ?, 1)
                ON CONFLICT (user_id, day) DO UPDATE SET
                    used_minutes = quota.used_minutes + EXCLUDED.used_minutes,
                    used_tasks   = quota.used_tasks + 1
                WHERE quota.used_minutes + ? <= ?
                  AND quota.used_tasks + 1 <= ?
                RETURNING used_minutes, used_tasks
                """,
                rs -> rs.next() ? new Long[]{rs.getLong(1), rs.getLong(2)} : null,
                userId, minutes, minutes, minutesPerDay, tasksPerDay);
        if (used == null) {
            throw quotaExceeded();
        }
        return new long[]{used[0], used[1]};
    }

    private ApiException quotaExceeded() {
        return new ApiException(429, "QUOTA_EXCEEDED",
                "今日配额已用尽（" + minutesPerDay + " 分钟 / " + tasksPerDay
                        + " 个任务；失败任务的分钟数会自动退还，删除不退，重试会重新计入），明天再试");
    }

    /** T4.87 C2：复活/重试前重新消费（与创建同口径）：读 task 行拿 user_id/duration_sec → consume。
     *  超限 429 上抛 ⇒ 调用方事务回滚 ⇒ 任务保持 DEAD/refunded=TRUE（堵「失败退款=免费重试」漏洞）。 */
    public void consumeForTask(long taskId) {
        long[] row = jdbc.query("SELECT user_id, duration_sec FROM task WHERE id = ?",
                rs -> rs.next() ? new long[]{rs.getLong(1), rs.getInt(2)} : null, taskId);
        if (row == null) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务不存在");
        }
        consume(row[0], ceilMinutes((int) row[1]));
    }

    /** T4.87 C2：终局失败退还分钟数（次数不退）。幂等：quota_refunded 原子翻转，已退过返回 false 不抛。
     *  退款落到任务创建日（created_at::date），GREATEST(0,…) 保证不为负。单事务（两段 SQL）。 */
    public boolean refundForTask(long taskId) {
        Boolean refunded = transactionTemplate.execute(status -> {
            RefundTarget target = jdbc.query("""
                    UPDATE task SET quota_refunded = TRUE
                    WHERE id = ? AND quota_refunded = FALSE
                    RETURNING user_id, duration_sec, CAST(created_at AS DATE)
                    """,
                    rs -> rs.next() ? new RefundTarget(rs.getLong(1), rs.getInt(2), rs.getDate(3)) : null,
                    taskId);
            if (target == null) {
                return false; // 已退过 / 不存在：不重复退、不抛
            }
            int minutes = ceilMinutes(target.durationSec());
            if (minutes > 0) {
                jdbc.update("UPDATE quota SET used_minutes = GREATEST(0, used_minutes - ?) "
                        + "WHERE user_id = ? AND day = ?", minutes, target.userId(), target.day());
            }
            return true;
        });
        return Boolean.TRUE.equals(refunded);
    }

    /** 分钟数 = ceil(duration_sec/60)；null/0（PASTE）= 0。 */
    static int ceilMinutes(int durationSec) {
        return durationSec <= 0 ? 0 : (durationSec + 59) / 60;
    }

    private record RefundTarget(long userId, int durationSec, Date day) {
    }

    /** 设置页展示（T4.1）：今日已用（无行 = 今日未消费，返回 0/0，与 CURRENT_DATE 口径一致）。 */
    public record Usage(long usedMinutes, long usedTasks) {
    }

    public long minutesPerDay() {
        return minutesPerDay;
    }

    public long tasksPerDay() {
        return tasksPerDay;
    }

    public Usage currentUsage(long userId) {
        return jdbc.query("""
                SELECT used_minutes, used_tasks FROM quota WHERE user_id = ? AND day = CURRENT_DATE
                """, rs -> rs.next() ? new Usage(rs.getLong(1), rs.getLong(2)) : new Usage(0, 0), userId);
    }
}
