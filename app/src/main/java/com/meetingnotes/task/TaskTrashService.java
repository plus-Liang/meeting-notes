package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * T4.96/C3（v0.2 回收站，D-245）：恢复 / 彻底删除 / 清空回收站 / 到期清理。
 * 软删除见 {@link TaskDeleteService}（删除 = 置 deleted_at，子表与 MinIO 对象保留）。
 * restore = 清空 deleted_at（不退也不扣配额）；purge = 单事务物理删 4 表（**必须先校验
 * deleted_at IS NOT NULL，防误删在用任务**）+ 事务外 best-effort 删对象（复用
 * {@link TaskDeleteService#purgeObjectBestEffort}）；purgeExpired 的 cutoff 用 **Java 侧时间戳传参**
 * （禁 PG interval，H2/PG 都能跑）。配额全程不退不扣（D-115/D-245）。
 */
@Service
public class TaskTrashService {

    private static final Logger log = LoggerFactory.getLogger(TaskTrashService.class);

    /** 批量恢复上限（口径同批量删除）。 */
    static final int MAX_IDS = 100;

    /** retention-days 非法（<1）时的回退值（<1 一次误配 = 永久删除，绝不接受）。 */
    static final int DEFAULT_RETENTION_DAYS = 30;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TaskDeleteService taskDeleteService;
    private final Clock clock;
    private final int retentionDays;

    /** 测试构造（可注入固定 Clock 与保留期；retention <1 与生产同口径回退 30）。 */
    public TaskTrashService(JdbcTemplate jdbc, TransactionTemplate tx,
                            TaskDeleteService taskDeleteService, Clock clock, int retentionDays) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.taskDeleteService = taskDeleteService;
        this.clock = clock;
        this.retentionDays = sanitizeRetention(retentionDays);
    }

    /** Spring 构造：retentionDays 走配置（<1 → WARN 回退 30，T4.78/T4.90 口径）。 */
    @Autowired
    public TaskTrashService(JdbcTemplate jdbc, TransactionTemplate tx,
                            TaskDeleteService taskDeleteService,
                            @Value("${app.trash.retention-days:30}") int retentionDays) {
        this(jdbc, tx, taskDeleteService, Clock.systemUTC(), sanitizeRetention(retentionDays));
    }

    static int sanitizeRetention(int configured) {
        return configured < 1 ? DEFAULT_RETENTION_DAYS : configured;
    }

    /** 恢复单条：清空 deleted_at（内容/对象原样都在）。非回收站任务或不存在/非本人 → 404。 */
    public void restore(long taskId) {
        int updated = jdbc.update(
                "UPDATE task SET deleted_at = NULL, updated_at = now() WHERE id = ? AND user_id = ? AND deleted_at IS NOT NULL",
                taskId, UserContext.current());
        if (updated == 0) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + taskId + " 不存在或不在回收站");
        }
        log.info("任务 {} 已从回收站恢复（deleted_at 清空；配额不退不扣，D-245）", taskId);
    }

    /** 批量恢复：1..100、去重保序、逐条独立事务（口径同 deleteBatch），@return 成功恢复的 id。 */
    public List<Long> restoreBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS) {
            throw new ApiException(400, "INVALID_PARAM", "ids 必须是 1.." + MAX_IDS + " 个任务 id");
        }
        List<Long> restored = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            if (id == null || id <= 0) {
                continue;
            }
            try {
                tx.executeWithoutResult(status -> restore(id));
                restored.add(id);
            } catch (ApiException e) {
                log.info("任务 {} 恢复跳过：{}", id, e.getCode());
            }
        }
        return restored;
    }

    /** 彻底删除单条（当前用户、仅回收站任务）。@return false = 不存在/非本人/不在回收站（controller → 404）。 */
    public boolean purge(long taskId) {
        String sourceUri = taskDeleteService.purgeInTx(taskId, UserContext.current());
        if (sourceUri == null) {
            return false;
        }
        taskDeleteService.purgeObjectBestEffort(taskId, sourceUri);
        return true;
    }

    /** 清空当前用户全部回收站任务（逐条独立事务，部分失败不互相牵连）。@return 彻底删除条数。 */
    public int purgeAll() {
        List<Long> ids = jdbc.query(
                "SELECT id FROM task WHERE user_id = ? AND deleted_at IS NOT NULL ORDER BY id",
                (rs, i) -> rs.getLong(1), UserContext.current());
        int purged = 0;
        for (Long id : ids) {
            if (purge(id)) {
                purged++;
            }
        }
        log.info("清空回收站：{} 条任务全部彻底删除", purged);
        return purged;
    }

    /** 到期清理（TaskTrashSweeper 调用，跨用户无用户上下文）：删 deleted_at < cutoff 的行，最多 limit 条。 */
    public int purgeExpired(int limit) {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(retentionDays);
        List<Long> ids = jdbc.query(
                "SELECT id FROM task WHERE deleted_at IS NOT NULL AND deleted_at < ? ORDER BY deleted_at LIMIT ?",
                (rs, i) -> rs.getLong(1), cutoff, limit);
        int purged = 0;
        for (Long id : ids) {
            String sourceUri = taskDeleteService.purgeInTx(id, null); // 系统清扫：不加 user 条件（回收站行必须被清到）
            if (sourceUri != null) {
                taskDeleteService.purgeObjectBestEffort(id, sourceUri);
                purged++;
            }
        }
        if (!ids.isEmpty()) {
            log.info("回收站到期清理：到期 {} 条，彻底删除 {} 条（cutoff={}，retentionDays={}）",
                    ids.size(), purged, cutoff, retentionDays);
        }
        return purged;
    }

}
