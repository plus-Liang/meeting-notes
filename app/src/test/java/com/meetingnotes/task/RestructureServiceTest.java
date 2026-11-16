package com.meetingnotes.task;

import com.meetingnotes.ai.StructuringService;
import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T4.26（F-17）：换模板重跑——404/409（busy+锁冲突）/400/成功覆盖/失败保留旧值/零配额（本服务自身零 UPDATE）。
 * 可证伪断言：锁查询必须带 FOR UPDATE NOWAIT；结构化失败时本服务不写任何行（事务回滚由真实 tx 保证）。
 */
@ExtendWith(MockitoExtension.class)
class RestructureServiceTest {

    private static final String TRANSCRIPT = "由张三负责整理材料，下周三截止。";

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private TransactionTemplate tx;
    @Mock
    private StructuringService structuringService;

    private RestructureService newService() {
        lenient().when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction((TransactionStatus) null));
        return new RestructureService(jdbc, tx, structuringService);
    }

    private void stubRowLock(String status) {
        when(jdbc.query(contains("FOR UPDATE NOWAIT"), ArgumentMatchers.<ResultSetExtractor<String>>any(),
                eq(7L), eq(1L))).thenReturn(status);
    }

    private void stubTranscript(String transcript) {
        when(jdbc.query(contains("SELECT transcript FROM task_result"), ArgumentMatchers.<ResultSetExtractor<String>>any(),
                eq(7L))).thenReturn(transcript);
    }

    @Test
    void missingTaskReturns404WithoutStructuring() {
        stubRowLock(null);

        ApiException e = assertThrows(ApiException.class,
                () -> newService().restructure(1L, 7L, "course"));

        assertEquals(404, e.getStatus());
        assertEquals("TASK_NOT_FOUND", e.getCode());
        verifyNoInteractions(structuringService);
    }

    @Test
    void nonDoneTaskReturns409TaskBusy() {
        stubRowLock("RUNNING");

        ApiException e = assertThrows(ApiException.class,
                () -> newService().restructure(1L, 7L, "course"));

        assertEquals(409, e.getStatus());
        assertEquals("TASK_BUSY", e.getCode());
        verifyNoInteractions(structuringService);
    }

    @Test
    void lockConflictReturns409TaskBusy() {
        when(jdbc.query(contains("FOR UPDATE NOWAIT"), ArgumentMatchers.<ResultSetExtractor<String>>any(),
                eq(7L), eq(1L))).thenThrow(new CannotAcquireLockException("行锁被并发请求持有"));

        ApiException e = assertThrows(ApiException.class,
                () -> newService().restructure(1L, 7L, "course"));

        assertEquals(409, e.getStatus());
        assertEquals("TASK_BUSY", e.getCode());
        verifyNoInteractions(structuringService);
    }

    @Test
    void pgNowaitConflictViaSqlStateReturns409() {
        // K-79：生产实锤——PG NOWAIT 冲突抛的是 UncategorizedSQLException(SQLState 55P03)，不是 CannotAcquireLockException
        when(jdbc.query(contains("FOR UPDATE NOWAIT"), ArgumentMatchers.<ResultSetExtractor<String>>any(),
                eq(7L), eq(1L)))
                .thenThrow(new org.springframework.jdbc.UncategorizedSQLException("SELECT ...",
                        "SELECT status FROM task WHERE id = ? AND user_id = ? FOR UPDATE NOWAIT",
                        new java.sql.SQLException("ERROR: could not obtain lock on row in relation \"task\"", "55P03")));

        ApiException e = assertThrows(ApiException.class,
                () -> newService().restructure(1L, 7L, "course"));

        assertEquals(409, e.getStatus(), "SQLState 55P03 必须判为锁冲突 → 409");
        assertEquals("TASK_BUSY", e.getCode());
        verifyNoInteractions(structuringService);
    }

    @Test
    void nonLockConflictSqlStateIsRethrownNotConvertedTo409() {
        // 反例（K-79 反向纪律）：非锁冲突的 DataAccessException（如 42P01 表不存在）必须原样抛，禁止 catch-all 转 409
        when(jdbc.query(contains("FOR UPDATE NOWAIT"), ArgumentMatchers.<ResultSetExtractor<String>>any(),
                eq(7L), eq(1L)))
                .thenThrow(new org.springframework.jdbc.UncategorizedSQLException("SELECT ...",
                        "SELECT status FROM task WHERE id = ? AND user_id = ? FOR UPDATE NOWAIT",
                        new java.sql.SQLException("ERROR: relation \"task\" does not exist", "42P01")));

        org.springframework.dao.DataAccessException e = assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> newService().restructure(1L, 7L, "course"));

        assertEquals(org.springframework.jdbc.UncategorizedSQLException.class, e.getClass(),
                "非锁冲突必须原样抛出（保持 500），不得转成 409 ApiException");
        verifyNoInteractions(structuringService);
    }

    @Test
    void blankTranscriptReturns400InvalidParam() {
        stubRowLock("DONE");
        stubTranscript("   ");

        ApiException e = assertThrows(ApiException.class,
                () -> newService().restructure(1L, 7L, "course"));

        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());
        verifyNoInteractions(structuringService);
    }

    @Test
    void successReturnsResponseAndPassesExplicitTemplateToStructuring() {
        stubRowLock("DONE");
        stubTranscript(TRANSCRIPT);
        when(structuringService.structure(7L, TRANSCRIPT, "course")).thenReturn(true);
        when(jdbc.queryForObject(contains("SELECT ai_tier"), eq(String.class), eq(7L))).thenReturn("CLOUD");

        RestructureService.RestructureResponse resp = newService().restructure(1L, 7L, "course");

        assertEquals(7L, resp.taskId());
        assertEquals("course", resp.template());
        assertEquals("CLOUD", resp.aiTier());
        verify(structuringService).structure(7L, TRANSCRIPT, "course");
    }

    @Test
    void structuringFailureThrows500AndWritesNothingItself() {
        stubRowLock("DONE");
        stubTranscript(TRANSCRIPT);
        when(structuringService.structure(7L, TRANSCRIPT, "course")).thenReturn(false);

        ApiException e = assertThrows(ApiException.class,
                () -> newService().restructure(1L, 7L, "course"));

        assertEquals(500, e.getStatus());
        assertEquals("INTERNAL_ERROR", e.getCode());
        assertTrue(e.getMessage().contains("已保留原"), "失败信息必须说明旧结果已保留（不写半截）");
        verify(jdbc, never()).update(ArgumentMatchers.anyString(),
                ArgumentMatchers.any(Object[].class));
    }

    @Test
    void zeroQuotaByDesignNoQuotaTouches() {
        stubRowLock("DONE");
        stubTranscript(TRANSCRIPT);
        when(structuringService.structure(7L, TRANSCRIPT, "podcast")).thenReturn(true);
        when(jdbc.queryForObject(contains("SELECT ai_tier"), eq(String.class), eq(7L))).thenReturn("EXTRACTIVE");

        newService().restructure(1L, 7L, "podcast");

        verify(jdbc, never()).update(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object[].class));
        verify(jdbc, never()).update(contains("quota"), ArgumentMatchers.any(Object[].class));
        verify(jdbc, never()).update(contains("INSERT"), ArgumentMatchers.any(Object[].class));
    }
}
