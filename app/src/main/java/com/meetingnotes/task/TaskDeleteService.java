package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 任务删除（T4.21 / D-115）：单条 / 批量（1..100，去重，逐条部分成功口径同 T4.1 批量重试）/
 * 清空失败任务（当前用户全部 DEAD，不限已加载行）。
 *
 * <p>事务（TransactionTemplate 显式包裹，批量循环每条独立事务 → 部分成功不互相牵连）：
 * 按 task_id 依次删 webhook_delivery → dead_letter → task_result → task（FK 顺序）。
 * 可删状态仅 DONE/DEAD；PENDING/RUNNING/RETRYING → TASK_BUSY（409）；
 * 不存在/非本人 → NOT_FOUND（404）。状态判断与删除同事务，防 worker 竞态。
 *
 * <p>MinIO 对象删除 **best-effort**：DB 行删除成功后逐个调 S3，失败只 WARN 记 taskId，不回滚 DB 行。
 * **配额不退还**（D-115 口径，错误信息与文档写明）。
 */
@Service
public class TaskDeleteService {

    private static final Logger log = LoggerFactory.getLogger(TaskDeleteService.class);

    /** 认证体系接入前固定单用户（与 TaskService 一致）。 */
    private static final long DEFAULT_USER_ID = 1L;

    /** 批量删除上限（卡口径 1..100，去重）。 */
    static final int MAX_IDS = 100;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final S3Client s3;
    private final String bucket;

    public TaskDeleteService(JdbcTemplate jdbc,
                             TransactionTemplate tx,
                             S3Client s3,
                             @Value("${app.storage.bucket}") String bucket) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.s3 = s3;
        this.bucket = bucket;
    }

    /** 逐条结果（snake_case 响应：reason 只取固定值 NOT_FOUND / TASK_BUSY，成功为 null）。 */
    public record DeleteOutcome(long id, boolean deleted, String reason, String sourceUri) {
    }

    /**
     * 单条删除（事务内判断状态 + 删行）。
     *
     * @return deleted=false 时 reason ∈ {NOT_FOUND, TASK_BUSY}
     */
    public DeleteOutcome deleteOne(long taskId) {
        DeleteOutcome outcome = tx.execute(status -> deleteOneInTx(taskId));
        return outcome == null ? new DeleteOutcome(taskId, false, "NOT_FOUND", null) : outcome;
    }

    /** 批量删除：1..100 条、去重（保序）、逐条独立事务，不整体失败。 */
    public List<DeleteOutcome> deleteBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS) {
            throw new ApiException(400, "INVALID_PARAM", "ids 必须是 1.." + MAX_IDS + " 个任务 id");
        }
        List<DeleteOutcome> results = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            if (id == null || id <= 0) {
                results.add(new DeleteOutcome(id == null ? -1L : id, false, "NOT_FOUND", null));
                continue;
            }
            results.add(deleteOne(id));
        }
        return results;
    }

    /** 清空当前用户全部 DEAD 任务（不限已加载行；RETRYING 不算失败不删）。@return 实际删除条数。 */
    public int deleteAllFailed() {
        List<Long> ids = jdbc.query(
                "SELECT id FROM task WHERE user_id = ? AND status = 'DEAD' ORDER BY id",
                (rs, i) -> rs.getLong(1), DEFAULT_USER_ID);
        int deleted = 0;
        for (Long id : ids) {
            DeleteOutcome outcome = deleteOne(id);
            if (outcome.deleted()) {
                deleted++;
            }
        }
        log.info("清空失败任务：DEAD {} 条，删除 {} 条", ids.size(), deleted);
        return deleted;
    }

    /** MinIO 对象 best-effort 删除：失败只 WARN 记 taskId，不抛（DB 行已删，不回滚）。 */
    public void purgeObjectBestEffort(long taskId, String sourceUri) {
        if (sourceUri == null || !sourceUri.startsWith("s3://" + bucket + "/")) {
            return;
        }
        String key = sourceUri.substring(("s3://" + bucket + "/").length());
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException e) {
            log.warn("任务 {} 的 MinIO 对象删除失败（best-effort，DB 行保留）：key={}", taskId, key, e);
        }
    }

    /** 事务内单条删除：状态判断与删除同事务（防 worker 竞态把 RUNNING 任务删成孤儿）。 */
    private DeleteOutcome deleteOneInTx(long taskId) {
        String status = jdbc.query(
                "SELECT status FROM task WHERE id = ? AND user_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, taskId, DEFAULT_USER_ID);
        if (status == null) {
            return new DeleteOutcome(taskId, false, "NOT_FOUND", null);
        }
        if ("PENDING".equals(status) || "RUNNING".equals(status) || "RETRYING".equals(status)) {
            return new DeleteOutcome(taskId, false, "TASK_BUSY", null);
        }
        String sourceUri = jdbc.query(
                "SELECT COALESCE(source_uri, '') FROM task WHERE id = ?",
                rs -> { rs.next(); return rs.getString(1); }, taskId);
        jdbc.update("DELETE FROM webhook_delivery WHERE task_id = ?", taskId);
        jdbc.update("DELETE FROM dead_letter WHERE task_id = ?", taskId);
        jdbc.update("DELETE FROM task_result WHERE task_id = ?", taskId);
        jdbc.update("DELETE FROM task WHERE id = ?", taskId);
        log.info("任务 {}（{}）已删除（task/task_result/dead_letter/webhook_delivery 同事务清理；配额不退还）",
                taskId, status);
        return new DeleteOutcome(taskId, true, null, sourceUri);
    }
}
