package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.task.TaskDeleteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.21b：TaskDeleteController 直测（mock Service）——404/409 走 ApiException 固定码、
 * 成功 {"deleted":1} 且逐条 best-effort purge、清空失败任务回传计数。
 */
@ExtendWith(MockitoExtension.class)
class TaskDeleteControllerTest {

    @Mock
    private TaskDeleteService service;

    @Test
    void deleteTaskMissingReturns404TaskNotFound() {
        when(service.deleteOne(9L))
                .thenReturn(new TaskDeleteService.DeleteOutcome(9L, false, "NOT_FOUND", null));

        ApiException ex = assertThrows(ApiException.class,
                () -> new TaskDeleteController(service).deleteTask(9L));

        assertEquals(404, ex.getStatus());
        assertEquals("TASK_NOT_FOUND", ex.getCode());
    }

    @Test
    void deleteTaskRunningReturns409TaskBusy() {
        when(service.deleteOne(8L))
                .thenReturn(new TaskDeleteService.DeleteOutcome(8L, false, "TASK_BUSY", null));

        ApiException ex = assertThrows(ApiException.class,
                () -> new TaskDeleteController(service).deleteTask(8L));

        assertEquals(409, ex.getStatus());
        assertEquals("TASK_BUSY", ex.getCode());
    }

    /** T4.96/C2 语义迁移：软删除后 controller **不再** best-effort 删对象（回收站要能恢复）。 */
    @Test
    void deleteTaskSuccessReturnsDeletedOneWithoutTouchingObject() {
        when(service.deleteOne(7L))
                .thenReturn(new TaskDeleteService.DeleteOutcome(7L, true, null,
                        "s3://meetingnotes/tasks/7/a.wav"));

        TaskDeleteController.DeleteCountResponse resp =
                new TaskDeleteController(service).deleteTask(7L);

        assertEquals(1, resp.deleted());
        verify(service, org.mockito.Mockito.never()).purgeObjectBestEffort(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void deleteFailedReturnsCountFromService() {
        when(service.deleteAllFailed()).thenReturn(2);

        TaskDeleteController.DeleteCountResponse resp =
                new TaskDeleteController(service).deleteFailed();

        assertEquals(2, resp.deleted());
    }
}
