package com.meetingnotes.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务指标（T4.84/P-25 + T4.85/P-27 收尾）：任务按状态计数、Redis 队列深度、最老 PENDING 年龄、
 * 近 1h DONE 任务时延分位（p50/p95），暴露给 Prometheus（/actuator/prometheus）。
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

    /** 时延分位样本窗口：近 1 小时的 DONE 任务（SQL 参数为 JVM 时间戳，卡面 C1 口径）。 */
    static final long DURATION_WINDOW_MILLIS = 3_600_000L;
    /** 时延分位样本上限：ORDER BY updated_at DESC LIMIT 500——只统计最近的 500 条，超出截断（写进文档）。 */
    static final int DURATION_SAMPLE_LIMIT = 500;

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final String queueKey;
    private final long cacheSeconds;
    private final Map<String, AtomicLong> taskCountByStatus = new ConcurrentHashMap<>();
    private final AtomicLong queueDepth = new AtomicLong();
    /** 最老一条 PENDING 距今的秒数（无 PENDING = 0，不输出 NaN/-1）。 */
    private final AtomicLong oldestPendingAgeSec = new AtomicLong();
    /** 近 1h DONE 任务时延分位（秒，最近秩法；自算标签 quantile——非 Prometheus summary）。 */
    private final AtomicLong durationP50Sec = new AtomicLong();
    private final AtomicLong durationP95Sec = new AtomicLong();
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
        Gauge.builder("meetingnotes.pending.oldest.age.seconds", oldestPendingAgeSec, value -> {
                    refreshIfStale();  // 抓取驱动：每个 gauge 的取值函数都必须先刷新（T4.85 教训——漏写则指标恒 0）
                    return value.doubleValue();
                })
                .description("最老一条 PENDING 距今秒数（0 = 无 PENDING）")
                .register(registry);
        // 自算 p50/p95（标签 quantile）：不是 Prometheus summary/histogram，勿用 histogram_quantile
        Gauge.builder("meetingnotes.task.duration.seconds", durationP50Sec, value -> {
                    refreshIfStale();
                    return value.doubleValue();
                })
                .tag("quantile", "0.5")
                .description("DONE 任务时延分位 p50（秒；近 1h、最多 500 条样本、最近秩法）")
                .register(registry);
        Gauge.builder("meetingnotes.task.duration.seconds", durationP95Sec, value -> {
                    refreshIfStale();
                    return value.doubleValue();
                })
                .tag("quantile", "0.95")
                .description("DONE 任务时延分位 p95（秒；近 1h、最多 500 条样本、最近秩法）")
                .register(registry);
    }

    /** 最近秩（nearest-rank）分位：升序第 ceil(p/100 × n) 个（1-based）；空样本 → 0。 */
    static long percentileSeconds(List<Long> samples, int p) {
        if (samples == null || samples.isEmpty()) {
            return 0L;
        }
        long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.min(Math.max(rank, 1), sorted.length) - 1];
    }

    /** 节流刷新：距上次刷新 < cacheSeconds 直接返回缓存；过期才发一条 GROUP BY SQL + 一次 Redis LLEN。 */
    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMillis < cacheSeconds * 1000L) {
            return;
        }
        lastRefreshMillis = now;
        try {
            // ① 任务按状态计数（一条 GROUP BY；禁止按状态循环查 5 次）；显式 ResultSetExtractor 类型消重载歧义
            org.springframework.jdbc.core.ResultSetExtractor<Map<String, Long>> collector = rowSet -> {
                Map<String, Long> byStatus = new HashMap<>();
                while (rowSet.next()) {
                    byStatus.put(rowSet.getString(1), rowSet.getLong(2));
                }
                return byStatus;
            };
            Map<String, Long> byStatus = jdbc.query("SELECT status, count(*) FROM task GROUP BY status", collector);
            // ② 最老 PENDING 年龄：SQL 只取 min(updated_at)，秒差在 Java 算（不用 SQL 时间函数——H2/PG 同一条 SQL）
            Timestamp oldestPending = jdbc.queryForObject(
                    "SELECT min(updated_at) FROM task WHERE status = 'PENDING'", Timestamp.class);
            // ③ 时延分位样本：近 1h DONE（参数为 JVM 时间戳），最多 500 条，秒差在 Java 算
            Timestamp windowStart = new Timestamp(now - DURATION_WINDOW_MILLIS);
            List<Long> durations = jdbc.query(
                    "SELECT created_at, updated_at FROM task WHERE status = 'DONE' AND updated_at > ? "
                            + "ORDER BY updated_at DESC LIMIT 500",
                    rowSet -> {
                        List<Long> samples = new ArrayList<>();
                        while (rowSet.next()) {
                            samples.add((rowSet.getTimestamp(2).getTime() - rowSet.getTimestamp(1).getTime()) / 1000L);
                        }
                        return samples;
                    }, windowStart);
            // 三个查询全部成功才落缓存（任一异常 → 整批保留旧值）
            for (String status : STATUS_BUCKETS) {
                taskCountByStatus.get(status).set(byStatus == null ? 0L : byStatus.getOrDefault(status, 0L));
            }
            oldestPendingAgeSec.set(oldestPending == null ? 0L
                    : Math.max(0L, (now - oldestPending.getTime()) / 1000L));
            durationP50Sec.set(percentileSeconds(durations, 50));
            durationP95Sec.set(percentileSeconds(durations, 95));
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
