package com.meetingnotes.worker;

import com.meetingnotes.task.QueuePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * T4.61：运行中实例的「陈旧 PENDING」周期清扫——Redis 故障期间提交的任务（行已提交、队列未投）
 * 在无人重提、也不重启进程时，靠本清扫每轮回到队列，避免无限期停在「排队中」。
 *
 * <p>判据与 {@link StartupRecovery} 的启动恢复一致：status='PENDING' 且 updated_at 早于
 * stale-minutes（默认 2 分钟，挡住"刚创建还没入队"的行，不与正常提交流程抢）。
 * 重投复用 {@link QueuePublisher#publish(long)}（队头，单一入队实现，不自建第 5 份 leftPush）；
 * 成功入队后刷新 updated_at，下一轮不再重复入队。
 *
 * <p>顺序语义（T4.62 S2，与 StartupRecovery 相反、不许合并）：**先投递成功、再 touch**——
 * 投递失败绝不刷新 updated_at，下一轮再兜底。判据/刷新语句在 {@link StalePendingQueries}。
 *
 * <p>开销边界：每轮一条 SELECT（仅 id 列）+ 每条成功重投一条 UPDATE；Redis 长期不可用时
 * 每条投递等待 Lettuce command-timeout（默认 60s）后记 warn 进下一轮——阻塞发生在
 * 调度线程池，不影响 worker 与 HTTP 线程。多实例假设：重投后 updated_at 刷新 +
 * worker 按 status 认领（非 PENDING/RETRYING 直接跳过），重复入队的多余副本会被安全跳过
 * （单实例部署为当前口径，D-061）。
 */
@Component
public class StalePendingSweeper {

    private static final Logger log = LoggerFactory.getLogger(StalePendingSweeper.class);

    private final QueuePublisher queuePublisher;
    private final StalePendingQueries stalePendingQueries;

    @Value("${app.queue.pending-sweep.enabled:true}")
    private boolean enabled;

    /** 陈旧阈值（分钟）：与 StartupRecovery.STALE_PENDING_MINUTES 的 2 分钟口径一致。 */
    @Value("${app.queue.pending-sweep.stale-minutes:2}")
    private long staleMinutes;

    public StalePendingSweeper(QueuePublisher queuePublisher, StalePendingQueries stalePendingQueries) {
        this.queuePublisher = queuePublisher;
        this.stalePendingQueries = stalePendingQueries;
    }

    /** 周期清扫；enabled=false 时完全不动（不查库、不投递）。fixedDelayString 外置周期（M-1）。 */
    @Scheduled(fixedDelayString = "${app.queue.pending-sweep.interval-ms:60000}")
    public void sweepStalePending() {
        if (!enabled) {
            return;
        }
        List<Long> stale;
        try {
            stale = stalePendingQueries.findStalePending(StalePendingQueries.cutoff(staleMinutes));
        } catch (DataAccessException e) {
            /* 空库/测试环境没有表：静默跳过（D-035 口径，同 StartupRecovery），不拖垮调度线程 */
            return;
        }
        if (stale.isEmpty()) {
            return;
        }
        int requeued = 0;
        for (Long id : stale) {
            try {
                queuePublisher.publish(id);
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
}
