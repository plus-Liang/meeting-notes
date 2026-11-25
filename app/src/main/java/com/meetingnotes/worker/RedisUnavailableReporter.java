package com.meetingnotes.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * T4.91/S2：Redis 故障日志去抖——同一窗口（redis-log-window-sec，默认 60s）内最多 1 条 WARN，
 * 窗口跨过后再打一条心跳式告警（带期间抑制计数）；恢复时 clear() 打 1 条 INFO，正常态调用零日志。
 * worker 单线程，仍用 synchronized 保证不出现半更新（StalePendingSweeper 将来可能复用）。
 * Clock 可注入（包可见构造器），测试用可控时钟，不依赖真实时间。
 */
@Component
public class RedisUnavailableReporter {

    private static final Logger log = LoggerFactory.getLogger(RedisUnavailableReporter.class);

    private final long windowMs;
    private final long pollSecs;
    private final Clock clock;
    private long firstFailMs = -1;
    private long lastWarnMs = -1;
    private long suppressed;

    @org.springframework.beans.factory.annotation.Autowired
    public RedisUnavailableReporter(@Value("${app.worker.redis-log-window-sec:60}") long windowSec,
            @Value("${app.worker.poll-timeout-sec:2}") long pollSecs) {
        this(windowSec, pollSecs, Clock.systemUTC());
    }

    RedisUnavailableReporter(long windowSec, long pollSecs, Clock clock) {
        if (windowSec <= 0) {
            log.warn("app.worker.redis-log-window-sec={} 非法，回退到 60", windowSec);
            windowSec = 60;
        }
        this.windowMs = windowSec * 1000;
        this.pollSecs = pollSecs;
        this.clock = clock;
    }

    /** 故障上报：首次或距上次 WARN ≥ 窗口 → 1 条 WARN（带抑制计数）；否则只累加计数，不产生日志行。 */
    public synchronized void report(RuntimeException e) {
        long now = clock.millis();
        if (firstFailMs < 0) { firstFailMs = now; }
        if (lastWarnMs < 0 || now - lastWarnMs >= windowMs) {
            log.warn("Redis 不可用，{}s 后重试：{}（窗口内已抑制 {} 条重复告警）", pollSecs, e.getMessage(), suppressed);
            lastWarnMs = now;
            suppressed = 0;
        } else {
            suppressed++;
        }
    }

    /** 恢复清零：有过故障才打 1 条 INFO（首次失败后秒数 + 抑制计数）并复位；正常态调用零日志。 */
    public synchronized void clear() {
        if (firstFailMs < 0) { return; }
        log.info("Redis 已恢复（首次失败后 {}s，期间抑制 {} 条重复告警）", (clock.millis() - firstFailMs) / 1000, suppressed);
        firstFailMs = -1;
        lastWarnMs = -1;
        suppressed = 0;
    }
}