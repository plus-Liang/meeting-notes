package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 任务入队唯一出口（T4.52 R2/R4：此前 TaskService/LinkTaskService/TaskRetryService/TextTaskService
 * 各自复制一份 enqueue，已漂移出 B1/B2 两个缺陷）。
 *
 * <p>{@link #publish(long)}：入队失败抛 503 QUEUE_UNAVAILABLE（T4.50 口径：不得假成功）——
 * Web 入口统一用它，错误码/文案/状态码是对外契约（接入指南 §错误码）。
 * {@link #tryPublish(long)}：不抛，返回是否成功——worker 重投这类"失败要自行回退状态"的调用方用它。
 *
 * <p>{@code StartupRecovery} 不走本类：启动期 Redis 未就绪属正常场景（D-035），
 * 必须逐条尽力而为、带着原始异常写日志，不许拖垮应用上下文。
 */
@Component
public class QueuePublisher {

    private static final Logger log = LoggerFactory.getLogger(QueuePublisher.class);

    private final StringRedisTemplate redis;
    private final String queueKey;

    public QueuePublisher(StringRedisTemplate redis,
                          @Value("${app.worker.queue-key}") String queueKey) {
        this.redis = redis;
        this.queueKey = queueKey;
    }

    /** 投递 worker 队列；入队失败抛 503 QUEUE_UNAVAILABLE（Redis 不可用时任务行已提交停在 PENDING）。 */
    public void publish(long taskId) {
        try {
            redis.opsForList().leftPush(queueKey, Long.toString(taskId));
        } catch (DataAccessException e) {
            log.error("任务 {} 入队失败（Redis 不可用），返回 503 QUEUE_UNAVAILABLE", taskId, e);
            throw new ApiException(503, "QUEUE_UNAVAILABLE", "队列暂不可用，请稍后重试");
        }
    }

    /** 不抛版本：供 worker 重投等调用方自行回退状态（S6），失败记 WARN 不中断调用方流程。 */
    public boolean tryPublish(long taskId) {
        try {
            publish(taskId);
            return true;
        } catch (ApiException e) {
            log.warn("任务 {} 入队失败（Redis 不可用），交由调用方回退状态", taskId);
            return false;
        }
    }
}
