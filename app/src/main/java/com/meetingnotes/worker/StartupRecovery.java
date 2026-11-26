package com.meetingnotes.worker;

import com.meetingnotes.task.QueuePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * T2.3 崩溃恢复（US-2 场景②）：进程被杀在 RUNNING 的任务，重启后必然是僵尸——
 * 单实例下应用启动时把 RUNNING 回收回 PENDING 并重新入队。
 *
 * <p>T4.78/P-01：回收判据改为「僵尸 RUNNING」——status='RUNNING' 且 updated_at 早于
 * {@code app.recovery.stale-running-minutes}（默认 5 分钟，必须远大于 T4.77 心跳间隔 20s）。
 * 心跳活着（本实例或其它实例正在跑）的 RUNNING 不碰，多实例/滚动重启不再误回收别人的在跑任务；
 * 逐条按 id 更新，杜绝无 id 条件的全表 UPDATE。0/负值属误配（等于全表回收），启动时 WARN 并回退 5。
 * PENDING 超过阈值仍未投递的也重新入队（T4.50：Redis 故障期间提交的任务行已落库、
 * 队列没投成，阈值挡住"刚创建还没入队"的 PENDING，不与正常提交流程抢）；不碰 DONE/DEAD/RETRYING
 * （DONE 回收等于重跑，RETRYING 本就在队列里）。
 * 入队走 {@link QueuePublisher#tryPublish(long)}（T4.62 S1：不抛 503 契约异常；
 * T4.67 S2：宽兜底 RuntimeException——其它运行时异常穿出会让 ApplicationRunner 失败 = 启动失败，恢复之）。
 * 与消费 worker（{@link TranscriptionWorker}）解耦：本类只做启动回收，失败分级/消费不变。
 */
@Component
public class StartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    /** T4.78：僵尸 RUNNING 判据的兜底值——与 C2 的 yml 默认值、≤0 回退值三处一致。 */
    static final long DEFAULT_STALE_RUNNING_MINUTES = 5;

    private final JdbcTemplate jdbc;
    private final QueuePublisher queuePublisher;
    private final StalePendingQueries stalePendingQueries;
    private final TaskCrashRecovery crashRecovery; // T4.94/P-03：陈旧 RUNNING 崩溃计次（PENDING+attempts+1 原子回收）

    /** T4.67 S4：阈值单一来源——与运行期清扫共用同一属性（默认值引用 StalePendingQueries 常量）。 */
    @Value("${app.queue.pending-sweep.stale-minutes:" + StalePendingQueries.DEFAULT_STALE_MINUTES + "}")
    private long staleMinutes = StalePendingQueries.DEFAULT_STALE_MINUTES;

    /** T4.78/P-01：僵尸 RUNNING 阈值（分钟）。必须远大于 app.worker.heartbeat-sec（20s）——
     *  心跳每 20s 刷一次本任务 updated_at，阈值小于心跳会把正常长任务误判成僵尸。 */
    @Value("${app.recovery.stale-running-minutes:" + DEFAULT_STALE_RUNNING_MINUTES + "}")
    private long recoveryStaleMinutes = DEFAULT_STALE_RUNNING_MINUTES;

    public StartupRecovery(JdbcTemplate jdbc,
                           QueuePublisher queuePublisher,
                           StalePendingQueries stalePendingQueries,
                           TaskCrashRecovery crashRecovery) {
        this.jdbc = jdbc;
        this.queuePublisher = queuePublisher;
        this.stalePendingQueries = stalePendingQueries;
        this.crashRecovery = crashRecovery;
    }

    @Override
    public void run(ApplicationArguments args) {
        // T4.78：防误配——0/负值等于「回收全部 RUNNING」（正是本卡要消灭的行为），绝不接受
        long staleRunningMinutes = recoveryStaleMinutes;
        if (staleRunningMinutes <= 0) {
            log.warn("app.recovery.stale-running-minutes={} 非法（必须 > 0），回退到 5 分钟", staleRunningMinutes);
            staleRunningMinutes = DEFAULT_STALE_RUNNING_MINUTES;
        }
        List<Long> stuck;
        List<Long> toRequeue = new java.util.ArrayList<>(); // T4.94：仅 NEED_REQUEUE 的 id 参与头插重投
        int quarantined = 0; // T4.94：崩溃计次达上限转 DEAD 的条数
        try {
            // T4.52 R3：两步式（先 SELECT 后 UPDATE）代替 UPDATE..RETURNING——后者 PG 专用语法，
            // 真库测试（StartupRecoveryDbTest，H2）跑不了、之前 mock 测试也覆盖不到；启动期单线程无并发，两步等价。
            // T4.78/P-01：只选「僵尸 RUNNING」（心跳停止超过阈值）；cutoff 用参数化 Timestamp（JVM 时钟），
            // 不写 interval '5 minute' 之类 PG 字面量——H2 跑不了。
            stuck = jdbc.query("SELECT id FROM task WHERE status = 'RUNNING' AND updated_at < ?",
                    (rs, i) -> rs.getLong(1), StalePendingQueries.cutoff(staleRunningMinutes));
            // T4.94/P-03：回收改走 TaskCrashRecovery——原子「PENDING + attempts+1」，达上限置 DEAD（毒丸不再无限重试）；
            // 重投仍留在本调用方（pushBack 头插，T4.62 S2 顺序语义不变）。
            for (Long id : stuck) {
                TaskCrashRecovery.Outcome outcome = crashRecovery.recover(id, "startup");
                if (outcome == TaskCrashRecovery.Outcome.NEED_REQUEUE) {
                    toRequeue.add(id);
                } else if (outcome == TaskCrashRecovery.Outcome.DEAD) {
                    quarantined++;
                }
            }
        } catch (DataAccessException e) {
            // 尽力而为（同 T1.4 ensureBucket 的教训）：无 key 启动（D-035）+ H2 空库等场景没有表可查，
            // 回收跳过但不许拖垮应用上下文
            log.warn("启动恢复：读取 RUNNING 任务失败（可能是空库/测试环境），跳过回收：{}", e.getMessage());
            return;
        }
        /* T4.67 S5：recovered 记「实际入队成功数」（pushBack 返回值），不再是尝试数。 */
        int recovered = pushBack(toRequeue);
        // T4.50/T4.52：Redis 故障期间提交的 PENDING（行已提交、队列未投）超过阈值 → 启动时重新入队。
        // T4.62 S2：判据与刷新语句收敛到 StalePendingQueries（启动口径=先 touch 全部、再逐条投递，顺序保留）。
        int requeuedPending;
        try {
            List<Long> stale = stalePendingQueries.findStalePending(
                    StalePendingQueries.cutoff(staleMinutes));
            for (Long id : stale) {
                stalePendingQueries.touch(id);
            }
            requeuedPending = pushBack(stale);
        } catch (DataAccessException e) {
            log.warn("启动恢复：读取积压 PENDING 失败（可能是空库/测试环境），跳过：{}", e.getMessage());
            requeuedPending = 0;
        }
        log.atInfo()
                .addKeyValue("recovered", recovered)
                .addKeyValue("requeuedPending", requeuedPending)
                .addKeyValue("quarantined", quarantined) // T4.94：崩溃计次达上限转 DEAD 条数
                .addKeyValue("staleRunningMinutes", staleRunningMinutes)
                .log("启动恢复：回收 RUNNING 僵尸任务重新入队");
    }

    /** 逐条入队（T4.62 S1：走 QueuePublisher.tryPublish）并返回成功数（T4.67 S5）。
     * 单条失败只记 error + taskId 字段，不中断——任务已在 PENDING，下次启动/重传再兜底。 */
    private int pushBack(List<Long> ids) {
        int ok = 0;
        for (Long id : ids) {
            try {
                if (queuePublisher.tryPublish(id)) {
                    ok++;
                } else {
                    log.atError()
                            .addKeyValue("taskId", id)
                            .log("启动恢复：任务回 PENDING 成功但入队失败（Redis 不可用）");
                }
            } catch (RuntimeException e) {
                /* T4.67 S2 宽兜底：tryPublish 只吞 ApiException，其它运行时异常（连接工厂未就绪等）
                   穿出会让 ApplicationRunner 失败 = 应用启动失败——恢复 T4.62 前的既有口径 */
                log.atError()
                        .addKeyValue("taskId", id)
                        .setCause(e)
                        .log("启动恢复：任务重新入队失败，跳过该任务");
            }
        }
        return ok;
    }
}
