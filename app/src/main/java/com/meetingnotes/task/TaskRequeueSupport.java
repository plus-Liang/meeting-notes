package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.worker.StalePendingQueries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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

    public TaskRequeueSupport(JdbcTemplate jdbc, QueuePublisher queuePublisher,
                              StalePendingQueries stalePendingQueries) {
        this.jdbc = jdbc;
        this.queuePublisher = queuePublisher;
        this.stalePendingQueries = stalePendingQueries;
    }

    /** DEAD 复活两连（D-049）：重置计数/状态/错误码 + 清死信。SQL 与原四处逐字一致。 */
    private void reviveDead(long id) {
        jdbc.update("""
                UPDATE task SET attempts = 0, status = 'PENDING', error_code = NULL, updated_at = now()
                WHERE id = ?
                """, id);
        jdbc.update("DELETE FROM dead_letter WHERE task_id = ?", id);
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
            jdbc.update("UPDATE task SET status = 'DEAD', error_code = 'QUEUE_UNAVAILABLE', updated_at = now() WHERE id = ?",
                    id);
            jdbc.update("INSERT INTO dead_letter (task_id, reason) VALUES (?, ?)",
                    id, "批量重试入队失败（Redis 不可用），已回滚为 DEAD；队列恢复后重新发起批量重试即可");
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
