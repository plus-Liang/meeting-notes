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
 * 重投复用 {@link QueuePublisher#publishToTail(long)}（队尾 rightPush，单一入队实现，不自建第 5 份 push；
 * T4.67-fix F5：恢复任务按「旧→新」追加到队尾——先卡住的先处理且不插队）；
 * 成功入队后刷新 updated_at，下一轮不再重复入队。
 *
 * <p>顺序语义（T4.62 S2，与 StartupRecovery 相反、不许合并）：**先投递成功、再 touch**——
 * 投递失败绝不刷新 updated_at，下一轮再兜底。判据/刷新语句在 {@link StalePendingQueries}。
 *
 * <p>开销边界：每轮一条 SELECT（仅 id 列）+ 每条成功重投一条 UPDATE；Redis 长期不可用时
 * 每条投递等待 Lettuce command-timeout（默认 60s）后记 warn 进下一轮——阻塞发生在
 * 调度线程池（{@code spring.task.scheduling.pool.size=4}，T4.67 S7），不影响 worker 与 HTTP 线程；
 * 最坏发现延迟 ≈ stale-minutes（默认 2 分钟）+ interval-ms（默认 60s）。
 *
 * <p>多实例口径（T4.67-fix F3，事实化——原注释曾宣称重复入队的多余副本无害，该说法在原子认领落地前不成立）：
 * 单实例部署为当前口径（D-061），重复入队的副本无害；多实例下靠 worker 侧的原子认领兜底——
 * TranscriptionWorker 用条件 UPDATE（{@code WHERE id = ? AND status IN ('PENDING','RETRYING')}）
 * 并校验影响行数，同一任务只有一个实例能把行改成 RUNNING，抢到 0 行的实例直接跳过，
 * 不会重复处理同一任务。判据/刷新 SQL 见 {@link StalePendingQueries}。
 */
@Component
public class StalePendingSweeper {

    private static final Logger log = LoggerFactory.getLogger(StalePendingSweeper.class);

    private final QueuePublisher queuePublisher;
    private final StalePendingQueries stalePendingQueries;

    @Value("${app.queue.pending-sweep.enabled:true}")
    private boolean enabled;

    /** 陈旧阈值（分钟）：与 StartupRecovery 共用单一来源（T4.67 S4，DEFAULT_STALE_MINUTES）。 */
    @Value("${app.queue.pending-sweep.stale-minutes:" + StalePendingQueries.DEFAULT_STALE_MINUTES + "}")
    private long staleMinutes = StalePendingQueries.DEFAULT_STALE_MINUTES;

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
}
