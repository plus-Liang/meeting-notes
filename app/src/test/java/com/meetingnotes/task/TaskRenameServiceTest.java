package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.43：重命名 service——404 归属口径（不存在/非本人统一 TASK_NOT_FOUND）、
 * **只写 display_name + updated_at**（不动其它列）、清除落 null。
 */
@ExtendWith(MockitoExtension.class)
class TaskRenameServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private TaskRenameService newService() {
        return new TaskRenameService(jdbc);
    }

    private void stubExists(boolean exists) {
        when(jdbc.query(contains("SELECT 1 FROM task"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Integer>>any(),
                eq(7L), eq(1L))).thenReturn(exists ? 1 : null);
    }

    @Test
    void missingTaskFails404TaskNotFoundAndSkipsUpdate() {
        stubExists(false);

        ApiException e = assertThrows(ApiException.class, () -> newService().rename(1L, 7L, "新名"));
        assertEquals(404, e.getStatus());
        assertEquals("TASK_NOT_FOUND", e.getCode());
        verify(jdbc, never()).update(ArgumentMatchers.anyString(),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any());
    }

    @Test
    void renameWritesOnlyDisplayNameAndUpdatedAt() {
        stubExists(true);

        TaskRenameService.RenameResult result = newService().rename(1L, 7L, "第三章 讲座");

        assertEquals(7L, result.taskId());
        assertEquals("第三章 讲座", result.displayName());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq("第三章 讲座"), eq(7L), eq(1L));
        assertTrue(sql.getValue().contains("display_name = ?"), "写 display_name：" + sql.getValue());
        assertTrue(sql.getValue().contains("updated_at = now()"), "写 updated_at：" + sql.getValue());
        assertTrue(sql.getValue().contains("WHERE id = ? AND user_id = ?"), "归属过滤在 UPDATE 内");
        assertTrue(!sql.getValue().toUpperCase().contains("SOURCE_URI"),
                "不动 source_uri（原文件名与溯源不改）：" + sql.getValue());
    }

    @Test
    void clearWritesNull() {
        stubExists(true);

        TaskRenameService.RenameResult result = newService().rename(1L, 7L, null);

        assertNull(result.displayName(), "清除 → display_name null（不是空串）");
        verify(jdbc).update(contains("display_name = ?"), isNull(), eq(7L), eq(1L));
    }
}
