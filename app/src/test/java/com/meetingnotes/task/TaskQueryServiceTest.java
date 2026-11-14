package com.meetingnotes.task;

import com.meetingnotes.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T2.10：keyset 分页（不重不漏）/ 状态过滤 / 参数校验。 */
@ExtendWith(MockitoExtension.class)
class TaskQueryServiceTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private ResultSet rs;

    private TaskQueryService newService() {
        return new TaskQueryService(jdbc, "meetingnotes");
    }

    /** 让 jdbc.query 的 RowCallbackHandler 收到 rowCount 行模拟数据（id 从 startId 递减）。一次性顺序打桩。 */
    private void stubRows(int rowCount, long startId) throws Exception {
        Long[] ids = new Long[rowCount];
        String[] uris = new String[rowCount];
        for (int i = 0; i < rowCount; i++) {
            ids[i] = startId - i;
            uris[i] = "s3://meetingnotes/tasks/" + ids[i] + "/a.wav";
        }
        if (rowCount > 0) {
            when(rs.getLong("id")).thenReturn(ids[0], java.util.Arrays.copyOfRange(ids, 1, ids.length));
            when(rs.getString("source_uri")).thenReturn(uris[0], java.util.Arrays.copyOfRange(uris, 1, uris.length));
        }
        when(rs.getString("status")).thenReturn("DONE");
        when(rs.getObject("duration_sec")).thenReturn(90);
        when(rs.getObject(org.mockito.ArgumentMatchers.eq("created_at"),
                org.mockito.ArgumentMatchers.<Class<OffsetDateTime>>any())).thenReturn(OffsetDateTime.now());
        when(rs.getString("error_code")).thenReturn(null);
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (int i = 0; i < rowCount; i++) {
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(contains("FROM task"), ArgumentMatchers.<RowCallbackHandler>any(),
                ArgumentMatchers.any(Object[].class));
    }

    @Test
    void keysetPaginationFetchesLimitPlusOneAndComputesNextBeforeId() throws Exception {
        // limit=2 但库里有 3 行 → 返回 2 项，next_before_id=4（本页最后一项 id）
        stubRows(3, 5L);

        TaskQueryService.TaskListResponse resp = newService().list(1L, null, 2, null);

        assertEquals(2, resp.items().size());
        assertEquals(5L, resp.items().get(0).id());
        assertEquals(4L, resp.items().get(1).id());
        assertEquals(4L, resp.nextBeforeId());
        assertEquals("a.wav", resp.items().get(0).originalName(), "original_name 取自 source_uri 文件名段");
    }

    @Test
    void noNextPageWhenRowsDoNotExceedLimit() throws Exception {
        // 只有 2 行、limit=3 → 没有下一页，next_before_id 必须为 null
        stubRows(2, 9L);

        TaskQueryService.TaskListResponse resp = newService().list(1L, null, 3, null);

        assertEquals(2, resp.items().size());
        assertNull(resp.nextBeforeId(), "总数未超过 limit 时不得给出翻页游标");
    }

    @Test
    void statusAndBeforeIdFiltersLandInSqlAndArgs() {
        // 只验证 SQL 形状与参数（不喂行数据；jdbc.query 返回值在此模式下不被使用）
        newService().list(1L, "DEAD", 20, 42L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), ArgumentMatchers.<RowCallbackHandler>any(), args.capture());
        assertTrue(sql.getValue().contains("AND status = ?"), "状态过滤必须进 SQL");
        assertTrue(sql.getValue().contains("AND id < ?"), "keyset 游标必须进 SQL");
        assertTrue(sql.getValue().contains("ORDER BY id DESC LIMIT ?"), "必须 keyset 倒序 + LIMIT");
        assertTrue(!sql.getValue().contains("OFFSET"), "禁止 OFFSET 分页");
        assertEquals(List.of(1L, "DEAD", 42L, 21), List.of(args.getValue()), "参数顺序：user_id/status/before_id/limit+1");
    }

    @Test
    void controllerRejectsBadLimitAndBeforeIdWith400InvalidMedia() {
        com.meetingnotes.web.TaskController controller = new com.meetingnotes.web.TaskController(
                org.mockito.Mockito.mock(com.meetingnotes.task.TaskService.class),
                newService(),
                jdbc);

        assertEquals(400, assertThrows(ApiException.class, () -> controller.list(null, "51", null)).getStatus());
        assertEquals("INVALID_MEDIA", assertThrows(ApiException.class, () -> controller.list(null, "51", null)).getCode());
        assertEquals(400, assertThrows(ApiException.class, () -> controller.list(null, "0", null)).getStatus());
        assertEquals(400, assertThrows(ApiException.class, () -> controller.list(null, "abc", null)).getStatus());
        assertEquals(400, assertThrows(ApiException.class, () -> controller.list(null, null, "not-a-number")).getStatus());
    }
}
