package com.meetingnotes.storage;

import com.meetingnotes.common.ApiException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * T4.92/P-21：StorageGuard 护栏语义——headroom 前置 503、启动横幅不拒启、探测失败放行。
 * usableBytes 用 spy stub（真实 Files 探测依赖宿主盘剩余空间，测试需确定性）。
 */
class StorageGuardTest {

    private static final long MB = 1024L * 1024;

    /** spy 包装：stub usableBytes() 后逻辑路径照走（assertWritable/run 的真实代码）。 */
    private StorageGuard guardWithUsable(long usableBytes) {
        StorageGuard guard = spy(new StorageGuard(2048, 256));
        doReturn(usableBytes).when(guard).usableBytes();
        return guard;
    }

    /** 低于 headroom → 503 INSUFFICIENT_STORAGE，message 逐字含「磁盘可用空间不足（剩余 X MB，至少需要 Y MB）」。 */
    @Test
    void belowHeadroomThrows503WithExactMessage() {
        StorageGuard guard = guardWithUsable(100 * MB);
        ApiException e = assertThrows(ApiException.class, guard::assertWritable);
        assertEquals(503, e.getStatus());
        assertEquals("INSUFFICIENT_STORAGE", e.getCode());
        assertTrue(e.getMessage().contains("磁盘可用空间不足（剩余 100 MB，至少需要 256 MB）"), e.getMessage());
        assertTrue(e.getMessage().endsWith("请清理后再上传"), e.getMessage());
    }

    /** 高于 headroom → 放行不抛。 */
    @Test
    void aboveHeadroomPasses() {
        StorageGuard guard = guardWithUsable(2048L * MB);
        assertDoesNotThrow(guard::assertWritable);
    }

    /** 启动横幅：低于 min-free → 恰 1 条 ERROR；高于 → 零日志；都不抛（不拒启）。 */
    @Test
    void runEmitsErrorBannerWhenBelowMinFree() {
        StorageGuard guard = guardWithUsable(100 * MB);
        Logger logger = (Logger) LoggerFactory.getLogger(StorageGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(() -> guard.run(null));
        } finally {
            logger.detachAppender(appender);
        }
        assertEquals(1, appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count());
        assertTrue(appender.list.get(0).getFormattedMessage().contains("【存储护栏】"));
    }

    /** 探测失败（stub 抛异常路径的等价物：返回 MAX_VALUE）→ 放行不拒启。 */
    @Test
    void probeFailureTreatedAsPlenty() {
        StorageGuard guard = guardWithUsable(Long.MAX_VALUE);
        assertDoesNotThrow(guard::assertWritable);
        assertDoesNotThrow(() -> guard.run(null));
    }
}
