package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4.29：meta 更新/读取——COALESCE 保留未提供键、404 口径、坏 JSON 降级。 */
@ExtendWith(MockitoExtension.class)
class TaskMetaServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private TaskMetaService newService() {
        return new TaskMetaService(jdbc);
    }

    @Test
    void updateWritesLabelsAndReadsBack() {
        when(jdbc.query(contains("SELECT 1 FROM task"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Integer>>any(),
                eq(7L), eq(1L))).thenReturn(1);
        // COALESCE 更新 + 回读（labels::text）
        when(jdbc.query(contains("labels::text AS labels"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<TaskMetaService.MetaRow>>any(),
                eq(7L), eq(1L))).thenReturn(java.util.List.of(new TaskMetaService.MetaRow(
                "[\"课程\",\"重点\"]", true)));

        TaskMetaService.TaskMeta meta = newService().update(1L, 7L, java.util.List.of("课程", "重点"), true);

        assertEquals(7L, meta.taskId());
        assertEquals(java.util.List.of("课程", "重点"), meta.labels());
        assertTrue(meta.favorite());
        verify(jdbc).update(contains("COALESCE"), eq("[\"课程\",\"重点\"]"), eq(true), eq(7L), eq(1L));
    }

    @Test
    void updateKeepsUnprovidedKeyViaCoalesce() {
        when(jdbc.query(contains("SELECT 1 FROM task"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Integer>>any(),
                eq(7L), eq(1L))).thenReturn(1);
        // 只更新 favorite：labels 传 null → COALESCE 保留原值；回读原 labels
        when(jdbc.query(contains("labels::text AS labels"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<TaskMetaService.MetaRow>>any(),
                eq(7L), eq(1L))).thenReturn(java.util.List.of(new TaskMetaService.MetaRow(
                "[\"课程\"]", false)));

        TaskMetaService.TaskMeta meta = newService().update(1L, 7L, null, false);

        assertEquals(java.util.List.of("课程"), meta.labels(), "未提供的 labels 不被清空");
        assertFalse(meta.favorite());
        verify(jdbc).update(contains("COALESCE"), eq((Object) null), eq(false), eq(7L), eq(1L));
    }

    @Test
    void readDegradesBadLabelsJsonToEmpty() {
        when(jdbc.query(contains("SELECT 1 FROM task"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Integer>>any(),
                eq(9L), eq(1L))).thenReturn(1);
        when(jdbc.query(contains("labels::text AS labels"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<TaskMetaService.MetaRow>>any(),
                eq(9L), eq(1L))).thenReturn(java.util.List.of(new TaskMetaService.MetaRow("不是json", false)));

        TaskMetaService.TaskMeta meta = newService().read(1L, 9L);
        assertEquals(java.util.List.of(), meta.labels(), "坏 JSON 降级为空集（口径同 keywords）");
    }

    @Test
    void missingTaskReturns404() {
        when(jdbc.query(contains("SELECT 1 FROM task"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Integer>>any(),
                eq(99L), eq(1L))).thenReturn(null);

        ApiException e = assertThrows(ApiException.class, () -> newService().update(1L, 99L, null, true));
        assertEquals(404, e.getStatus());
        assertEquals("TASK_NOT_FOUND", e.getCode());
        ApiException e2 = assertThrows(ApiException.class, () -> newService().read(1L, 99L));
        assertEquals(404, e2.getStatus());
    }

    private static void assertFalse(boolean v) {
        org.junit.jupiter.api.Assertions.assertFalse(v);
    }
}
