package com.meetingnotes.worker;

import com.meetingnotes.task.QueuePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * T2.3 崩溃恢复（US-2 场景②）：进程被杀在 RUNNING 的任务，重启后必然是僵尸——
 * 单实例前提下应用启动时把 RUNNING 一律回收回 PENDING 并重新入队。
 *
 * <p>【单实例假设】多实例部署时"别人的 RUNNING"会被误回收，须改为按 updated_at 阈值判僵尸（本卡不实现）。
 * RUNNING 一律回收；PENDING 超过 2 分钟仍未投递的也重新入队（T4.50：Redis 故障期间提交的任务行已落库、
 * 队列没投成，阈值挡住"刚创建还没入队"的 PENDING，不与正常提交流程抢）；不碰 DONE/DEAD/RETRYING
 * （DONE 回收等于重跑，RETRYING 本就在队列里）。
 * 入队走 {@link QueuePublisher#tryPublish(long)}（T4.62 S1：启动期 Redis 未就绪不抛异常、不拖垮上下文；
 * 失败原因由 QueuePublisher 内部带原始异常记 error）。
 * 与消费 worker（{@link TranscriptionWorker}）解耦：本类只做启动回收，失败分级/消费不变。
 */
@Component
public class StartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    /** 陈旧 PENDING 阈值（分钟）：挡住"刚创建还没入队"的 PENDING，不与正常提交流程抢（T4.50 卡面口径）。 */
    static final long STALE_PENDING_MINUTES = 2;

    private final JdbcTemplate jdbc;
    private final QueuePublisher queuePublisher;
    private final StalePendingQueries stalePendingQueries;

    public StartupRecovery(JdbcTemplate jdbc,
                           QueuePublisher queuePublisher,
                           StalePendingQueries stalePendingQueries) {
        this.jdbc = jdbc;
        this.queuePublisher = queuePublisher;
        this.stalePendingQueries = stalePendingQueries;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Long> stuck;
        try {
            // T4.52 R3：两步式（先 SELECT 后 UPDATE）代替 UPDATE..RETURNING——后者 PG 专用语法，
            // 真库测试（StartupRecoveryDbTest，H2）跑不了、之前 mock 测试也覆盖不到；启动期单线程无并发，两步等价。
            stuck = jdbc.query("SELECT id FROM task WHERE status = 'RUNNING'",
                    (rs, i) -> rs.getLong(1));
            if (!stuck.isEmpty()) {
                jdbc.update("UPDATE task SET status = 'PENDING', updated_at = now() WHERE status = 'RUNNING'");
            }
        } catch (DataAccessException e) {
            // 尽力而为（同 T1.4 ensureBucket 的教训）：无 key 启动（D-035）+ H2 空库等场景没有表可查，
            // 回收跳过但不许拖垮应用上下文
            log.warn("启动恢复：读取 RUNNING 任务失败（可能是空库/测试环境），跳过回收：{}", e.getMessage());
            return;
        }
        pushBack(stuck);
        // T4.50/T4.52：Redis 故障期间提交的 PENDING（行已提交、队列未投）超过 2 分钟 → 启动时重新入队。
        // T4.62 S2：判据与刷新语句收敛到 StalePendingQueries（启动口径=先 touch 全部、再逐条投递，顺序保留）。
        int requeuedPending = 0;
        try {
            List<Long> stale = stalePendingQueries.findStalePending(
                    StalePendingQueries.cutoff(STALE_PENDING_MINUTES));
            for (Long id : stale) {
                stalePendingQueries.touch(id);
            }
            pushBack(stale);
            requeuedPending = stale.size();
        } catch (DataAccessException e) {
            log.warn("启动恢复：读取积压 PENDING 失败（可能是空库/测试环境），跳过：{}", e.getMessage());
        }
        log.atInfo()
                .addKeyValue("recovered", stuck.size())
                .addKeyValue("requeuedPending", requeuedPending)
                .log("启动恢复：回收 RUNNING 僵尸任务重新入队");
    }

    /** 逐条入队（T4.62 S1：走 QueuePublisher.tryPublish，永不抛——启动期 Redis 未就绪不拖垮上下文）；
     * 单条失败只记 error + taskId 字段（原始异常由 QueuePublisher 内部带栈记录）。 */
    private void pushBack(List<Long> ids) {
        for (Long id : ids) {
            if (!queuePublisher.tryPublish(id)) {
                log.atError()
                        .addKeyValue("taskId", id)
                        .log("启动恢复：任务回 PENDING 成功但入队失败（Redis 不可用）");
            }
        }
    }
}
