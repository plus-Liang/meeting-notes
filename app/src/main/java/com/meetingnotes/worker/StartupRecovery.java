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
 * 只动 RUNNING，不碰 DONE/DEAD/PENDING/RETRYING（DONE 回收等于重跑，RETRYING 本就在队列里）。
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
        for (Long id : stuck) {
            try {
                redis.opsForList().leftPush(queueKey, Long.toString(id));
            } catch (RuntimeException e) {
                // Redis 尚未就绪：任务已回 PENDING，等待后续入队重试/人工兜底，不让启动流程崩
                log.error("启动恢复：任务 {} 回 PENDING 成功但入队失败（Redis 不可用）", id, e);
            }
        }
        log.info("启动恢复：回收 {} 个 RUNNING 僵尸任务重新入队", stuck.size());
    }
}
