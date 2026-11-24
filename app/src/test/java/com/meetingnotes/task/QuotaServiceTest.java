package com.meetingnotes.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.1 设置页数据源：QuotaService.currentUsage 与 consume 的 CURRENT_DATE 口径一致
 * （无行 = 今日未消费 → 0/0）。
 */
@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private TransactionTemplate tx;

    @org.junit.jupiter.api.BeforeEach
    void stubTx() {
        // 单事务包装在单测里直接执行回调（真回滚语义由隔离栈 A4/A7 验）
        org.mockito.Mockito.lenient().when(tx.execute(any()))
                .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
    }

    private static java.sql.ResultSet row(long userId, int durationSec, Date day) throws Exception {
        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(userId);
        when(rs.getInt(2)).thenReturn(durationSec);
        org.mockito.Mockito.lenient().when(rs.getDate(3)).thenReturn(day); // consumeForTask 不读 day
        return rs;
    }

    @Test
    void currentUsageReturnsTodayRowValues() {
        when(jdbc.query(contains("FROM quota WHERE user_id"),
                ArgumentMatchers.<ResultSetExtractor<QuotaService.Usage>>any(), eq(1L)))
                .thenReturn(new QuotaService.Usage(65, 10));
        QuotaService service = new QuotaService(jdbc, 120, 50, tx);

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
        QuotaService service = new QuotaService(jdbc, 120, 50, tx);

        QuotaService.Usage usage = service.currentUsage(1L);

        assertEquals(0, usage.usedMinutes(), "今日未消费 → 0（不许把昨天的算进来）");
        assertEquals(0, usage.usedTasks());
    }

    /* ---- T4.87 C2/C8：失败退还（幂等 / clamp / PASTE / 跨天） ---- */

    @Test
    void refundSecondCallReturnsFalseAndDoesNotDeductTwice() throws Exception {
        when(jdbc.query(contains("UPDATE task SET quota_refunded = TRUE"),
                ArgumentMatchers.<ResultSetExtractor<Object>>any(), eq(7L)))
                .thenAnswer(inv -> ((ResultSetExtractor<Object>) inv.getArgument(1))
                        .extractData(row(1L, 120, Date.valueOf(LocalDate.now()))))
                .thenReturn(null); // 第二次翻转 0 行 → 已退过
        QuotaService service = new QuotaService(jdbc, 120, 50, tx);

        assertEquals(true, service.refundForTask(7L));
        assertEquals(false, service.refundForTask(7L), "已退过 → false（幂等，不抛）");
        verify(jdbc, times(1)).update(contains("GREATEST(0, used_minutes - ?)"),
                eq(2), eq(1L), any(Date.class));
    }

    @Test
    void refundUsesGreatestClampAndCeilMinutes() throws Exception {
        when(jdbc.query(contains("UPDATE task SET quota_refunded = TRUE"),
                ArgumentMatchers.<ResultSetExtractor<Object>>any(), eq(7L)))
                .thenAnswer(inv -> ((ResultSetExtractor<Object>) inv.getArgument(1))
                        .extractData(row(1L, 120, Date.valueOf(LocalDate.now()))));
        QuotaService service = new QuotaService(jdbc, 120, 50, tx);

        assertEquals(true, service.refundForTask(7L));
        // 120s → ceil=2 分钟；SQL 用 GREATEST(0,…) 保证不为负（真 clamp 由隔离栈 A2/A7 验）
        verify(jdbc).update(contains("GREATEST(0, used_minutes - ?)"), eq(2), eq(1L), any(Date.class));
    }

    @Test
    void refundPasteZeroMinutesFlipsFlagButNeverTouchesQuota() throws Exception {
        when(jdbc.query(contains("UPDATE task SET quota_refunded = TRUE"),
                ArgumentMatchers.<ResultSetExtractor<Object>>any(), eq(7L)))
                .thenAnswer(inv -> ((ResultSetExtractor<Object>) inv.getArgument(1))
                        .extractData(row(1L, 0, Date.valueOf(LocalDate.now()))));
        QuotaService service = new QuotaService(jdbc, 120, 50, tx);

        assertEquals(true, service.refundForTask(7L), "PASTE 0 分钟仍翻转 quota_refunded");
        verify(jdbc, never()).update(contains("UPDATE quota"), any(), any(), any());
    }

    @Test
    void refundLandsOnCreationDayNotToday() throws Exception {
        Date yesterday = Date.valueOf(LocalDate.now().minusDays(1));
        when(jdbc.query(contains("UPDATE task SET quota_refunded = TRUE"),
                ArgumentMatchers.<ResultSetExtractor<Object>>any(), eq(7L)))
                .thenAnswer(inv -> ((ResultSetExtractor<Object>) inv.getArgument(1))
                        .extractData(row(1L, 120, yesterday)));
        QuotaService service = new QuotaService(jdbc, 120, 50, tx);

        service.refundForTask(7L);
        verify(jdbc).update(contains("WHERE user_id = ? AND day = ?"), eq(2), eq(1L), eq(yesterday));
    }

    @Test
    void consumeForTaskReadsTaskAndConsumesCeilMinutes() throws Exception {
        when(jdbc.query(contains("SELECT user_id, duration_sec FROM task"),
                ArgumentMatchers.<ResultSetExtractor<Object>>any(), eq(7L)))
                .thenAnswer(inv -> ((ResultSetExtractor<Object>) inv.getArgument(1))
                        .extractData(row(1L, 120, null)));
        java.sql.ResultSet qrs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        when(qrs.next()).thenReturn(true);
        when(qrs.getLong(1)).thenReturn(2L);
        when(qrs.getLong(2)).thenReturn(1L);
        when(jdbc.query(contains("INSERT INTO quota"),
                ArgumentMatchers.<ResultSetExtractor<Object>>any(), eq(1L), eq(2), eq(2), eq(120L), eq(50L)))
                .thenAnswer(inv -> ((ResultSetExtractor<Object>) inv.getArgument(1)).extractData(qrs));
        QuotaService service = new QuotaService(jdbc, 120, 50, tx);

        service.consumeForTask(7L);
        verify(jdbc).query(contains("INSERT INTO quota"),
                ArgumentMatchers.<ResultSetExtractor<Object>>any(), eq(1L), eq(2), eq(2), eq(120L), eq(50L));
    }
}
