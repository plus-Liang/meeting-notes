package com.meetingnotes.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * 与消费 worker（{@link TranscriptionWorker}）解耦：本类只做启动回收，失败分级/消费不变。
 */
@Component
public class StartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final String queueKey;

    public StartupRecovery(JdbcTemplate jdbc,
                           StringRedisTemplate redis,
                           @Value("${app.worker.queue-key}") String queueKey) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.queueKey = queueKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Long> stuck;
        try {
            stuck = jdbc.query(
                    "UPDATE task SET status = 'PENDING', updated_at = now() WHERE status = 'RUNNING' RETURNING id",
                    (rs, i) -> rs.getLong(1));
        } catch (DataAccessException e) {
            // 尽力而为（同 T1.4 ensureBucket 的教训）：无 key 启动（D-035）+ H2 空库等场景没有表可查，
            // 回收跳过但不许拖垮应用上下文
            log.warn("启动恢复：读取 RUNNING 任务失败（可能是空库/测试环境），跳过回收：{}", e.getMessage());
            return;
        }
        pushBack(stuck);
        // T4.50：Redis 故障期间提交的 PENDING（行已提交、队列未投）超过 2 分钟 → 启动时重新入队。
        // updated_at 同步刷新：投递成功的下次启动不再重投；投递失败的等下个周期（≥2 分钟）再兜底。
        int requeuedPending = 0;
        try {
            List<Long> stale = jdbc.query(
                    "UPDATE task SET updated_at = now() WHERE status = 'PENDING'"
                            + " AND updated_at < now() - interval '2 minutes' RETURNING id",
                    (rs, i) -> rs.getLong(1));
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

    /** 逐条入队；单条失败（Redis 未就绪）只记日志不中断——任务已在 PENDING，下次启动/重传再兜底。 */
    private void pushBack(List<Long> ids) {
        for (Long id : ids) {
            try {
                redis.opsForList().leftPush(queueKey, Long.toString(id));
            } catch (RuntimeException e) {
                log.atError()
                        .addKeyValue("taskId", id)
                        .setCause(e)
                        .log("启动恢复：任务回 PENDING 成功但入队失败（Redis 不可用）");
            }
        }
    }
}
