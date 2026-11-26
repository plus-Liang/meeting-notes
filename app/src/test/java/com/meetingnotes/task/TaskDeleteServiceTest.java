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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.21（D-115）+ T4.96/C2（D-245 软删除语义迁移）：任务删除——单条 404/409/软删成功、
 * 批量 1..100 去重逐条部分成功、清空失败任务只处理未删除的 DEAD；配额不退还（文档口径）。
 * 可证伪断言：TASK_BUSY 路径**零 UPDATE**；成功路径 = 一条 `UPDATE … SET deleted_at`、
 * **零 DELETE FROM**、**零 S3 调用**（子表与对象保留，恢复时还在——物理删除收敛到 TaskTrashService.purge）。
 * 语义迁移（T4.96 卡面预授权的唯一一种）：原「4 表 DELETE 按序」断言改为「软删 UPDATE + 零 DELETE」。
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


    @org.junit.jupiter.api.BeforeEach
    void setUpUserContextT437a() {
        com.meetingnotes.common.UserContext.set(1L);
    }

    @org.junit.jupiter.api.AfterEach
    void clearUserContextT437a() {
        com.meetingnotes.common.UserContext.clear();
    }

    @Test
    void deleteMissingTaskReturnsNotFoundWithoutAnyWrite() {
        stubStatus(9L, null);

        TaskDeleteService.DeleteOutcome out = newService().deleteOne(9L);

        assertFalse(out.deleted());
        assertEquals("NOT_FOUND", out.reason());
        verify(jdbc, never()).update(contains("DELETE FROM"), any(Object[].class));
        verify(jdbc, never()).update(contains("deleted_at"), any(Object[].class));
        verifyNoS3Calls();
    }

    @Test
    void busyTaskReturnsTaskBusyWithoutAnyWrite() {
        stubStatus(8L, "RUNNING");

        TaskDeleteService.DeleteOutcome out = newService().deleteOne(8L);

        assertFalse(out.deleted());
        assertEquals("TASK_BUSY", out.reason());
        verify(jdbc, never()).update(contains("DELETE FROM"), any(Object[].class));
        verify(jdbc, never()).update(contains("deleted_at"), any(Object[].class));
        verifyNoS3Calls();
    }

    /** T4.96/C2：软删成功 = 一条 `UPDATE … SET deleted_at`，**零 DELETE FROM、零 S3 调用**
     *  （4 张子表行与 MinIO 对象都在，恢复时原样回来）。 */
    @Test
    void successSoftDeletesViaDeletedAtWithoutDeletingRowsOrObject() {
        stubStatus(7L, "DEAD");
        stubSourceUri(7L, "s3://meetingnotes/tasks/7/a.wav");

        TaskDeleteService.DeleteOutcome out = newService().deleteOne(7L);

        assertTrue(out.deleted());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(1)).update(sql.capture(), eq(7L), eq(1L));
        assertTrue(sql.getValue().contains("deleted_at = now()"), "删除 = 置 deleted_at（软删除，D-245）");
        assertTrue(sql.getValue().contains("UPDATE task SET"), "必须是 UPDATE，不是 DELETE");
        // 子表与对象保留：本类不再出现任何 DELETE / S3 调用（物理删收敛到 TaskTrashService.purge）
        verify(jdbc, never()).update(contains("DELETE FROM"), any(Object[].class));
        verifyNoS3Calls();
    }

    /** T4.96：purgeObjectBestEffort 保留（供 TaskTrashService.purge 复用），行为不变。 */
    @Test
    void s3FailureIsBestEffortAndDoesNotThrow() {
        doThrow(new RuntimeException("MinIO 不可达")).when(s3)
                .deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));

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
        assertTrue(out.get(0).deleted(), "DEAD → 软删成功");
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

    /** T4.96/C2-5：清空失败只处理 `deleted_at IS NULL` 的 DEAD 行（回收站任务由回收站出口清理）。 */
    @Test
    void deleteAllFailedOnlyTouchesDeadTasksNotInTrash() {
        when(jdbc.query(contains("status = 'DEAD' AND deleted_at IS NULL"),
                ArgumentMatchers.<RowMapper<Long>>any(), eq(1L)))
                .thenReturn(List.of(10L, 11L));
        stubStatus(10L, "DEAD");
        stubSourceUri(10L, "s3://meetingnotes/tasks/10/a.wav");
        stubStatus(11L, "DEAD");
        stubSourceUri(11L, "s3://meetingnotes/tasks/11/b.wav");

        int deleted = newService().deleteAllFailed();

        assertEquals(2, deleted, "只软删查询出的 DEAD 行（未在回收站）");
        verify(jdbc).update(contains("deleted_at = now()"), eq(10L), eq(1L));
        verify(jdbc).update(contains("deleted_at = now()"), eq(11L), eq(1L));
        verify(jdbc, never()).update(contains("DELETE FROM"), any(Object[].class));
    }

    /** T4.96 语义迁移：软删**不再删 MinIO 对象**（回收站要能恢复；对象删除只发生在 purge）。 */
    @Test
    void deleteAllFailedDoesNotTouchS3Anymore() {
        when(jdbc.query(contains("status = 'DEAD' AND deleted_at IS NULL"),
                ArgumentMatchers.<RowMapper<Long>>any(), eq(1L)))
                .thenReturn(List.of(20L, 21L));
        stubStatus(20L, "DEAD");
        stubSourceUri(20L, "s3://meetingnotes/tasks/20/a.mp3");
        stubStatus(21L, "DEAD");
        stubSourceUri(21L, "s3://meetingnotes/tasks/21/b.mp3");

        int deleted = newService().deleteAllFailed();

        assertEquals(2, deleted, "软删计数不变（删除语义=进回收站）");
        verifyNoS3Calls();
    }

    /** T4.21b 语义迁移：原「S3 失败不影响删除」用例在软删下不再触发 S3——改为纯 DB 断言。 */
    @Test
    void deleteAllFailedReturnsCountWithoutS3Interaction() {
        when(jdbc.query(contains("status = 'DEAD' AND deleted_at IS NULL"),
                ArgumentMatchers.<RowMapper<Long>>any(), eq(1L)))
                .thenReturn(List.of(30L, 31L));
        stubStatus(30L, "DEAD");
        stubSourceUri(30L, "s3://meetingnotes/tasks/30/a.wav");
        stubStatus(31L, "DEAD");
        stubSourceUri(31L, "s3://meetingnotes/tasks/31/b.wav");

        int deleted = newService().deleteAllFailed();

        assertEquals(2, deleted);
        verify(jdbc).update(contains("deleted_at = now()"), eq(30L), eq(1L));
        verify(jdbc).update(contains("deleted_at = now()"), eq(31L), eq(1L));
        verifyNoS3Calls();
    }

    private void verifyNoS3Calls() {
        verify(s3, never()).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }
}
