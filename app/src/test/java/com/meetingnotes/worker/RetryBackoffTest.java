package com.meetingnotes.worker;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** T4.100/C3/C9-1：退避纯函数 + 配置校验回退（含 WARN 含实际值）。 */
class RetryBackoffTest {

    private static long sec(RetryBackoff b, int attempts) {
        return b.delayFor(attempts).getSeconds();
    }

    @Test
    void defaultsAreExponentialWithCap() {
        RetryBackoff b = new RetryBackoff(30, 300, 5000);
        assertEquals(30, sec(b, 1));
        assertEquals(60, sec(b, 2));
        assertEquals(120, sec(b, 3));
        assertEquals(240, sec(b, 4));
        assertEquals(300, sec(b, 5)); // cap
    }

    @Test
    void nonPositiveAttemptsTreatedAsOne() {
        assertEquals(30, sec(new RetryBackoff(30, 300, 5000), 0));
        assertEquals(30, sec(new RetryBackoff(30, 300, 5000), -5));
    }

    @Test
    void hugeAttemptsReturnMaxWithoutOverflow() {
        assertEquals(300, sec(new RetryBackoff(30, 300, 5000), 31));
        assertEquals(300, sec(new RetryBackoff(30, 300, 5000), Integer.MAX_VALUE));
    }

    @Test
    void customBaseAndMaxCapCorrectly() {
        RetryBackoff b = new RetryBackoff(3, 5, 5000);
        assertEquals(3, sec(b, 1));
        assertEquals(5, sec(b, 2));
        assertEquals(5, sec(b, 3));
    }

    @Test
    void invalidBaseFallsBackWithWarnContainingActualValues() {
        Logger logger = (Logger) LoggerFactory.getLogger(RetryBackoff.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            RetryBackoff b = new RetryBackoff(0, 5, 5000); // base<1 且 max<base → 回退 30/300
            assertEquals(30, sec(b, 1));
            assertEquals(300, sec(b, 10));
            org.junit.jupiter.api.Assertions.assertTrue(appender.list.stream()
                            .anyMatch(e -> e.getFormattedMessage().contains("0") && e.getFormattedMessage().contains("5")),
                    "WARN 须含两个实际值（base=0, max=5），实测：" + appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void maxLessThanBaseFallsBackTo300() {
        RetryBackoff b = new RetryBackoff(30, 5, 5000);
        assertEquals(30, sec(b, 1)); // 回退 base=30
        assertEquals(60, sec(b, 2));
        assertEquals(300, sec(b, 5)); // 回退 max=300（cap）
    }

    @Test
    void invalidScanIntervalFallsBackTo5000() {
        assertEquals(5000L, new RetryBackoff(30, 300, 100).scanIntervalMs());
        assertEquals(5000L, new RetryBackoff(30, 300, 0).scanIntervalMs());
        assertEquals(1000L, new RetryBackoff(30, 300, 1000).scanIntervalMs());
    }
}
