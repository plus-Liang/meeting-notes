package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    /** 认证体系接入前固定单用户（与 TaskService 一致）。 */
    private static final long DEFAULT_USER_ID = 1L;

    /** 批量上限（卡 2：1..20 个，去重）。 */
    static final int MAX_IDS = 20;

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final String queueKey;

    public TaskRetryService(JdbcTemplate jdbc,
                            StringRedisTemplate redis,
                            @Value("${app.worker.queue-key}") String queueKey) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.queueKey = queueKey;
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
                    rs -> rs.next() ? rs.getString(1) : null, id, DEFAULT_USER_ID);
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
            enqueue(id);
            retried.add(id);
            log.info("任务 {} 已批量重试复活（DEAD→PENDING）并重入队", id);
        }
        return new RetryOutcome(retried, skipped);
    }

    /** 投递 worker 队列；入队失败只记日志（任务停在 PENDING，T2.3 恢复机制兜底），不向上抛。 */
    private void enqueue(long taskId) {
        try {
            redis.opsForList().leftPush(queueKey, Long.toString(taskId));
        } catch (DataAccessException e) {
            log.error("任务 {} 重试入队失败（Redis 不可用），停在 PENDING，待 T2.3 恢复机制兜底", taskId, e);
        }
    }
}
