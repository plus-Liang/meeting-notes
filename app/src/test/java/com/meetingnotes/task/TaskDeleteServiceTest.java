package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.21（D-115）：任务删除——单条 404/409/成功（4 表同事务按 FK 顺序）、批量 1..100 去重逐条
 * 部分成功、清空失败任务只删 DEAD；MinIO best-effort（失败只 WARN 不回滚）；配额不退还（文档口径）。
 * 可证伪断言：TASK_BUSY 路径**零 DELETE**；成功路径 4 条 DELETE 按序；S3 异常不向上抛。
 */
@ExtendWith(MockitoExtension.class)
class TaskDeleteServiceTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private TransactionTemplate tx;
    @Mock
    private S3Client s3;

    private TaskDeleteService newService() {
        // TransactionTemplate mock：直接执行回调（等价真实事务路径）；lenient（部分用例不进事务）
        lenient().when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction((TransactionStatus) null));
        return new TaskDeleteService(jdbc, tx, s3, "meetingnotes");
    }

    private void stubStatus(long id, String status) {
        when(jdbc.query(contains("SELECT status FROM task"),
                ArgumentMatchers.<ResultSetExtractor<String>>any(), eq(id), eq(1L)))
                .thenReturn(status);
    }

    private void stubSourceUri(long id, String uri) {
        when(jdbc.query(contains("SELECT COALESCE(source_uri"),
                ArgumentMatchers.<ResultSetExtractor<String>>any(), eq(id)))
                .thenReturn(uri);
    }

    @Test
    void deleteMissingTaskReturnsNotFoundWithoutAnyDelete() {
        stubStatus(9L, null);

        TaskDeleteService.DeleteOutcome out = newService().deleteOne(9L);

        assertFalse(out.deleted());
        assertEquals("NOT_FOUND", out.reason());
        verify(jdbc, never()).update(contains("DELETE FROM"), any(Object[].class));
        verifyNoS3Calls();
    }

    @Test
    void busyTaskReturnsTaskBusyWithoutAnyDelete() {
        stubStatus(8L, "RUNNING");

        TaskDeleteService.DeleteOutcome out = newService().deleteOne(8L);

        assertFalse(out.deleted());
        assertEquals("TASK_BUSY", out.reason());
        verify(jdbc, never()).update(contains("DELETE FROM"), any(Object[].class));
        verifyNoS3Calls();
    }

    @Test
    void successDeletesFourTablesInOrderThenPurgesObject() {
        stubStatus(7L, "DEAD");
        stubSourceUri(7L, "s3://meetingnotes/tasks/7/a.wav");

        TaskDeleteService svc = newService();
        TaskDeleteService.DeleteOutcome out = svc.deleteOne(7L);

        assertTrue(out.deleted());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(4)).update(sql.capture(), eq(7L));
        List<String> statements = sql.getAllValues();
        assertTrue(statements.get(0).contains("webhook_delivery"), "先删 webhook_delivery");
        assertTrue(statements.get(1).contains("dead_letter"), "再删 dead_letter");
        assertTrue(statements.get(2).contains("task_result"), "再删 task_result");
        assertTrue(statements.get(3).contains("DELETE FROM task WHERE"), "最后删 task");
        // purge 由 controller 层编排（deleteOne 只删 DB；purge 是独立 best-effort 步）
        svc.purgeObjectBestEffort(7L, out.sourceUri());
        verify(s3).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }

    @Test
    void s3FailureIsBestEffortAndDoesNotThrow() {
        // purge 不查 DB（DB 行已删），无需 status/sourceUri 打桩
        doThrow(new RuntimeException("MinIO 不可达")).when(s3)
                .deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));

        // 不抛：DB 行已删，对象删除失败只 WARN（best-effort 口径）
        newService().purgeObjectBestEffort(6L, "s3://meetingnotes/tasks/6/a.wav");
        verify(s3, times(1)).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }

    @Test
    void batchDedupesAndReturnsPerItemResultsWithoutFailingWhole() {
        stubStatus(3L, "DEAD");
        stubSourceUri(3L, "s3://meetingnotes/tasks/3/a.wav");
        stubStatus(4L, "RUNNING");
        stubStatus(99L, null);

        List<TaskDeleteService.DeleteOutcome> out =
                newService().deleteBatch(List.of(3L, 4L, 3L, 99L, 4L));

        assertEquals(3, out.size(), "重复 id 去重（3/4/99）");
        assertEquals(3L, out.get(0).id());
        assertTrue(out.get(0).deleted(), "DEAD → 删除成功");
        assertEquals(4L, out.get(1).id());
        assertFalse(out.get(1).deleted());
        assertEquals("TASK_BUSY", out.get(1).reason(), "RUNNING → TASK_BUSY，不整体失败");
        assertEquals(99L, out.get(2).id());
        assertEquals("NOT_FOUND", out.get(2).reason());
    }

    @Test
    void batchRejectsEmptyOrOver100() {
        TaskDeleteService svc = newService();
        assertEquals(400, assertThrows(ApiException.class, () -> svc.deleteBatch(List.of())).getStatus());
        List<Long> tooMany = new java.util.ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            tooMany.add(i);
        }
        assertEquals(400, assertThrows(ApiException.class, () -> svc.deleteBatch(tooMany)).getStatus());
    }

    @Test
    void deleteAllFailedOnlyTouchesDeadTasks() {
        when(jdbc.query(contains("status = 'DEAD'"),
                ArgumentMatchers.<RowMapper<Long>>any(), eq(1L)))
                .thenReturn(List.of(10L, 11L));
        stubStatus(10L, "DEAD");
        stubSourceUri(10L, "s3://meetingnotes/tasks/10/a.wav");
        stubStatus(11L, "DEAD");
        stubSourceUri(11L, "s3://meetingnotes/tasks/11/a.wav");

        int deleted = newService().deleteAllFailed();

        assertEquals(2, deleted, "只删查询出的 DEAD 行");
        verify(jdbc).update(contains("DELETE FROM task WHERE"), eq(10L));
        verify(jdbc).update(contains("DELETE FROM task WHERE"), eq(11L));
    }

    private void verifyNoS3Calls() {
        verify(s3, never()).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }
}
