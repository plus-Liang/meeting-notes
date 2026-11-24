package com.meetingnotes.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务指标（T4.84/P-25）：任务按状态计数 + Redis 队列深度，暴露给 Prometheus（/actuator/prometheus）。
 *
 * <p>两个语义要点：
 * <ul>
 *   <li><b>抓取驱动</b>：无后台线程、无 {@code @Scheduled}（禁止项 3）——Gauge 的取值函数在
 *       Prometheus 抓取时被调用，内部先 {@link #refreshIfStale()}（cacheSeconds 节流）再读缓存。
 *       没人抓就不查库，抓多频也只按 cacheSeconds 节流；</li>
 *   <li><b>单实例口径</b>：计数是 JVM 内存缓存，每次抓取各自查一次库/Redis——多实例部署时各实例
 *       报各自视角的同一 DB 计数（值相同，无聚合问题）；queue depth 用 {@code queueKey} 直查同一
 *       Redis 列表（多实例共享，同值）。</li>
 * </ul>
 *
 * <p>异常口径：查库/Redis 抛异常时保留上一次值（缓存不清空），仅 WARN 一次（AtomicBoolean 去重，
 * 避免每 cacheSeconds 刷屏），绝不向 Micrometer 抛异常——否则 /actuator/prometheus 整页 500。
 */
@Component
public class TaskMetrics {

    private static final Logger log = LoggerFactory.getLogger(TaskMetrics.class);

    /** 五档状态（SQL GROUP BY 后对齐到这些桶；未知状态忽略）。 */
    private static final List<String> STATUS_BUCKETS = List.of("PENDING", "RUNNING", "DONE", "DEAD", "RETRYING");

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final String queueKey;
    private final long cacheSeconds;
    private final Map<String, AtomicLong> taskCountByStatus = new ConcurrentHashMap<>();
    private final AtomicLong queueDepth = new AtomicLong();
    private volatile long lastRefreshMillis;
    // 注意：初始值必须为 0（不能 Long.MIN_VALUE）——now - Long.MIN_VALUE 会 long 溢出为负，
    // 使节流条件恒真、指标永远不刷新（T4.84 调试实录，K-185 候选）。0 = 启动即视为过期。
    private final AtomicBoolean failureWarned = new AtomicBoolean(false);

    public TaskMetrics(JdbcTemplate jdbc, StringRedisTemplate redis, MeterRegistry registry,
                       @Value("${app.worker.queue-key:meetingnotes:task-queue}") String queueKey,
                       @Value("${app.metrics.cache-seconds:15}") long cacheSeconds) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.queueKey = queueKey;
        this.cacheSeconds = cacheSeconds;
        for (String status : STATUS_BUCKETS) {
            AtomicLong bucket = new AtomicLong();
            taskCountByStatus.put(status, bucket);
            // holder 强引用 bucket（Micrometer Gauge 只弱引用 lambda，不弱引用 holder）
            Gauge.builder("meetingnotes.task.count", bucket, value -> {
                        refreshIfStale();
                        return value.doubleValue();
                    })
                    .tag("status", status)
                    .description("任务按状态计数（对账口径见 docs/项目/录音纪要平台/运维-监控与告警.md）")
                    .register(registry);
        }
        Gauge.builder("meetingnotes.queue.depth", queueDepth, value -> {
                    refreshIfStale();
                    return value.doubleValue();
                })
                .description("Redis 转写队列深度（BLPOP 待取条数）")
                .register(registry);
    }

    /** 节流刷新：距上次刷新 < cacheSeconds 直接返回缓存；过期才发一条 GROUP BY SQL + 一次 Redis LLEN。 */
    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMillis < cacheSeconds * 1000L) {
            return;
        }
        lastRefreshMillis = now;
        try {
            // 一次刷新只发这一条 SQL（禁止项 4：不许按状态循环查 5 次）；显式 ResultSetExtractor 类型消重载歧义
            org.springframework.jdbc.core.ResultSetExtractor<Map<String, Long>> collector = rowSet -> {
                Map<String, Long> byStatus = new HashMap<>();
                while (rowSet.next()) {
                    byStatus.put(rowSet.getString(1), rowSet.getLong(2));
                }
                return byStatus;
            };
            Map<String, Long> byStatus = jdbc.query("SELECT status, count(*) FROM task GROUP BY status", collector);
            for (String status : STATUS_BUCKETS) {
                taskCountByStatus.get(status).set(byStatus == null ? 0L : byStatus.getOrDefault(status, 0L));
            }
            Long depth = redis.opsForList().size(queueKey);
            queueDepth.set(depth == null ? 0L : depth);
            failureWarned.set(false);
        } catch (RuntimeException e) {
            // 保留上一次值，绝不向 Micrometer 抛异常；WARN 只发一次（本轮节流期内不刷屏）
            if (failureWarned.compareAndSet(false, true)) {
                log.warn("业务指标刷新失败（保留上一次值）：{}", e.getMessage());
            }
        }
    }
}
