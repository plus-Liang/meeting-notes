package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
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
 * 任务删除（T4.21 / D-115；T4.96/C2 起为**软删除**）：单条 / 批量（1..100，去重，逐条部分成功口径同
 * T4.1 批量重试）/ 清空失败任务（当前用户全部 DEAD 且未在回收站）。
 *
 * <p>T4.96（D-245）：删除 = {@code UPDATE task SET deleted_at = now()}（进回收站，保留期内可恢复），
 * **不再删** webhook_delivery / dead_letter / task_result / task，**不再删 MinIO 对象**——
 * 恢复时内容与原音频必须还在。可删状态仍仅 DONE/DEAD（PENDING/RUNNING/RETRYING → TASK_BUSY 409）；
 * FOR UPDATE 行锁防 worker 竞态（T4.21 验收 P3 语义不变）。**配额仍不退**（D-115）。
 * 物理删除收敛到 {@link TaskTrashService#purge}（4 表 DELETE + best-effort 删对象）。
 */
@Service
public class TaskDeleteService {

    private static final Logger log = LoggerFactory.getLogger(TaskDeleteService.class);


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

    /** 清空当前用户全部 DEAD 任务（T4.96/C2-5：只处理 {@code deleted_at IS NULL} 的行——回收站任务
     *  由回收站出口清理，不在本入口重复处置）。@return 实际删除条数。
     *
     *  <p>T4.96：删除 = 软删（进回收站），**不再** best-effort 删 MinIO 对象（回收站要能恢复）。</p>
     */
    public int deleteAllFailed() {
        List<Long> ids = jdbc.query(
                "SELECT id FROM task WHERE user_id = ? AND status = 'DEAD' AND deleted_at IS NULL ORDER BY id",
                (rs, i) -> rs.getLong(1), UserContext.current());
        int deleted = 0;
        for (Long id : ids) {
            DeleteOutcome outcome = deleteOne(id);
            if (outcome.deleted()) {
                deleted++;
            }
        }
        log.info("清空失败任务：DEAD {} 条，进回收站 {} 条（软删除，配额不退还）", ids.size(), deleted);
        return deleted;
    }

    /** MinIO 对象 best-effort 删除：失败只 WARN 记 taskId，不抛（T4.96 起仅 {@link TaskTrashService#purge} 使用）。 */
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

    /** T4.96/C3：事务内物理删 4 表（webhook_delivery → dead_letter → task_result → task，FK 顺序）——
     *  回收站彻底删除/到期清理的**唯一**物理删入口。FOR UPDATE 校验归属（userId 可空 = 系统清扫）
     *  **且 deleted_at IS NOT NULL**（双重防误删在用任务）。
     *  @return source_uri（供事务外 best-effort 删对象）；null = 条件不满足 0 行（幂等，不 500）。 */
    public String purgeInTx(long taskId, Long userId) {
        return tx.execute(status -> {
            String sourceUri = userId == null
                    ? jdbc.query("SELECT COALESCE(source_uri, '') FROM task WHERE id = ? AND deleted_at IS NOT NULL FOR UPDATE",
                            rs -> rs.next() ? rs.getString(1) : null, taskId)
                    : jdbc.query("SELECT COALESCE(source_uri, '') FROM task WHERE id = ? AND user_id = ? AND deleted_at IS NOT NULL FOR UPDATE",
                            rs -> rs.next() ? rs.getString(1) : null, taskId, userId);
            if (sourceUri == null) {
                return null; // 已被并发清掉 / 不在回收站 / 非本人
            }
            jdbc.update("DELETE FROM webhook_delivery WHERE task_id = ?", taskId);
            jdbc.update("DELETE FROM dead_letter WHERE task_id = ?", taskId);
            jdbc.update("DELETE FROM task_result WHERE task_id = ?", taskId);
            jdbc.update("DELETE FROM task WHERE id = ?", taskId);
            log.info("任务 {} 已彻底删除（4 表同事务物理删 + 对象 best-effort；配额不退，D-115/D-245）", taskId);
            return sourceUri;
        });
    }

    /** 事务内单条软删除：状态判断与打删除时间戳同事务，状态读加 FOR UPDATE 行锁
     * （T4.21 验收 P3：读-改串行化防竞态，语义不变）。
     * T4.96/C2：不再删 4 张子表、不再删 MinIO 对象——回收站要能整条恢复。
     * T4.102：SELECT/UPDATE 都加 AND deleted_at IS NULL——已在回收站的任务再删走 NOT_FOUND
     * （控制器映射 404），且 deleted_at 不被刷新（30 天计时不会被二次删除重置）。 */
    private DeleteOutcome deleteOneInTx(long taskId) {
        String status = jdbc.query(
                "SELECT status FROM task WHERE id = ? AND user_id = ? AND deleted_at IS NULL FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null, taskId, UserContext.current());
        if (status == null) {
            return new DeleteOutcome(taskId, false, "NOT_FOUND", null);
        }
        if ("PENDING".equals(status) || "RUNNING".equals(status) || "RETRYING".equals(status)) {
            return new DeleteOutcome(taskId, false, "TASK_BUSY", null);
        }
        String sourceUri = jdbc.query(
                "SELECT COALESCE(source_uri, '') FROM task WHERE id = ?",
                rs -> { rs.next(); return rs.getString(1); }, taskId);
        jdbc.update("UPDATE task SET deleted_at = now(), updated_at = now() WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
                taskId, UserContext.current());
        log.info("任务 {}（{}）已进回收站（软删除：deleted_at 置 now，子表与对象保留；配额不退还，D-115）",
                taskId, status);
        return new DeleteOutcome(taskId, true, null, sourceUri);
    }
}
