package com.meetingnotes.worker;

import com.meetingnotes.task.QueuePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * T4.100/C5：到期重试调度器。每轮取到期的 RETRYING 行，条件抢占为 PENDING 后投递；
 * 抢占用「status=RETRYING AND next_retry_at<=now()」的原子 UPDATE（影响 1 行才投递），
 * 多实例/并发下不会重复入队。投递失败回退 RETRYING 并恢复原到期值（下一轮再试）。
 * 整轮只 ERROR 不外抛（Redis/PG 抖动不能停摆调度器）。
 */
@Component
public class RetryDueSweeper {
    private static final Logger log = LoggerFactory.getLogger(RetryDueSweeper.class);
    static final int BATCH = 50;
    private static final String DUE_SQL = "SELECT id, next_retry_at FROM task "
            + "WHERE status='RETRYING' AND deleted_at IS NULL AND next_retry_at IS NOT NULL "
            + "AND next_retry_at <= now() ORDER BY next_retry_at ASC LIMIT 50";
    private static final String CLAIM_SQL = "UPDATE task SET status='PENDING', next_retry_at=NULL, "
            + "updated_at=now() WHERE id=? AND status='RETRYING' AND deleted_at IS NULL AND next_retry_at <= now()";

    private final JdbcTemplate jdbc;
    private final QueuePublisher queuePublisher;

    public RetryDueSweeper(JdbcTemplate jdbc, QueuePublisher queuePublisher) {
        this.jdbc = jdbc;
        this.queuePublisher = queuePublisher;
    }

    @Scheduled(fixedDelayString = "${app.retry.scan-interval-ms:5000}")
    public void sweep() {
        try {
            List<Map<String, Object>> due = jdbc.query(DUE_SQL,
                    (rs, i) -> Map.of("id", rs.getLong("id"), "next_retry_at", rs.getTimestamp("next_retry_at")));
            int enqueued = 0, failed = 0;
            for (Map<String, Object> row : due) {
                long id = (Long) row.get("id");
                Timestamp dueAt = (Timestamp) row.get("next_retry_at");
                if (jdbc.update(CLAIM_SQL, id) != 1) { continue; } // 并发/多实例已被别人抢占
                if (queuePublisher.tryPublishToTail(id)) {
                    enqueued++;
                } else {
                    // 投递失败：回退 RETRYING 并恢复原到期值（C7 不变量：RETRYING 必有 next_retry_at）
                    jdbc.update("UPDATE task SET status='RETRYING', next_retry_at=? WHERE id=? AND status='PENDING'",
                            dueAt, id);
                    failed++;
                }
            }
            log.info("重试调度完成：到期={} 入队={} 失败={}", due.size(), enqueued, failed);
        } catch (RuntimeException e) {
            log.error("重试调度异常（Redis/PG 抖动），本轮跳过下一轮再试", e);
        }
    }
}
