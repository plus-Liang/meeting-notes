package com.meetingnotes.config;

import com.meetingnotes.storage.StorageService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 存储指标（T4.92/P-22 前半）：磁盘可用字节 + 音频占用字节，暴露给 Prometheus。
 * 沿用 TaskMetrics 的「抓取驱动」范式：Gauge 取值函数在抓取时调用 {@link #refreshIfStale()}
 * （cacheSeconds=15 节流），无后台线程、无 @Scheduled（K-188：取值函数必须触发刷新，
 * 否则指标恒 0）；取数失败保留旧值 + 去重 WARN，绝不向 Micrometer 抛异常。
 */
@Component
public class StorageMetrics {

    private static final Logger log = LoggerFactory.getLogger(StorageMetrics.class);
    private static final long CACHE_MILLIS = 15_000;

    private final StorageService storageService;
    private final AtomicLong diskFreeBytes = new AtomicLong();
    private final AtomicLong audioBytes = new AtomicLong();
    private volatile long lastRefreshMillis;
    private final AtomicBoolean failureWarned = new AtomicBoolean(false);

    public StorageMetrics(StorageService storageService, MeterRegistry registry) {
        this.storageService = storageService;
        Gauge.builder("meetingnotes.storage.disk.free.bytes", diskFreeBytes, value -> {
                    refreshIfStale(); // K-188：取值函数必须触发刷新（方法引用会漏 → 指标恒 0）
                    return value.doubleValue();
                })
                .description("磁盘可用空间（字节；FileStore 探测目录见 STORAGE_PROBE_DIR）")
                .register(registry);
        Gauge.builder("meetingnotes.storage.audio.bytes", audioBytes, value -> {
                    refreshIfStale();
                    return value.doubleValue();
                })
                .description("音频对象总占用（字节；tasks/ 前缀，≤20000 对象样本）")
                .register(registry);
    }

    /** 节流刷新：15s 内直接返回缓存；过期才取数。取数失败保留旧值 + 去重 WARN。 */
    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMillis < CACHE_MILLIS) {
            return;
        }
        lastRefreshMillis = now;
        try {
            diskFreeBytes.set(storageService.diskUsage().freeBytes());
            audioBytes.set(storageService.audioUsage().bytes());
            failureWarned.set(false);
        } catch (Exception e) {
            System.out.println("PROBE storage-refresh-exc: " + e);
            if (failureWarned.compareAndSet(false, true)) {
                log.warn("存储指标刷新失败（保留上一次值）：{}", e.getMessage());
            }
        }
    }
}
