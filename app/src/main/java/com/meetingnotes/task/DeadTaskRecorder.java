package com.meetingnotes.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T4.87 C3：置 DEAD 的单一入口（worker 重试耗尽 / 批量重试入队失败回滚两处共用）。
 * 单事务内：① 置 DEAD + error_code ② 写 dead_letter ③ {@link QuotaService#refundForTask(long)} 退分钟数。
 * 退款幂等（quota_refunded 原子翻转）：已退过返回 false，不重复退、不抛；DEAD 与 dead_letter 仍然成立。
 * 不变量由「同事务」保证：DEAD 落库与 quota_refunded=TRUE 要么一起生效要么一起回滚。
 */
@Component
public class DeadTaskRecorder {

    private final JdbcTemplate jdbc;
    private final QuotaService quotaService;
    private final TransactionTemplate transactionTemplate;

    public DeadTaskRecorder(JdbcTemplate jdbc, QuotaService quotaService, TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.quotaService = quotaService;
        this.transactionTemplate = transactionTemplate;
    }

    /** @return 本次是否真的退了分钟数（已退过/无需退 = false）。DEAD + dead_letter 不受退款结果影响。 */
    public boolean markDead(long taskId, String code, String reason) {
        Boolean refunded = transactionTemplate.execute(status -> {
            // T4.100/C7：离开 RETRYING（本类由 worker 重试耗尽调用）必须清 next_retry_at，维持「RETRYING ⇒ 非空」不变量。
            jdbc.update("UPDATE task SET status = 'DEAD', error_code = ?, next_retry_at = NULL, updated_at = now() WHERE id = ?",
                    code, taskId);
            jdbc.update("INSERT INTO dead_letter (task_id, reason) VALUES (?, ?)", taskId, reason);
            return quotaService.refundForTask(taskId);
        });
        return Boolean.TRUE.equals(refunded);
    }
}
