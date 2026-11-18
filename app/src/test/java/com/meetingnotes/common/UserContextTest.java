package com.meetingnotes.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T4.37a：UserContext——set/current/clear；未设置时显式失败（不静默回退 1，卡面口径）。
 */
class UserContextTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void setThenCurrentReturnsValue() {
        UserContext.set(42L);
        assertEquals(42L, UserContext.current());
    }

    @Test
    void unsetCurrentThrowsIllegalState() {
        assertThrows(IllegalStateException.class, UserContext::current,
                "未设置必须显式失败，防止漏改的端点悄悄变回单用户");
    }

    @Test
    void clearRemovesValue() {
        UserContext.set(7L);
        assertDoesNotThrow(UserContext::clear);
        assertThrows(IllegalStateException.class, UserContext::current, "clear 后不可再读");
    }

    @Test
    void valueIsThreadLocalAndDoesNotLeakAcrossThreads() throws Exception {
        UserContext.set(9L);
        StringBuilder seen = new StringBuilder();
        Thread other = new Thread(() -> {
            try {
                UserContext.current();
                seen.append("no-throw");
            } catch (IllegalStateException e) {
                seen.append("throw");
            }
        });
        other.start();
        other.join();
        assertEquals("throw", seen.toString(), "其它线程读不到（ThreadLocal 隔离）");
        assertEquals(9L, UserContext.current(), "本线程值不受影响");
    }
}
