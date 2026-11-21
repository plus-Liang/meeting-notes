package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 批量重试（T4.1）：只处理 **DEAD** 任务，复活口径与"重传触发复活"（D-049）**逐条一致**——
 * attempts=0 / status='PENDING' / error_code=NULL → 清死信 → 重入队 Redis；**不消费配额**（与重传一致）。
 * 不许新造状态语义（卡 2）：复活 SQL 与 {@code TaskService} 的 DEAD 复活路径逐字相同。
 *
 * <p>非 DEAD / 不存在 / 非本人 → 逐条记原因（NOT_DEAD / NOT_FOUND），**不整体失败**；
 * 非法 body（空 / >{@value #MAX_IDS} 个）→ 400 INVALID_PARAM（非数字由 Jackson 映射同码）。
 * 入参去重（LinkedHashSet 保序）。
 */
@Service
public class TaskRetryService {

    private static final Logger log = LoggerFactory.getLogger(TaskRetryService.class);


    /** 批量上限（卡 2：1..20 个，去重）。 */
    static final int MAX_IDS = 20;

    private final JdbcTemplate jdbc;
    private final QueuePublisher queuePublisher;

    public TaskRetryService(JdbcTemplate jdbc, QueuePublisher queuePublisher) {
        this.jdbc = jdbc;
        this.queuePublisher = queuePublisher;
    }

    /** 逐条跳过原因（snake_case 响应：reason 只取固定值，不外泄内部细节）。 */
    public record Skipped(Long id, String reason) {
    }

    /** 重试结果：retried = 已复活重入队的 DEAD id；skipped = 未处理明细。 */
    public record RetryOutcome(List<Long> retried, List<Skipped> skipped) {
    }

    public RetryOutcome retry(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS) {
            throw new ApiException(400, "INVALID_PARAM", "ids 必须是 1.." + MAX_IDS + " 个任务 id");
        }
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        List<Long> retried = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (Long id : distinct) {
            if (id == null || id <= 0) {
                skipped.add(new Skipped(id, "NOT_FOUND"));
                continue;
            }
            String status = jdbc.query(
                    "SELECT status FROM task WHERE id = ? AND user_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, id, UserContext.current());
            if (status == null) {
                skipped.add(new Skipped(id, "NOT_FOUND"));
                continue;
            }
            if (!"DEAD".equals(status)) {
                skipped.add(new Skipped(id, "NOT_DEAD"));
                continue;
            }
            // 复活三连与 TaskService 的 DEAD 重传复活逐字同口径（D-049：重置计数 + 清死信 + 重入队）
            jdbc.update("""
                    UPDATE task SET attempts = 0, status = 'PENDING', error_code = NULL, updated_at = now()
                    WHERE id = ?
                    """, id);
            jdbc.update("DELETE FROM dead_letter WHERE task_id = ?", id);
            try {
                queuePublisher.publish(id);
            } catch (ApiException e) {
                // T4.52 B2：复活只完成一半（已 PENDING、死信已删、队列没投）——回滚成 DEAD 并补回死信，
                // 保证客户端重发仍能命中「DEAD→复活」路径，再把 503 抛给调用方；绝不许 retried 为空却 200
                jdbc.update("UPDATE task SET status = 'DEAD', error_code = 'QUEUE_UNAVAILABLE', updated_at = now() WHERE id = ?",
                        id);
                jdbc.update("INSERT INTO dead_letter (task_id, reason) VALUES (?, ?)",
                        id, "批量重试入队失败（Redis 不可用），已回滚为 DEAD；队列恢复后重新发起批量重试即可");
                throw e;
            }
            retried.add(id);
            log.info("任务 {} 已批量重试复活（DEAD→PENDING）并重入队", id);
        }
        return new RetryOutcome(retried, skipped);
    }
}
