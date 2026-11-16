package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T2.10：keyset 分页（不重不漏）/ 状态过滤 / 参数校验。T4.24：q 服务端搜索（参数化/转义/纯数字兼 id）+ counts。 */
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
        stubRowsWithKeywords(rowCount, startId, null, null, null);
    }

    /** T4.21：带辨识三字段的行打桩（keywords/summary/template 逐行循环）。 */
    private void stubRowsWithKeywords(int rowCount, long startId,
                                      String[] keywordsRaw, String[] summaries, String[] templates) throws Exception {
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
        when(rs.getString("keywords")).thenReturn(keywordsRaw == null ? null : keywordsRaw[0],
                keywordsRaw == null ? new String[0] : java.util.Arrays.copyOfRange(keywordsRaw, 1, keywordsRaw.length));
        when(rs.getString("summary")).thenReturn(summaries == null ? null : summaries[0],
                summaries == null ? new String[0] : java.util.Arrays.copyOfRange(summaries, 1, summaries.length));
        when(rs.getString("template")).thenReturn(templates == null ? null : templates[0],
                templates == null ? new String[0] : java.util.Arrays.copyOfRange(templates, 1, templates.length));
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (int i = 0; i < rowCount; i++) {
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(contains("FROM task"), ArgumentMatchers.<RowCallbackHandler>any(),
                ArgumentMatchers.any(Object[].class));
    }

    /** T4.21：列表辨识三字段——keywords 解析 / 坏 JSON → [] / summary_head 40 字截断 / template 透传。 */
    @Test
    void listItemsCarryKeywordsSummaryHeadAndTemplate() throws Exception {
        stubRowsWithKeywords(2, 9L,
                new String[]{"[\"AI\",\"简历\",\"招聘\",\"扩容\"]", "{\"bad json"},
                new String[]{"会议讨论了AI对就业市场的影响，覆盖基础开发岗位缩减与初级程序员入口收窄两大议题，并给出行动建议。", null},
                new String[]{"meeting", null});

        TaskQueryService.TaskListResponse resp = newService().list(1L, null, 5, null, null);

        var first = resp.items().get(0);
        assertEquals(List.of("AI", "简历", "招聘", "扩容"), first.keywords(), "合法 JSON → 数组原样");
        assertEquals("会议讨论了AI对就业市场的影响，覆盖基础开发岗位缩减与初级程序员入口收窄两大议题",
                first.summaryHead(), "summary 前 40 字截断");
        assertEquals("meeting", first.template());
        var second = resp.items().get(1);
        assertEquals(List.of(), second.keywords(), "坏 JSON → []（不炸接口）");
        assertNull(second.summaryHead(), "无 summary → null");
        assertNull(second.template(), "旧任务 template → null");
    }

    @Test
    void keysetPaginationFetchesLimitPlusOneAndComputesNextBeforeId() throws Exception {
        // limit=2 但库里有 3 行 → 返回 2 项，next_before_id=4（本页最后一项 id）
        stubRows(3, 5L);

        TaskQueryService.TaskListResponse resp = newService().list(1L, null, 2, null, null);

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

        TaskQueryService.TaskListResponse resp = newService().list(1L, null, 3, null, null);

        assertEquals(2, resp.items().size());
        assertNull(resp.nextBeforeId(), "总数未超过 limit 时不得给出翻页游标");
    }

    @Test
    void statusAndBeforeIdFiltersLandInSqlAndArgs() {
        // 只验证 SQL 形状与参数（不喂行数据；jdbc.query 返回值在此模式下不被使用）
        newService().list(1L, "DEAD", 20, 42L, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), ArgumentMatchers.<RowCallbackHandler>any(), args.capture());
        assertTrue(sql.getValue().contains("AND t.status = ?"), "状态过滤必须进 SQL");
        assertTrue(sql.getValue().contains("AND t.id < ?"), "keyset 游标必须进 SQL");
        assertTrue(sql.getValue().contains("ORDER BY t.id DESC LIMIT ?"), "必须 keyset 倒序 + LIMIT");
        assertTrue(sql.getValue().contains("LEFT JOIN task_result"), "T4.21：必须 JOIN task_result 取辨识字段");
        assertTrue(!sql.getValue().contains("OFFSET"), "禁止 OFFSET 分页");
        assertEquals(List.of(1L, "DEAD", 42L, 21), List.of(args.getValue()), "参数顺序：user_id/status/before_id/limit+1");
    }

    @Test
    void controllerRejectsBadParamsWith400InvalidParam() {
        com.meetingnotes.web.TaskController controller = newController();

        assertEquals("INVALID_PARAM", assertThrows(ApiException.class, () -> controller.list(null, "51", null, null)).getCode());
        assertEquals(400, assertThrows(ApiException.class, () -> controller.list(null, "0", null, null)).getStatus());
        assertEquals("INVALID_PARAM", assertThrows(ApiException.class, () -> controller.list(null, "abc", null, null)).getCode());
        assertEquals("INVALID_PARAM", assertThrows(ApiException.class, () -> controller.list(null, null, "not-a-number", null)).getCode());
    }

    /** T4.1 起构造器多了 retry/glossary 两个依赖，T4.7 再加 batch，这里统一补齐 mock。 */
    private com.meetingnotes.web.TaskController newController() {
        return new com.meetingnotes.web.TaskController(
                org.mockito.Mockito.mock(com.meetingnotes.task.TaskService.class),
                org.mockito.Mockito.mock(com.meetingnotes.task.BatchTaskService.class),
                newService(),
                org.mockito.Mockito.mock(com.meetingnotes.task.TaskMutationService.class),
                org.mockito.Mockito.mock(com.meetingnotes.task.TextTaskService.class),
                org.mockito.Mockito.mock(com.meetingnotes.task.TaskRetryService.class),
                org.mockito.Mockito.mock(com.meetingnotes.task.GlossaryRepository.class),
                jdbc,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void controllerValidatesStatusFilterStrictly() throws Exception {
        com.meetingnotes.task.TaskService taskService = org.mockito.Mockito.mock(com.meetingnotes.task.TaskService.class);
        com.meetingnotes.web.TaskController controller = newController();

        // 非法 status → 400 INVALID_PARAM，不再静默空列表（T2.12）
        ApiException e = assertThrows(ApiException.class, () -> controller.list("BOGUS", null, null, null));
        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());

        // 合法 status 原样透传（ 不触发 400）
        stubRows(1, 3L);
        com.meetingnotes.task.TaskQueryService.TaskListResponse resp = controller.list("DONE", null, null, null);
        assertEquals(1, resp.items().size());
    }

    /* ===== T4.24：q 服务端搜索 ===== */

    /** 捕获最近一次 list() 的 SQL 与参数。 */
    private record CapturedSql(String sql, Object[] args) {
    }

    private CapturedSql captureLastQuery() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), ArgumentMatchers.<RowCallbackHandler>any(), args.capture());
        return new CapturedSql(sql.getValue(), args.getValue());
    }

    @Test
    void qLandsInSqlParameterizedWithLikeEscaping() {
        // %/_/\ 全部转义成字面量，且关键词只出现在参数里、绝不拼进 SQL 字符串
        newService().list(1L, null, 20, null, "ab%_\\c");

        CapturedSql c = captureLastQuery();
        assertTrue(c.sql().contains("ILIKE ? ESCAPE '\\'"), "必须声明 ESCAPE '\\'（反斜杠为转义符）");
        assertFalse(c.sql().contains("ab%"), "关键词不许拼进 SQL 字符串（必须参数化）");
        assertEquals(List.of(1L, "%ab\\%\\_\\\\c%", 21), List.of(c.args()),
                "参数顺序：user_id / %q%（%/_/\\ 已转义）/ limit+1");
    }

    @Test
    void blankOrNullQAddsNoCondition() {
        newService().list(1L, null, 20, null, null);
        newService().list(1L, null, 20, null, "   ");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).query(sql.capture(), ArgumentMatchers.<RowCallbackHandler>any(), args.capture());
        List<String> sqls = sql.getAllValues();
        List<Object[]> allArgs = args.getAllValues();
        for (int i = 0; i < sqls.size(); i++) {
            assertFalse(sqls.get(i).contains("ILIKE"), "空白/缺省 q 不加搜索条件");
            assertEquals(List.of(1L, 21), List.of(allArgs.get(i)), "参数只有 user_id / limit+1");
        }
    }

    @Test
    void pureNumericQAlsoMatchesIdExactly() {
        newService().list(1L, null, 20, null, "42");

        CapturedSql c = captureLastQuery();
        assertTrue(c.sql().contains("OR t.id = ?"), "纯数字 q 额外精确匹配任务 id");
        assertTrue(c.sql().contains("AND (t.source_uri ILIKE"), "OR 必须包在括号内（不破坏 user_id/status 过滤）");
        assertEquals(List.of(1L, "%42%", 42L, 21), List.of(c.args()));
    }

    @Test
    void qCombinesWithStatusAndBeforeId() {
        newService().list(1L, "DEAD", 20, 42L, "abc");

        CapturedSql c = captureLastQuery();
        assertTrue(c.sql().contains("AND t.status = ?"), "与 status 可组合");
        assertTrue(c.sql().contains("AND t.id < ?"), "与 before_id 可组合");
        assertTrue(c.sql().contains("ORDER BY t.id DESC LIMIT ?"), "分页语义不变");
        assertEquals(List.of(1L, "DEAD", 42L, "%abc%", 21), List.of(c.args()));
    }

    @Test
    void controllerRejectsOverLongQWith400AndTrimsQ() throws Exception {
        com.meetingnotes.web.TaskController controller = newController();

        // q 超过 100 字符 → 400 INVALID_PARAM
        ApiException e = assertThrows(ApiException.class,
                () -> controller.list(null, null, null, "x".repeat(101)));
        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());

        // q 去空格 + 纯数字 → 透传到 SQL（id 精确匹配）
        stubRows(1, 3L);
        controller.list(null, null, null, "  42  ");
        CapturedSql c = captureLastQuery();
        assertTrue(c.sql().contains("OR t.id = ?"), "q 去空格后纯数字仍精确匹配 id");
        assertEquals(List.of(1L, "%42%", 42L, 21), List.of(c.args()));
    }

    /* ===== T4.24b：纯数字 q 溢出安全（超 64 位只走 ILIKE，不 500） ===== */

    @Test
    void overLongNumericQOnlyGoesIlikeWithoutIdMatch() {
        // 20 位数字 > Long.MAX → 不加 OR t.id（防 NumberFormatException 打 500），只走 ILIKE，保持 200
        String q20 = "9".repeat(20);
        newService().list(1L, null, 20, null, q20);

        CapturedSql c = captureLastQuery();
        assertFalse(c.sql().contains("OR t.id = ?"), "超出 64 位范围的数字不得加 id 精确匹配");
        assertEquals(List.of(1L, "%" + q20 + "%", 21), List.of(c.args()), "仍按普通关键词参数化 ILIKE");
    }

    @Test
    void longMaxValueQStillMatchesIdExactly() {
        // Long.MAX_VALUE（9223372036854775807）恰好能装进 long → 精确匹配保留，参数 = MAX
        String max = Long.toString(Long.MAX_VALUE);
        newService().list(1L, null, 20, null, max);

        CapturedSql c = captureLastQuery();
        assertTrue(c.sql().contains("OR t.id = ?"), "Long.MAX 边界值仍走 id 精确匹配");
        assertEquals(List.of(1L, "%" + max + "%", Long.MAX_VALUE, 21), List.of(c.args()));
    }

    @Test
    void countsGroupsByStatusAndMergesActive() throws Exception {
        when(rs.getString(1)).thenReturn("DONE", "DEAD", "RUNNING");
        when(rs.getLong(2)).thenReturn(3L, 2L, 1L);
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (int i = 0; i < 3; i++) {
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(contains("GROUP BY status"), ArgumentMatchers.<RowCallbackHandler>any(),
                ArgumentMatchers.any(Object[].class));

        TaskQueryService.TaskCounts c = newService().counts(1L);

        assertEquals(6, c.total(), "total = 全状态行数合计（真计数无上限）");
        assertEquals(1, c.active(), "active = PENDING+RUNNING+RETRYING 合计");
        assertEquals(3, c.done());
        assertEquals(2, c.dead());
        verify(jdbc).query(contains("GROUP BY status"), ArgumentMatchers.<RowCallbackHandler>any(), eq(1L));
    }
}
