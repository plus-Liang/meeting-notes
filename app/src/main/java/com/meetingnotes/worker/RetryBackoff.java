package com.meetingnotes.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * T4.100/C3：失败重试的指数退避计算（纯函数）。delay = min(base * 2^(attempts-1), max)，
 * 用 long 计算且在乘 2 前先与 max 比较，attempts>=31 直接返回 max（防溢出）。
 * 配置非法（base<1 / max<base）在构造期 WARN 并回退 30/300；scan<500 回退 5000。
 */
@Component
public class RetryBackoff {
    private static final Logger log = LoggerFactory.getLogger(RetryBackoff.class);
    static final long DEFAULT_BASE_SEC = 30;
    static final long DEFAULT_MAX_SEC = 300;
    static final long DEFAULT_SCAN_MS = 5000;

    private final long baseSec;
    private final long maxSec;
    private final long scanMs;

    public RetryBackoff(@Value("${app.retry.base-delay-sec:30}") long baseSec,
                        @Value("${app.retry.max-delay-sec:300}") long maxSec,
                        @Value("${app.retry.scan-interval-ms:5000}") long scanMs) {
        long base = baseSec, max = maxSec, scan = scanMs;
        if (base < 1 || max < base) {
            log.warn("app.retry 非法（base-delay-sec={}, max-delay-sec={}），回退 30/300", base, max);
            base = DEFAULT_BASE_SEC; max = DEFAULT_MAX_SEC;
        }
        if (scan < 500) {
            scan = DEFAULT_SCAN_MS;
        }
        this.baseSec = base; this.maxSec = max; this.scanMs = scan;
    }

    /** attempts<=0 视为 1；乘 2 前先与 max 比较避免 long 溢出。 */
    public Duration delayFor(int attempts) {
        int a = Math.max(1, attempts);
        if (a >= 31) { return Duration.ofSeconds(maxSec); }
        long d = baseSec;
        for (int i = 1; i < a && d < maxSec; i++) {
            if (d > maxSec / 2) { return Duration.ofSeconds(maxSec); }
            d *= 2;
        }
        return Duration.ofSeconds(Math.min(d, maxSec));
    }

    public long scanIntervalMs() { return scanMs; }
}
