package com.meetingnotes.worker;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * T4.86/P-26：TaskLogContext 的 MDC 读写口径——open 双键齐写 / requestId 空白跳过（不写 "null"
 * 字符串）/ close 只清本类两键（不用 clear()）。
 */
class TaskLogContextTest {

    /** open("42","abc") → 两键都在。 */
    @Test
    void openWritesBothKeys() {
        TaskLogContext.open("42", "abc");
        assertEquals("42", MDC.get("taskId"));
        assertEquals("abc", MDC.get("requestId"));
        TaskLogContext.close();
    }

    /** open("42", null) → 只有 taskId，requestId 键不存在（不写 "null" 字符串）。 */
    @Test
    void openWithNullRequestIdSkipsKey() {
        TaskLogContext.open("42", null);
        assertEquals("42", MDC.get("taskId"));
        assertNull(MDC.get("requestId"));
        TaskLogContext.close();
    }

    /** 空白 requestId 同样跳过（历史任务空串口径）。 */
    @Test
    void openWithBlankRequestIdSkipsKey() {
        TaskLogContext.open("42", "   ");
        assertEquals("42", MDC.get("taskId"));
        assertNull(MDC.get("requestId"));
        TaskLogContext.close();
    }

    /** close() → 两键都清空。 */
    @Test
    void closeRemovesBothKeys() {
        TaskLogContext.open("42", "abc");
        TaskLogContext.close();
        assertNull(MDC.get("taskId"));
        assertNull(MDC.get("requestId"));
    }
}
