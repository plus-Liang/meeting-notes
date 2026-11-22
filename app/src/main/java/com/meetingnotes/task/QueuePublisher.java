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
 * <p>方向语义（T4.60 B1）：新任务入队走队头（leftPush，"新任务插队"为既有语义）；
 * worker 失败重投走队尾（rightPush，让出队头、给其他任务让路并形成自然退避）。
 *
 * <p>{@link #publish(long)}/{@link #publishToTail(long)}：入队失败抛 503 QUEUE_UNAVAILABLE（T4.50 口径：
 * 不得假成功）——错误码/文案/状态码是对外契约（接入指南 §错误码）。
 * {@link #tryPublish(long)}/{@link #tryPublishToTail(long)}：不抛，返回是否成功——
 * 用于「失败要自行处理」的调用方：worker 失败重投（{@code tryPublishToTail}）、
 * 启动恢复 {@code StartupRecovery}（{@code tryPublish}）。
 *
 * <p>{@code StartupRecovery} 启动恢复也走本类：用 {@link #tryPublish(long)}（永不抛），
 * 启动期 Redis 未就绪（D-035）时返回 false、由调用方记 error 日志，不拖垮应用上下文；
 * 原始异常栈由本类内部记录。
 */
@Component
public class QueuePublisher {

    private static final Logger log = LoggerFactory.getLogger(QueuePublisher.class);

    private final StringRedisTemplate redis;
    private final String queueKey;

    public QueuePublisher(StringRedisTemplate redis,
                          @Value("${app.worker.queue-key}") String queueKey) {
        if (queueKey == null || queueKey.isBlank()) {
            throw new IllegalArgumentException("queueKey 不能为空（检查 app.worker.queue-key 配置）");
        }
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

    /** 不抛版本：启动恢复（StartupRecovery）等"失败要自行处理"的调用方用（T4.63 O-2）；
     * worker 失败重投走 {@code tryPublishToTail}（队尾语义）。失败记 WARN 不中断调用方流程。 */
    public boolean tryPublish(long taskId) {
        try {
            publish(taskId);
            return true;
        } catch (ApiException e) {
            log.warn("任务 {} 入队失败（Redis 不可用），交由调用方回退状态", taskId, e);
            return false;
        }
    }

    /** T4.60 B1：worker 失败重投走队尾（rightPush，让出队头、给其他任务让路并形成自然退避）；契约同 publish。 */
    public void publishToTail(long taskId) {
        try {
            redis.opsForList().rightPush(queueKey, Long.toString(taskId));
        } catch (DataAccessException e) {
            log.error("任务 {} 重投失败（Redis 不可用），返回 503 QUEUE_UNAVAILABLE", taskId, e);
            throw new ApiException(503, "QUEUE_UNAVAILABLE", "队列暂不可用，请稍后重试");
        }
    }

    /** 不抛的尾插版本：worker markFailure 用（失败自行回退状态）。 */
    public boolean tryPublishToTail(long taskId) {
        try {
            publishToTail(taskId);
            return true;
        } catch (ApiException e) {
            log.warn("任务 {} 重投失败（Redis 不可用），交由调用方回退状态", taskId, e);
            return false;
        }
    }
}
