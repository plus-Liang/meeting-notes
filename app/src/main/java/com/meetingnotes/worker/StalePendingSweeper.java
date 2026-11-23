package com.meetingnotes.worker;

import com.meetingnotes.task.QueuePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Set;

/**
 * T4.61：运行中实例的「陈旧 PENDING」周期清扫——Redis 故障期间提交的任务（行已提交、队列未投）
 * 在无人重提、也不重启进程时，靠本清扫每轮回到队列，避免无限期停在「排队中」。
 *
 * <p>判据与 {@link StartupRecovery} 的启动恢复一致：status='PENDING' 且 updated_at 早于
 * stale-minutes（默认 2 分钟，挡住"刚创建还没入队"的行，不与正常提交流程抢）。
 * 重投复用 {@link QueuePublisher#publishToTail(long)}（队尾 rightPush，单一入队实现，不自建第 5 份 push；
 * T4.67-fix F5：恢复任务按「旧→新」追加到队尾——先卡住的先处理且不插队）；
 * 成功入队后刷新 updated_at，下一轮不再重复入队。
 *
 * <p>顺序语义（T4.62 S2，与 StartupRecovery 相反、不许合并）：**先投递成功、再 touch**——
 * 投递失败绝不刷新 updated_at，下一轮再兜底。判据/刷新语句在 {@link StalePendingQueries}。
 *
 * <p>T4.79/P-02：同一调度里追加「僵尸回执」清扫——worker BLPOP 取出即 ZADD 回执（processingKey，
 * score=取出毫秒），SIGKILL/OOM 后回执残留且行停在 RUNNING；回执超过 visibility-timeout-sec 的候选
 * 按 DB 状态分派：行没了只 ZREM；RUNNING 且心跳新鲜（updated_at 在 heartbeat×3 内）跳过；RUNNING 且
 * 心跳陈旧 = 死消费者，条件 UPDATE 原子抢回收后重投；PENDING/RETRYING 重投；DONE/DEAD 只 ZREM。
 * 不新增调度器（禁止项 3）；两类清扫互不干扰，单条失败只 warn 不影响其它条。
 *
 * <p>多实例口径（T4.67-fix F3，事实化）：单实例部署为当前口径（D-061），重复入队的副本无害；多实例下靠
 * worker 侧的原子认领兜底——TranscriptionWorker 用条件 UPDATE（{@code WHERE id = ? AND status IN
 * ('PENDING','RETRYING')}）并校验影响行数，同一任务只有一个实例能把行改成 RUNNING，抢到 0 行的实例直接跳过。
 * 回收僵尸 RUNNING 用 {@link StalePendingQueries#reclaimZombieRunning(long)} 的同构条件 UPDATE。
 */
@Component
public class StalePendingSweeper {

    private static final Logger log = LoggerFactory.getLogger(StalePendingSweeper.class);

    /** T4.79：可见性超时兜底值——与 yml 默认值、≤0 回退值一致（默认 180s，比心跳 20s 大一个数量级）。 */
    static final long DEFAULT_VISIBILITY_TIMEOUT_SEC = 180;

    /** T4.79/P-02：每轮最多处理的僵尸回执条数（卡面 C3 指定 50，防回执堆积后单轮过长）。 */
    static final int RECEIPT_BATCH = 50;

    private final QueuePublisher queuePublisher;
    private final StalePendingQueries stalePendingQueries;
    private final StringRedisTemplate redis;

    @Value("${app.queue.pending-sweep.enabled:true}")
    private boolean enabled;

    /** 陈旧阈值（分钟）：与 StartupRecovery 共用单一来源（T4.67 S4，DEFAULT_STALE_MINUTES）。 */
    @Value("${app.queue.pending-sweep.stale-minutes:" + StalePendingQueries.DEFAULT_STALE_MINUTES + "}")
    private long staleMinutes = StalePendingQueries.DEFAULT_STALE_MINUTES;

    /** T4.79/P-02：回执可见性超时（秒）——回执超过该时长即视为消费者可能已死，查 DB 后回收/清理。 */
    @Value("${app.worker.visibility-timeout-sec:" + DEFAULT_VISIBILITY_TIMEOUT_SEC + "}")
    private long visibilityTimeoutSec = DEFAULT_VISIBILITY_TIMEOUT_SEC;

    /** T4.79/P-02：处理中回执 ZSET 键（与 TranscriptionWorker.processingKey 同源配置）。 */
    @Value("${app.worker.processing-key:meetingnotes:worker-processing}")
    private String processingKey = "meetingnotes:worker-processing";

    /** T4.79/P-02：心跳间隔（秒）——只读同源配置，用于「RUNNING 且心跳新鲜」判定（updated_at 在 heartbeat×3 内）。 */
    @Value("${app.worker.heartbeat-sec:20}")
    private long heartbeatSec = 20;

    public StalePendingSweeper(QueuePublisher queuePublisher, StalePendingQueries stalePendingQueries,
                               StringRedisTemplate redis) {
        this.queuePublisher = queuePublisher;
        this.stalePendingQueries = stalePendingQueries;
        this.redis = redis;
    }

    /** 周期清扫；enabled=false 时完全不动（不查库、不投递）。fixedDelayString 外置周期（M-1）。
     *  T4.79：同一段调度追加僵尸回执清扫（不新增调度器）；两类清扫互不依赖、单条失败互不影响。 */
    @Scheduled(fixedDelayString = "${app.queue.pending-sweep.interval-ms:60000}")
    public void sweepStalePending() {
        if (!enabled) {
            return;
        }
        sweepStalePendingOnce();
        sweepZombieReceipts();
    }

    private void sweepStalePendingOnce() {
        List<Long> stale;
        try {
            stale = stalePendingQueries.findStalePending(StalePendingQueries.cutoff(staleMinutes));
        } catch (DataAccessException e) {
            /* 空库/测试环境没有表：跳过但不拖垮调度线程（D-035 口径，同 StartupRecovery；T4.67-fix F2 补日志） */
            log.warn("陈旧 PENDING 清扫跳过（空库/测试环境无表或数据库不可用）：{}", e.getMessage());
            return;
        }
        if (stale.isEmpty()) {
            return;
        }
        int requeued = 0;
        for (Long id : stale) {
            try {
                queuePublisher.publishToTail(id);
                stalePendingQueries.touch(id); /* T4.62 S2：先投递成功、再 touch——失败绝不刷新 */
                requeued++;
            } catch (RuntimeException e) {
                /* 单条投递失败（如 Redis 抖动）：记 warn 继续下一条，不刷新 updated_at——下一轮再兜底 */
                log.warn("陈旧 PENDING {} 重投失败（Redis 不可用），留待下一轮清扫", id, e);
            }
        }
        log.atInfo()
                .addKeyValue("swept", stale.size())
                .addKeyValue("requeued", requeued)
                .log("陈旧 PENDING 周期清扫：重入队完成");
    }

    /** T4.79/P-02：僵尸回执清扫。可见性超时 = 回执 score 早于 now − timeoutSec（score 是 worker 取出时刻，
     *  超 50 条截断下一轮继续）。防误配：visibilityTimeoutSec ≤ 0 属误配（0 = 无条件回收所有在跑任务），WARN 回退 180。 */
    void sweepZombieReceipts() {
        long timeoutSec = visibilityTimeoutSec;
        if (timeoutSec <= 0) {
            log.warn("app.worker.visibility-timeout-sec={} 非法（必须 > 0），回退到 {} 秒", timeoutSec, DEFAULT_VISIBILITY_TIMEOUT_SEC);
            timeoutSec = DEFAULT_VISIBILITY_TIMEOUT_SEC;
        }
        double cutoff = System.currentTimeMillis() - timeoutSec * 1000L;
        Set<String> candidates;
        try {
            // ms 精度 score：cutoff-1（含）等价卡面的开区间 (cutoff)；min=0（score 恒为正毫秒时间戳）
            candidates = redis.opsForZSet().rangeByScore(processingKey, 0, cutoff - 1, 0, RECEIPT_BATCH);
        } catch (RuntimeException e) {
            log.warn("僵尸回执清扫跳过（Redis 不可用）：{}", e.getMessage());
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        long freshCutoff = System.currentTimeMillis() - heartbeatSec * 3 * 1000L; // 心跳×3 内 = 活着
        int zombieReceipts = 0, requeued = 0, skipped = 0;
        for (String member : candidates) {
            long id;
            try {
                id = Long.parseLong(member.trim());
            } catch (NumberFormatException e) {
                log.warn("回执 {} 非 id 形态（脏数据），直接清理", member);
                zremQuietly(member);
                continue;
            }
            try {
                java.util.Map<String, Object> row = stalePendingQueries.findTaskStatus(id);
                if (row == null) { // 行已被删：回执失去意义
                    zremQuietly(member);
                    zombieReceipts++;
                } else if ("RUNNING".equals(row.get("status"))) {
                    Timestamp updatedAt = (Timestamp) row.get("updated_at");
                    if (updatedAt != null && updatedAt.after(new Timestamp(freshCutoff))) {
                        skipped++; // 心跳活着：可能是慢任务，也可能是其它实例在跑——绝不回收
                    } else {
                        int won = stalePendingQueries.reclaimZombieRunning(id); // 条件 UPDATE：抢到才算赢
                        if (won == 1) {
                            if (queuePublisher.tryPublishToTail(id)) { requeued++; }
                        }
                        zremQuietly(member); // 赢/输都清回执（输 = 行已被别人动过，PENDING 清扫兜底）
                        zombieReceipts++;
                    }
                } else if ("PENDING".equals(row.get("status")) || "RETRYING".equals(row.get("status"))) {
                    // 消费失败后没人重投的行：重投成功才清回执，失败留到下一轮
                    if (queuePublisher.tryPublishToTail(id)) {
                        zremQuietly(member);
                        requeued++;
                        zombieReceipts++;
                    } else {
                        log.warn("回执 {} 对应任务停在 {}，重投失败（Redis 不可用），回执留待下一轮", member, row.get("status"));
                        skipped++;
                    }
                } else if ("DONE".equals(row.get("status")) || "DEAD".equals(row.get("status"))) {
                    zremQuietly(member); // 已终态：只清回执
                    zombieReceipts++;
                } else {
                    skipped++; // 未知状态：不轻举妄动
                }
            } catch (RuntimeException e) {
                log.warn("回执 {} 处理失败，留待下一轮清扫", member, e);
            }
        }
        log.atInfo()
                .addKeyValue("zombieReceipts", zombieReceipts)
                .addKeyValue("requeued", requeued)
                .addKeyValue("skipped", skipped)
                .log("僵尸回执清扫：可见性超时回收完成");
    }

    /** ZREM 失败只 WARN（残留回执下一轮会再进来，靠 DB 状态判定，不会误重投）。 */
    private void zremQuietly(String member) {
        try {
            redis.opsForZSet().remove(processingKey, member);
        } catch (RuntimeException e) {
            log.warn("回执 {} ZREM 失败（Redis 不可用），留待下一轮", member, e);
        }
    }
}
