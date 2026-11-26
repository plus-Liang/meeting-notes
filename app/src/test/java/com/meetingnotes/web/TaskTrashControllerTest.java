package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import com.meetingnotes.task.TaskTrashService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.96/C11：TaskTrashController 直测（mock Service）——4 端点响应键逐字
 * （restored/results/purged）、404 TASK_NOT_FOUND、鉴权口径 = UserContext（401 由 ApiTokenFilter 横切）。
 */
@ExtendWith(MockitoExtension.class)
class TaskTrashControllerTest {

    @Mock
    private TaskTrashService service;

    @BeforeEach
    void setUp() {
        UserContext.set(1L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void restoreReturnsRestoredTrue() {
        Map<String, Object> resp = new TaskTrashController(service).restore(7L);
        assertEquals(true, resp.get("restored"), "响应键 = restored（逐字）");
        verify(service).restore(7L);
    }

    @Test
    void restoreMissingReturns404TaskNotFound() {
        doThrow(new ApiException(404, "TASK_NOT_FOUND", "不存在或不在回收站")).when(service).restore(9L);

        ApiException ex = assertThrows(ApiException.class, () -> new TaskTrashController(service).restore(9L));
        assertEquals(404, ex.getStatus());
        assertEquals("TASK_NOT_FOUND", ex.getCode());
    }

    @Test
    void batchRestoreReturnsPerItemResults() {
        when(service.restoreBatch(List.of(1L, 2L, 3L))).thenReturn(List.of(1L, 3L));

        Map<String, Object> resp = new TaskTrashController(service).restoreBatch(new RetryRequest(List.of(1L, 2L, 3L)));

        @SuppressWarnings("unchecked")
        List<TaskTrashController.RestoreItemResult> results = (List<TaskTrashController.RestoreItemResult>) resp.get("results");
        assertEquals(3, results.size(), "逐条结果（部分成功口径）");
        assertEquals(1L, results.get(0).id());
        assertTrue(results.get(0).restored());
        assertEquals(2L, results.get(1).id());
        assertEquals(false, results.get(1).restored());
        assertEquals("NOT_FOUND", results.get(1).reason(), "失败 reason 固定值");
        assertEquals(3L, results.get(2).id());
        assertTrue(results.get(2).restored());
    }

    @Test
    void purgeReturnsPurgedTrue() {
        when(service.purge(5L)).thenReturn(true);

        Map<String, Object> resp = new TaskTrashController(service).purge(5L);
        assertEquals(true, resp.get("purged"), "响应键 = purged（逐字）");
    }

    @Test
    void purgeNotInTrashReturns404() {
        when(service.purge(6L)).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> new TaskTrashController(service).purge(6L));
        assertEquals(404, ex.getStatus());
        assertEquals("TASK_NOT_FOUND", ex.getCode());
    }

    @Test
    void purgeAllReturnsCount() {
        when(service.purgeAll()).thenReturn(4);

        Map<String, Object> resp = new TaskTrashController(service).purgeAll();
        assertEquals(4, resp.get("purged"), "响应键 = purged（计数，逐字）");
    }
}
