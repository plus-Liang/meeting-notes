package com.meetingnotes.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

/**
 * F-23 时区一致性预检（T4.12）：归一化比较的纯函数面 + run() 在 DB 异常时不阻塞启动。
 */
class TimezoneConsistencyCheckTest {

    @Test
    void normalizedComparisonCoversEtcPrefixAndCase() {
        assertTrue(TimezoneConsistencyCheck.isConsistent("Asia/Shanghai", "Asia/Shanghai"));
        assertTrue(TimezoneConsistencyCheck.isConsistent(" asia/shanghai ", "ASIA/SHANGHAI"), "trim+大小写无关");
        assertTrue(TimezoneConsistencyCheck.isConsistent("Etc/UTC", "UTC"), "Etc/ 前缀等价");
        assertFalse(TimezoneConsistencyCheck.isConsistent("UTC", "Asia/Shanghai"), "F-23 的老卷场景必须报不一致");
        assertFalse(TimezoneConsistencyCheck.isConsistent(null, "Asia/Shanghai"));
    }

    @Test
    void runWarnsButNeverBlocksStartupOnMismatchOrDbError() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SHOW timezone"), eq(String.class))).thenReturn("UTC");
        TimezoneConsistencyCheck check = new TimezoneConsistencyCheck(jdbc, "Asia/Shanghai");
        assertDoesNotThrow(() -> check.run(new DefaultApplicationArguments(new String[0])),
                "不一致只 WARN，不阻塞启动");
        verify(jdbc).queryForObject(eq("SHOW timezone"), eq(String.class));

        JdbcTemplate broken = mock(JdbcTemplate.class);
        when(broken.queryForObject(anyString(), eq(String.class)))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException(
                        "SHOW", "SHOW timezone", new java.sql.SQLException("H2 不支持")));
        TimezoneConsistencyCheck check2 = new TimezoneConsistencyCheck(broken, "Asia/Shanghai");
        assertDoesNotThrow(() -> check2.run(new DefaultApplicationArguments(new String[0])),
                "H2 测试上下文/DB 未就绪 → 静默跳过");
    }

    @Test
    void consistentDbTimezonePasses() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SHOW timezone"), eq(String.class))).thenReturn("Asia/Shanghai");
        TimezoneConsistencyCheck check = new TimezoneConsistencyCheck(jdbc, "Asia/Shanghai");
        assertDoesNotThrow(() -> check.run(new DefaultApplicationArguments(new String[0])));
    }
}
