package com.meetingnotes.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * T4.1 设置页数据源：QuotaService.currentUsage 与 consume 的 CURRENT_DATE 口径一致
 * （无行 = 今日未消费 → 0/0）。
 */
@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @Test
    void currentUsageReturnsTodayRowValues() {
        when(jdbc.query(contains("FROM quota WHERE user_id"),
                ArgumentMatchers.<ResultSetExtractor<QuotaService.Usage>>any(), eq(1L)))
                .thenReturn(new QuotaService.Usage(65, 10));
        QuotaService service = new QuotaService(jdbc, 120, 50);

        QuotaService.Usage usage = service.currentUsage(1L);

        assertEquals(65, usage.usedMinutes());
        assertEquals(10, usage.usedTasks());
        assertEquals(120, service.minutesPerDay());
        assertEquals(50, service.tasksPerDay());
    }

    @Test
    void noRowTodayMeansZeroUsage() throws Exception {
        // 真正走 extractor 的空结果集分支（mock jdbc 直返 null 会绕过 "无行 → 0/0" 的兜底代码）
        java.sql.ResultSet emptyRs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        when(jdbc.query(contains("FROM quota WHERE user_id"),
                ArgumentMatchers.<ResultSetExtractor<QuotaService.Usage>>any(), eq(1L)))
                .thenAnswer(inv -> ((ResultSetExtractor<QuotaService.Usage>) inv.getArgument(1)).extractData(emptyRs));
        QuotaService service = new QuotaService(jdbc, 120, 50);

        QuotaService.Usage usage = service.currentUsage(1L);

        assertEquals(0, usage.usedMinutes(), "今日未消费 → 0（不许把昨天的算进来）");
        assertEquals(0, usage.usedTasks());
    }
}
