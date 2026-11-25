package com.meetingnotes.worker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.91/S2 契约：窗口内最多 1 条 WARN（抑制计数累加）、跨窗口再告警带计数、恢复 1 条 INFO、
 * 正常态 clear() 零日志、clear 后状态机复位。可控时钟注入，不用 Thread.sleep/真实时间。
 */
class RedisUnavailableReporterTest {

    /** 可推进的测试时钟（毫秒）。 */
    private static final class MutableClock extends Clock {
        private long ms;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(ms); }
    }

    private final MutableClock clock = new MutableClock();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RedisUnavailableReporter.class);
    private final RedisUnavailableReporter reporter = new RedisUnavailableReporter(60, 2, clock);

    @BeforeEach
    void attach() { appender.start(); logger.addAppender(appender); }

    @AfterEach
    void detach() { logger.detachAppender(appender); appender.stop(); }

    private long count(Level level, String text) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level && e.getFormattedMessage().contains(text)).count();
    }

    private RuntimeException failure() { return new RuntimeException("Unable to connect to Redis"); }

    @Test
    void firstReportEmitsExactlyOneWarn() {
        reporter.report(failure());
        assertEquals(1, count(Level.WARN, "Redis 不可用"));
        assertEquals(0, count(Level.INFO, "Redis 已恢复"));
    }

    @Test
    void sameWindowSuppressesAndCounts() {
        reporter.report(failure());
        clock.ms += 1000;
        reporter.report(failure());
        clock.ms += 1000;
        reporter.report(failure());
        assertEquals(1, count(Level.WARN, "Redis 不可用"), "同窗口 3 次 report 只 1 条 WARN");
        reporter.clear();
        assertEquals(1, count(Level.INFO, "期间抑制 2 条"), "抑制计数应为 2");
    }

    @Test
    void crossingWindowEmitsSecondWarnWithSuppressedCount() {
        reporter.report(failure());
        clock.ms += 1000;
        reporter.report(failure());
        clock.ms += 1000;
        reporter.report(failure());
        clock.ms += 60_000; // 跨过 60s 窗口
        reporter.report(failure());
        assertEquals(2, count(Level.WARN, "Redis 不可用"), "跨窗口后应再打一条心跳式 WARN");
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("窗口内已抑制 2 条")), "第 2 条 WARN 应带抑制计数 2");
    }

    @Test
    void clearAfterFailureEmitsOneInfoWithElapsed() {
        reporter.report(failure());
        clock.ms += 5000;
        reporter.clear();
        assertEquals(1, count(Level.INFO, "Redis 已恢复"));
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("首次失败后 5s")), "INFO 应带首次失败后秒数");
    }

    @Test
    void clearInNormalStateIsSilent() {
        reporter.clear();
        reporter.clear();
        assertEquals(0, appender.list.size(), "正常态 clear() 必须零日志");
    }

    @Test
    void failureAfterClearWarnsAgain() {
        reporter.report(failure());
        clock.ms += 1000;
        reporter.clear();
        clock.ms += 1000;
        reporter.report(failure());
        assertEquals(2, count(Level.WARN, "Redis 不可用"), "clear 复位后再故障应重新 WARN");
        assertEquals(1, count(Level.INFO, "Redis 已恢复"), "恢复 INFO 仍只有 1 条");
    }
}