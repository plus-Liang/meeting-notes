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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        return new TaskQueryService(jdbc, "meetingnotes", 30);
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
        when(rs.getObject(org.mockito.ArgumentMatchers.eq("deleted_at"),
                org.mockito.ArgumentMatchers.<Class<java.time.OffsetDateTime>>any())).thenReturn(null);
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

    @org.junit.jupiter.api.BeforeEach
    void setUpUserContextT437a() {
        com.meetingnotes.common.UserContext.set(1L);
    }

    @org.junit.jupiter.api.AfterEach
    void clearUserContextT437a() {
        com.meetingnotes.common.UserContext.clear();
    }

    @Test
    void listItemsCarryKeywordsSummaryHeadAndTemplate() throws Exception {
        stubRowsWithKeywords(2, 9L,
                new String[]{"[\"AI\",\"简历\",\"招聘\",\"扩容\"]", "{\"bad json"},
                new String[]{"会议讨论了AI对就业市场的影响，覆盖基础开发岗位缩减与初级程序员入口收窄两大议题，并给出行动建议。", null},
                new String[]{"meeting", null});

        TaskQueryService.TaskListResponse resp = newService().list(1L, null, 5, null, null, List.of(), null, null, false);

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

    /** T4.43：列表项末尾只增 display_name——命名任务透传、旧任务 null（键不省略由序列化层保证）。 */
    @Test
    void listItemsCarryDisplayNameOrNull() throws Exception {
        stubRows(2, 9L);
        // 第 1 行有展示名、第 2 行未命名（Mockito 连续打桩）；labels 同步打桩防严格桩参数错配
        when(rs.getString("labels")).thenReturn(null);
        when(rs.getString("display_name")).thenReturn("第三章 讲座", (String) null);

        TaskQueryService.TaskListResponse resp = newService().list(1L, null, 5, null, null, List.of(), null, null, false);

        assertEquals("第三章 讲座", resp.items().get(0).displayName(), "display_name 原样透传");
        assertNull(resp.items().get(1).displayName(), "未命名任务 display_name = null（不是空串）");
    }

    @Test
    void keysetPaginationFetchesLimitPlusOneAndComputesNextBeforeId() throws Exception {
        // limit=2 但库里有 3 行 → 返回 2 项，next_before_id=4（本页最后一项 id）
        stubRows(3, 5L);

        TaskQueryService.TaskListResponse resp = newService().list(1L, null, 2, null, null, List.of(), null, null, false);

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

        TaskQueryService.TaskListResponse resp = newService().list(1L, null, 3, null, null, List.of(), null, null, false);

        assertEquals(2, resp.items().size());
        assertNull(resp.nextBeforeId(), "总数未超过 limit 时不得给出翻页游标");
    }

    @Test
    void statusAndBeforeIdFiltersLandInSqlAndArgs() {
        // 只验证 SQL 形状与参数（不喂行数据；jdbc.query 返回值在此模式下不被使用）
        newService().list(1L, "DEAD", 20, 42L, null, List.of(), null, null, false);

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
        com.meetingnotes.web.TaskListController controller = newListController();

        assertEquals("INVALID_PARAM", assertThrows(ApiException.class, () -> controller.list(null, "51", null, null, null, null, null, null, null)).getCode());
        assertEquals(400, assertThrows(ApiException.class, () -> controller.list(null, "0", null, null, null, null, null, null, null)).getStatus());
        assertEquals("INVALID_PARAM", assertThrows(ApiException.class, () -> controller.list(null, "abc", null, null, null, null, null, null, null)).getCode());
        assertEquals("INVALID_PARAM", assertThrows(ApiException.class, () -> controller.list(null, null, "not-a-number", null, null, null, null, null, null)).getCode());
    }

    /** T4.1 起构造器多了 retry/glossary 两个依赖，T4.7 再加 batch，这里统一补齐 mock。 */
    private com.meetingnotes.web.TaskListController newListController() {
        return new com.meetingnotes.web.TaskListController(newService());
    }

    private com.meetingnotes.web.TaskController newController() {
        return new com.meetingnotes.web.TaskController(
                org.mockito.Mockito.mock(com.meetingnotes.task.TaskService.class),
                org.mockito.Mockito.mock(com.meetingnotes.task.BatchTaskService.class),
                newService(),
                org.mockito.Mockito.mock(com.meetingnotes.task.TaskMutationService.class),
                org.mockito.Mockito.mock(com.meetingnotes.task.TextTaskService.class),
                org.mockito.Mockito.mock(com.meetingnotes.task.TaskRetryService.class),
                org.mockito.Mockito.mock(com.meetingnotes.task.GlossaryRepository.class),
                new com.meetingnotes.web.TaskStatusAssembler(
                        jdbc, new com.fasterxml.jackson.databind.ObjectMapper())); /* T4.101：构造器去 jdbc/mapper，改注 assembler */
    }

    @Test
    void controllerValidatesStatusFilterStrictly() throws Exception {
        com.meetingnotes.web.TaskListController controller = newListController();

        // 非法 status → 400 INVALID_PARAM，不再静默空列表（T2.12）
        ApiException e = assertThrows(ApiException.class, () -> controller.list("BOGUS", null, null, null, null, null, null, null, null));
        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());

        // 合法 status 原样透传（ 不触发 400）
        stubRows(1, 3L);
        com.meetingnotes.task.TaskQueryService.TaskListResponse resp = controller.list("DONE", null, null, null, null, null, null, null, null);
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
        newService().list(1L, null, 20, null, "ab%_\\c", List.of(), null, null, false);

        CapturedSql c = captureLastQuery();
        assertTrue(c.sql().contains("ILIKE ? ESCAPE '\\'"), "必须声明 ESCAPE '\\'（反斜杠为转义符）");
        assertFalse(c.sql().contains("ab%"), "关键词不许拼进 SQL 字符串（必须参数化）");
        assertEquals(List.of(1L, "%ab\\%\\_\\\\c%", 21), List.of(c.args()),
                "参数顺序：user_id / %q%（%/_/\\ 已转义）/ limit+1");
    }

    @Test
    void blankOrNullQAddsNoCondition() {
        newService().list(1L, null, 20, null, null, List.of(), null, null, false);
        newService().list(1L, null, 20, null, "   ", List.of(), null, null, false);

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
        newService().list(1L, null, 20, null, "42", List.of(), null, null, false);

        CapturedSql c = captureLastQuery();
        assertTrue(c.sql().contains("OR t.id = ?"), "纯数字 q 额外精确匹配任务 id");
        assertTrue(c.sql().contains("AND (t.source_uri ILIKE"), "OR 必须包在括号内（不破坏 user_id/status 过滤）");
        assertEquals(List.of(1L, "%42%", 42L, 21), List.of(c.args()));
    }

    @Test
    void qCombinesWithStatusAndBeforeId() {
        newService().list(1L, "DEAD", 20, 42L, "abc", List.of(), null, null, false);

        CapturedSql c = captureLastQuery();
        assertTrue(c.sql().contains("AND t.status = ?"), "与 status 可组合");
        assertTrue(c.sql().contains("AND t.id < ?"), "与 before_id 可组合");
        assertTrue(c.sql().contains("ORDER BY t.id DESC LIMIT ?"), "分页语义不变");
        /* T4.42：before_id 只进列表查询（COUNT 不含游标），参数顺序变为 user/status/q/before_id/limit+1 */
        assertEquals(List.of(1L, "DEAD", "%abc%", 42L, 21), List.of(c.args()));
    }

    @Test
    void controllerRejectsOverLongQWith400AndTrimsQ() throws Exception {
        com.meetingnotes.web.TaskListController controller = newListController();

        // q 超过 100 字符 → 400 INVALID_PARAM
        ApiException e = assertThrows(ApiException.class,
                () -> controller.list(null, null, null, "x".repeat(101), null, null, null, null, null));
        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());

        // q 去空格 + 纯数字 → 透传到 SQL（id 精确匹配）
        stubRows(1, 3L);
        controller.list(null, null, null, "  42  ", null, null, null, null, null);
        CapturedSql c = captureLastQuery();
        assertTrue(c.sql().contains("OR t.id = ?"), "q 去空格后纯数字仍精确匹配 id");
        assertEquals(List.of(1L, "%42%", 42L, 21), List.of(c.args()));
    }

    /* ===== T4.24b：纯数字 q 溢出安全（超 64 位只走 ILIKE，不 500） ===== */

    @Test
    void overLongNumericQOnlyGoesIlikeWithoutIdMatch() {
        // 20 位数字 > Long.MAX → 不加 OR t.id（防 NumberFormatException 打 500），只走 ILIKE，保持 200
        String q20 = "9".repeat(20);
        newService().list(1L, null, 20, null, q20, List.of(), null, null, false);

        CapturedSql c = captureLastQuery();
        assertFalse(c.sql().contains("OR t.id = ?"), "超出 64 位范围的数字不得加 id 精确匹配");
        assertEquals(List.of(1L, "%" + q20 + "%", 21), List.of(c.args()), "仍按普通关键词参数化 ILIKE");
    }

    @Test
    void longMaxValueQStillMatchesIdExactly() {
        // Long.MAX_VALUE（9223372036854775807）恰好能装进 long → 精确匹配保留，参数 = MAX
        String max = Long.toString(Long.MAX_VALUE);
        newService().list(1L, null, 20, null, max, List.of(), null, null, false);

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

    /* ===== T4.29：label / favorite 筛选（与 status/q 组合，参数化） ===== */

    @Test
    void labelFilterUsesJsonbContainmentParameterized() {
        newService().list(1L, null, 20, null, null, List.of("课程"), null, null, false);

        CapturedSql c = captureLastQuery();
        assertTrue(c.sql().contains("t.labels @> ?::jsonb"), "label 筛选走 jsonb 包含匹配");
        assertFalse(c.sql().contains("课程"), "标签值绝不拼进 SQL 字符串（注入面同 q）");
        assertEquals(List.of(1L, "[\"课程\"]", 21), List.of(c.args()),
                "jsonb 参数由 mapper 序列化（值在绑定参数里）");
    }

    @Test
    void favoriteFilterBindsBooleanParameterized() {
        newService().list(1L, null, 20, null, null, List.of(), true, null, false);

        CapturedSql c = captureLastQuery();
        assertTrue(c.sql().contains("t.favorite = ?"), "favorite 筛选参数化");
        assertEquals(List.of(1L, true, 21), List.of(c.args()));
    }

    /* ===== T4.42：日期区间 / 多标签 / total ===== */

    @Test
    void dateWindowBlankOrNullSidesReturnNull() {
        assertNull(TaskDateWindow.resolve(null, null), "两侧都未给 → 不过滤");
        assertNull(TaskDateWindow.resolve("", "   "), "空白串按未给");
    }

    @Test
    void dateWindowParsesHalfOpenRangeInServerZone() {
        TaskDateWindow w = TaskDateWindow.resolve("2026-01-01", "2026-01-02");
        ZoneId zone = ZoneId.systemDefault();
        assertNotNull(w);
        assertEquals(LocalDate.of(2026, 1, 1).atStartOfDay(zone).toOffsetDateTime(), w.after(),
                "after = 当日 00:00（服务端时区）");
        assertEquals(LocalDate.of(2026, 1, 3).atStartOfDay(zone).toOffsetDateTime(), w.before(),
                "before = 次日 00:00（半开区间，含 before 当日全天）");
    }

    @Test
    void dateWindowSameDayIsNonEmptyInterval() {
        TaskDateWindow w = TaskDateWindow.resolve("2026-01-01", "2026-01-01");
        assertNotNull(w);
        assertTrue(w.before().isAfter(w.after()), "同一天 = [当日00:00, 次日00:00) 非空区间");
    }

    @Test
    void dateWindowSingleEndAllowed() {
        assertNotNull(TaskDateWindow.resolve("2026-01-01", null), "只给 after 合法");
        assertNotNull(TaskDateWindow.resolve(null, "2026-01-31"), "只给 before 合法");
    }

    @Test
    void dateWindowCrossMonthBeforeRollsToNextMonth() {
        TaskDateWindow w = TaskDateWindow.resolve("2026-01-31", "2026-01-31");
        ZoneId zone = ZoneId.systemDefault();
        assertEquals(LocalDate.of(2026, 2, 1).atStartOfDay(zone).toOffsetDateTime(), w.before(),
                "跨月边界：01-31 的次日 = 02-01 00:00");
    }

    @Test
    void dateWindowReversedDatesAndBadFormatsAre400() {
        ApiException reversed = assertThrows(ApiException.class,
                () -> TaskDateWindow.resolve("2026-01-02", "2026-01-01"));
        assertEquals(400, reversed.getStatus(), "after 晚于 before → 400");
        assertEquals("INVALID_PARAM", reversed.getCode());
        for (String bad : new String[]{"2026-1-1", "abc", "2026/01/01", "2026-01-01T00:00", "20260101"}) {
            ApiException e = assertThrows(ApiException.class,
                    () -> TaskDateWindow.resolve(bad, null), "宽松/非法格式必须拒绝：" + bad);
            assertEquals(400, e.getStatus());
            assertEquals("INVALID_PARAM", e.getCode());
        }
    }

    @Test
    void dateWindowLandsInListSqlParameterized() {
        TaskDateWindow w = TaskDateWindow.resolve("2026-01-01", "2026-01-02");
        newService().list(1L, null, 20, null, null, List.of(), null, w, false);

        CapturedSql c = captureLastQuery();
        assertTrue(c.sql().contains("t.created_at >= ?"), "after 必须进 SQL");
        assertTrue(c.sql().contains("t.created_at < ?"), "before（半开）必须进 SQL");
        assertEquals(List.of(1L, w.after(), w.before(), 21), List.of(c.args()), "日期参数化绑定");
    }

    @Test
    void multiLabelUsesOrUnionParameterizedPerLabel() {
        /* D-162 判别用例（先红后绿）：OR 并集——两标签各自一个 @> 参数，绝不合并成一个数组（那是 AND 语义） */
        newService().list(1L, null, 20, null, null, List.of("课程", "不存在"), null, null, false);

        CapturedSql c = captureLastQuery();
        assertTrue(c.sql().contains("t.labels @> ?::jsonb OR t.labels @> ?::jsonb"),
                "多标签必须 OR 并集（同维度并集/跨维度交集，D-162）");
        assertFalse(c.sql().contains("课程"), "标签值绝不拼进 SQL 字符串");
        assertEquals(List.of(1L, "[\"课程\"]", "[\"不存在\"]", 21), List.of(c.args()),
                "每标签一个 jsonb 参数（合 并成一个数组 = AND 语义，必须拒绝）");
    }


    @Test
    void totalComesFromCountQueryWithoutCursorOrLimit() throws Exception {
        when(jdbc.queryForObject(contains("count(*)"), eq(Long.class),
                ArgumentMatchers.any(Object[].class))).thenReturn(7L);
        stubRows(3, 9L); // limit=2 → 列表 2 项 + 有下一页

        TaskQueryService.TaskListResponse resp = newService().list(1L, "DONE", 2, 8L, null, List.of(), null, null, false);

        assertEquals(7, resp.total(), "total 来自 COUNT（与分页无关）");
        assertEquals(2, resp.items().size());
        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(countSql.capture(), eq(Long.class), countArgs.capture());
        assertFalse(countSql.getValue().contains("LIMIT"), "COUNT 不带 LIMIT");
        assertFalse(countSql.getValue().contains("t.id < ?"), "COUNT 不含 before_id 游标");
        assertTrue(countSql.getValue().contains("t.status = ?"), "COUNT 与筛选条件一致");
        assertEquals(List.of(1L, "DONE"), List.of(countArgs.getValue()), "COUNT 参数不含游标与 limit");
    }

    @Test
    void allNewFiltersCombineWithStatusQAndKeysetStaysListOnly() {
        TaskDateWindow w = TaskDateWindow.resolve("2026-01-01", "2026-01-02");
        newService().list(1L, "DONE", 20, 42L, "abc", List.of("会议"), true, w, false);

        CapturedSql c = captureLastQuery();
        assertTrue(c.sql().contains("AND t.status = ?"), "与 status 可组合");
        assertTrue(c.sql().contains("AND (t.source_uri ILIKE"), "与 q 可组合");
        assertTrue(c.sql().contains("t.labels @> ?::jsonb"), "与 label 可组合");
        assertTrue(c.sql().contains("t.favorite = ?"), "与 favorite 可组合");
        assertTrue(c.sql().contains("t.created_at >= ?"), "与日期区间可组合");
        assertTrue(c.sql().contains("AND t.id < ?"), "keyset 游标仍在列表查询");
        assertEquals(List.of(1L, "DONE", "%abc%", "[\"会议\"]", true, w.after(), w.before(), 42L, 21),
                List.of(c.args()), "参数顺序：user/status/q/labels/favorite/after/before/before_id/limit+1");
    }

    @Test
    void controllerRejectsBadDateParamsWith400InvalidParam() {
        com.meetingnotes.web.TaskListController controller = newListController();

        ApiException badFormat = assertThrows(ApiException.class,
                () -> controller.list(null, null, null, null, null, null, "2026/01/01", null, null));
        assertEquals(400, badFormat.getStatus());
        assertEquals("INVALID_PARAM", badFormat.getCode());

        ApiException reversed = assertThrows(ApiException.class,
                () -> controller.list(null, null, null, null, null, null, "2026-01-02", "2026-01-01", null));
        assertEquals(400, reversed.getStatus());
        assertEquals("INVALID_PARAM", reversed.getCode());
    }

    @Test
    void controllerTrimsMultiLabelAndPassesDateWindowThrough() throws Exception {
        com.meetingnotes.web.TaskListController controller = newListController();
        stubRows(1, 3L);
        TaskDateWindow w = TaskDateWindow.resolve("2026-01-01", null);

        controller.list(null, null, null, null, java.util.Arrays.asList(" 会议 ", "", null), null, "2026-01-01", null, null);

        CapturedSql c = captureLastQuery();
        assertEquals(List.of(1L, "[\"会议\"]", w.after(), 21), List.of(c.args()),
                "多标签 trim 去空；日期窗口透传服务层");
    }

    /* ===== T4.95/C2：error_message（真 H2——子查询 SQL 必须真执行过，mock 层证不了可移植性） ===== */

    private static final String H2_URL = "jdbc:h2:mem:querysvc;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    private org.springframework.jdbc.core.JdbcTemplate h2() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(H2_URL));
        jdbc.execute("DROP TABLE IF EXISTS dead_letter");
        jdbc.execute("DROP TABLE IF EXISTS task_result");
        jdbc.execute("DROP TABLE IF EXISTS task");
        /* 最小列集（覆盖列表 SQL 触达的列）；labels 用 VARCHAR 承接 ::text 转换 */
        jdbc.execute("CREATE TABLE task (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                + "user_id BIGINT, status VARCHAR(16), source_uri VARCHAR(512), duration_sec INT, "
                + "created_at TIMESTAMP WITH TIME ZONE DEFAULT now(), error_code VARCHAR(64), "
                + "content_hash VARCHAR(64), deleted_at TIMESTAMP WITH TIME ZONE, "
                + "labels VARCHAR(64), favorite BOOLEAN NOT NULL DEFAULT FALSE, display_name VARCHAR(128))");
        jdbc.execute("CREATE TABLE task_result (task_id BIGINT PRIMARY KEY, keywords VARCHAR(256), "
                + "summary VARCHAR(512), template VARCHAR(16))");
        jdbc.execute("CREATE TABLE dead_letter (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                + "task_id BIGINT, reason VARCHAR(512), payload VARCHAR(64), "
                + "created_at TIMESTAMP WITH TIME ZONE DEFAULT now())");
        return jdbc;
    }

    private long insertTask(org.springframework.jdbc.core.JdbcTemplate jdbc, String status) {
        return insertTask(jdbc, status, null);
    }

    private long insertTask(org.springframework.jdbc.core.JdbcTemplate jdbc, String status, String errorCode) {
        jdbc.update("INSERT INTO task (user_id, status, source_uri, error_code) VALUES (1, ?, 's3://meetingnotes/tasks/x/a.wav', ?)",
                status, errorCode);
        return jdbc.queryForObject("SELECT MAX(id) FROM task", Long.class);
    }

    private long insertDeadLetter(org.springframework.jdbc.core.JdbcTemplate jdbc, long taskId, String reason) {
        jdbc.update("INSERT INTO dead_letter (task_id, reason) VALUES (?, ?)", taskId, reason);
        return jdbc.queryForObject("SELECT MAX(id) FROM dead_letter", Long.class);
    }

    /** C7 用例 1：DEAD 任务两条 dead_letter（id 大 = 新）⇒ 列表 error_message 取最新一条。 */
    @Test
    void errorMessageTakesNewestDeadLetterRow() {
        var jdbc = h2();
        long deadId = insertTask(jdbc, "DEAD");
        insertDeadLetter(jdbc, deadId, "旧原因（不是这条）");
        insertDeadLetter(jdbc, deadId, "ASR 连接超时（最新原因）");

        var resp = new TaskQueryService(jdbc, "meetingnotes", 30).list(1L, null, 5, null, null, List.of(), null, null, false);

        var item = resp.items().stream().filter(i -> i.id() == deadId).findFirst().orElseThrow();
        assertEquals("DEAD", item.status());
        assertEquals("ASR 连接超时（最新原因）", item.errorMessage(), "必须取 id 最大（最新）的 dead_letter.reason");
    }

    /** C7 用例 2：C2 不做状态裁剪——DONE 任务有历史 dead_letter 行（T4.87 复活残留）也返回 reason；
     *  无 dead_letter 行的 DONE 任务 error_message = null，两种情形可区分。 */
    @Test
    void doneTaskErrorMessageFollowsDeadLetterNotStatusTrimming() {
        var jdbc = h2();
        long doneWithHistory = insertTask(jdbc, "DONE");
        insertDeadLetter(jdbc, doneWithHistory, "历史失败原因（复活后残留）");
        long plainDone = insertTask(jdbc, "DONE");

        var resp = new TaskQueryService(jdbc, "meetingnotes", 30).list(1L, null, 5, null, null, List.of(), null, null, false);

        assertEquals("历史失败原因（复活后残留）",
                resp.items().stream().filter(i -> i.id() == doneWithHistory).findFirst().orElseThrow().errorMessage(),
                "后端不按状态裁剪：DONE 残留行原样返回，展示判断归前端");
        assertNull(resp.items().stream().filter(i -> i.id() == plainDone).findFirst().orElseThrow().errorMessage(),
                "无 dead_letter 行的 DONE 任务 error_message = null（可区分）");
    }

    /** C7 用例 3：RETRYING 无 dead_letter 行（还没死）⇒ error_message = null（前端用码映射兜底）。 */
    @Test
    void retryingTaskWithoutDeadLetterRowHasNullErrorMessage() {
        var jdbc = h2();
        insertTask(jdbc, "RETRYING", "ASR_ERROR");

        var resp = new TaskQueryService(jdbc, "meetingnotes", 30).list(1L, null, 5, null, null, List.of(), null, null, false);

        assertNull(resp.items().get(0).errorMessage(), "RETRYING 且无 dead_letter 行 → null");
        assertEquals("ASR_ERROR", resp.items().get(0).errorCode(), "error_code 仍透传（前端兜底数据源）");
    }

    /** C2 子查询必须可移植：SQL 文本里禁 LATERAL / RETURNING（H2 真库跑通即证可移植）。 */
    @org.junit.jupiter.api.Test
    void errorMessageSubqueryIsPortableSql() {
        var real = h2();
        var jdbc = org.mockito.Mockito.spy(real); // spy 包真库：SQL 可捕获、语句仍真执行
        insertTask(real, "DEAD");
        insertDeadLetter(real, jdbc.queryForObject("SELECT MAX(id) FROM task", Long.class), "r");

        new TaskQueryService(jdbc, "meetingnotes", 30).list(1L, null, 5, null, null, List.of(), null, null, false);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), ArgumentMatchers.<RowCallbackHandler>any(),
                ArgumentMatchers.any(Object[].class));
        assertTrue(sql.getValue().contains("dead_letter"), "列表 SQL 必须带 dead_letter 子查询");
        assertFalse(sql.getValue().toUpperCase().contains("LATERAL"), "禁 LATERAL（PG 专有）");
        assertFalse(sql.getValue().toUpperCase().contains("RETURNING"), "禁 RETURNING（PG 专有）");
    }

    /* ===== T4.96/C4：trash 模式（真 H2——可见性条件必须真执行，漏加即泄露） ===== */

    @Test
    void trashListOnlyShowsDeletedRowsWithDaysLeft() {
        var jdbc = h2();
        // 在用任务（deleted_at NULL）+ 回收站任务（3 天前删除）
        jdbc.update("INSERT INTO task (user_id, status, source_uri, content_hash, deleted_at) "
                + "VALUES (1, 'DONE', 's3://meetingnotes/tasks/x/live.wav', 'h-live', NULL)");
        jdbc.update("INSERT INTO task (user_id, status, source_uri, content_hash, deleted_at) "
                + "VALUES (1, 'DEAD', 's3://meetingnotes/tasks/x/dead.wav', 'h-dead', ?)",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3 * 86400)));
        var svc = new TaskQueryService(jdbc, "meetingnotes", 30);

        var normal = svc.list(1L, null, 20, null, null, List.of(), null, null, false);
        var trash = svc.list(1L, null, 20, null, null, List.of(), null, null, true);

        assertTrue(normal.items().stream().noneMatch(i -> "h-dead".equals(
                        jdbc.queryForObject("SELECT content_hash FROM task WHERE id = ?", String.class, i.id()))),
                "默认列表不得出现回收站任务（泄露 = 阻塞）");
        assertTrue(normal.items().stream().allMatch(i -> i.deletedAt() == null && i.daysLeft() == null),
                "非 trash 模式 deleted_at/days_left 恒 null");
        assertEquals(1, trash.items().size(), "trash=true 只看回收站");
        var item = trash.items().get(0);
        assertNotNull(item.deletedAt(), "trash 行 deleted_at 非空");
        assertTrue(item.daysLeft() != null && item.daysLeft() >= 27 && item.daysLeft() <= 30,
                "days_left = max(0, 30 − 已过天数)，3 天前删的应在 27 附近（实测 " + item.daysLeft() + "）");
    }

    @Test
    void trashCountsUseSameVisibilityCondition() {
        var jdbc = h2();
        jdbc.update("INSERT INTO task (user_id, status, source_uri, content_hash, deleted_at) "
                + "VALUES (1, 'DONE', 's3://meetingnotes/tasks/x/a.wav', 'h1', NULL)");
        jdbc.update("INSERT INTO task (user_id, status, source_uri, content_hash, deleted_at) "
                + "VALUES (1, 'DONE', 's3://meetingnotes/tasks/x/b.wav', 'h2', now())");
        var svc = new TaskQueryService(jdbc, "meetingnotes", 30);

        assertEquals(1, svc.list(1L, null, 20, null, null, List.of(), null, null, false).total(),
                "total（COUNT）与列表同条件：默认列表 total 不含回收站");
        assertEquals(1, svc.list(1L, null, 20, null, null, List.of(), null, null, true).total(),
                "trash 模式 total 只算回收站行");
    }

    /** T4.96/B2：counts 必须排除回收站（台账侧栏徽标与默认列表 total 同口径，漏加即「徽标有数点进去没有」）。 */
    @Test
    void countsExcludeTrashedTasks() throws java.sql.SQLException {
        when(rs.getString(1)).thenReturn("DONE");
        when(rs.getLong(2)).thenReturn(1L);
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(contains("GROUP BY status"), ArgumentMatchers.<RowCallbackHandler>any(),
                ArgumentMatchers.any(Object[].class));

        TaskQueryService.TaskCounts c = newService().counts(1L);

        assertEquals(1, c.total());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), ArgumentMatchers.<RowCallbackHandler>any(), eq(1L));
        assertTrue(sql.getValue().contains("deleted_at IS NULL"),
                "counts SQL 必须排除回收站任务（实测：" + sql.getValue() + "）");
    }
}
