package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.worker.StalePendingQueries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T4.60 B2：DEAD 复活 + PENDING 自愈 SQL 的单一实现（此前 TaskService/LinkTaskService/TextTaskService
 * 三处逐字重复、TaskRetryService 一处带回滚变体——R4 审查项）。
 *
 * <p>复活口径（D-049，SQL 与原四处逐字一致）：attempts=0 / status='PENDING' / error_code=NULL →
 * 清死信 → 重入队（队头，既有语义）。入队失败抛 503 QUEUE_UNAVAILABLE：
 * <ul>
 *   <li>{@link #reviveDeadThenPublish(long)}：无回滚——任务停在 PENDING，靠幂等重提/启动恢复自愈
 *       （TaskService/LinkTaskService/TextTaskService 的 DEAD 分支口径）；</li>
 *   <li>{@link #reviveDeadThenPublishOrRollback(long)}：批量重试专用——回滚成 DEAD 并补回死信后再抛
 *       （不许半复活卡死，TaskRetryService 口径）。</li>
 * </ul>
 * {@link #publishPending(long, String)}：幂等命中 PENDING 的重投；logTag 传各调用方原日志文案
 * （措辞各处不同——"重传/重提"，不统一成一句空话）。
 */
@Component
public class TaskRequeueSupport {

    private static final Logger log = LoggerFactory.getLogger(TaskRequeueSupport.class);

    private final JdbcTemplate jdbc;
    private final QueuePublisher queuePublisher;
    private final StalePendingQueries stalePendingQueries;
    private final QuotaService quotaService;
    private final DeadTaskRecorder deadTaskRecorder;
    private final TransactionTemplate transactionTemplate;

    public TaskRequeueSupport(JdbcTemplate jdbc, QueuePublisher queuePublisher,
                              StalePendingQueries stalePendingQueries, QuotaService quotaService,
                              DeadTaskRecorder deadTaskRecorder, TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.queuePublisher = queuePublisher;
        this.stalePendingQueries = stalePendingQueries;
        this.quotaService = quotaService;
        this.deadTaskRecorder = deadTaskRecorder;
        this.transactionTemplate = transactionTemplate;
    }

    /** T4.87 C4：DEAD 复活 = 单事务「重置 + quota_refunded=FALSE + 重新消费 + 清死信」。
     *  重新消费与创建同口径（ceil 分钟 + 1 次）；超限 429 上抛 ⇒ 事务回滚 ⇒ 任务保持 DEAD/refunded=TRUE
     *  （堵「失败退款 → 免费重试」漏洞）。RETURNING 拿 user_id/duration_sec 供消费。 */
    private void reviveDead(long id) {
        transactionTemplate.executeWithoutResult(status -> {
            long[] row = jdbc.query("""
                    UPDATE task SET attempts = 0, status = 'PENDING', error_code = NULL,
                           quota_refunded = FALSE, updated_at = now()
                    WHERE id = ?
                    RETURNING user_id, duration_sec
                    """,
                    rs -> rs.next() ? new long[]{rs.getLong(1), rs.getInt(2)} : null, id);
            if (row == null) {
                throw new ApiException(404, "TASK_NOT_FOUND", "任务不存在");
            }
            quotaService.consume(row[0], QuotaService.ceilMinutes((int) row[1]));
            jdbc.update("DELETE FROM dead_letter WHERE task_id = ?", id);
        });
    }

    /** DEAD 重传/重提复活 + 重投（队头，既有语义）；入队失败 503 上抛——任务停在 PENDING，自愈前提成立。 */
    public void reviveDeadThenPublish(long id) {
        reviveDead(id);
        queuePublisher.publish(id);
    }

    /** 批量重试口径：复活后入队失败 → 回滚成 DEAD 并补回死信（不许半复活卡死）→ 503 原样上抛。 */
    public void reviveDeadThenPublishOrRollback(long id) {
        reviveDead(id);
        try {
            queuePublisher.publish(id);
        } catch (ApiException e) {
            // T4.87 C3：回滚置 DEAD 走单一入口（同事务退款；复活刚消费过 refunded=FALSE，此处退回去）
            deadTaskRecorder.markDead(id, "QUEUE_UNAVAILABLE",
                    "批量重试入队失败（Redis 不可用），已回滚为 DEAD；队列恢复后重新发起批量重试即可");
            throw e;
        }
    }

    /** 幂等命中 PENDING 的重投（T4.50/T4.52 自愈）；logTag = 调用方原日志文案（措辞各处不同，保留）。
     * T4.67 S9：成功入队后 touch（与 Sweeper「先投后 touch」口径一致）——否则用户重提一个停很久的
     * PENDING 后，60s 内清扫器仍会当「陈旧」再投一遍，把「用户重提」记成「清扫自愈」。失败不 touch。 */
    public void publishPending(long id, String logTag) {
        queuePublisher.publish(id);
        stalePendingQueries.touch(id);
        log.info("{}", logTag);
    }
}
