package com.meetingnotes.task;

import com.meetingnotes.task.TaskMutationService.Correction;
import com.meetingnotes.task.TaskMutationService.ReplaceOutcome;
import com.meetingnotes.task.TaskMutationService.TaskTexts;
import com.meetingnotes.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3.5 服务层：改词替换/计数口径（e）、jsonb 结构化改写（修正 2）、glossary upsert 累加、
 * done 兼容老数据、越界/非法输入反例。SQL 只断言"形状"（参数化、ON CONFLICT、?::jsonb），真库行为留给实机。
 */
@ExtendWith(MockitoExtension.class)
class TaskMutationServiceTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private GlossaryRepository glossaryRepository;

    private TaskMutationService newService() {
        return new TaskMutationService(jdbc, glossaryRepository);
    }

    private static TaskTexts texts(String transcript, String summary, String keywords, String todos) {
        return new TaskTexts(transcript, summary, keywords, todos);
    }

    // ---- 纯替换逻辑（口径 a / e、修正 2） ----

    @Test
    void correctionsAppliedLongestFirstToAvoidDoubleReplacement() {
        // 口径 a：长度降序——"章总经理"先整体替换，"章总"再替换剩余，不发生二次破坏
        ReplaceOutcome outcome = TaskMutationService.applyAll(
                texts("章总经理到了。章总好。", null, null, null),
                List.of(new Correction("章总", "张总"), new Correction("章总经理", "张总经理")));

        assertEquals("张总经理到了。张总好。", outcome.transcript(), "实际：" + outcome.transcript());
        assertEquals(2, outcome.transcriptHits());
    }

    @Test
    void quotesInRightTextKeepJsonbValid() {
        // 修正 2：right_text 含双引号/反斜杠 → Java 侧 Jackson 序列化兜住，keywords/todos 的 jsonb 必须仍合法
        ReplaceOutcome outcome = TaskMutationService.applyAll(
                texts("他说了句口头禅。", "口头禅被记录",
                        "[\"口头禅\",\"其他\"]",
                        "[{\"action\":\"把口头禅写进纪要\",\"owner\":null,\"deadline\":null}]"),
                List.of(new Correction("口头禅", "他说：\"引号\"\\测试")));

        assertEquals("他说了句他说：\"引号\"\\测试。", outcome.transcript());
        com.fasterxml.jackson.databind.JsonNode todos = parse(outcome.todosJson());
        assertTrue(todos.isArray() && todos.get(0).isObject(), "todos 仍是合法 JSON 数组");
        assertEquals("把他说：\"引号\"\\测试写进纪要", todos.get(0).path("action").asText(),
                "todo 字段替换后引号被正确转义");
        com.fasterxml.jackson.databind.JsonNode keywords = parse(outcome.keywordsJson());
        assertEquals("他说：\"引号\"\\测试", keywords.get(0).asText());
    }

    @Test
    void replacedCountFollowsContractDefinition() {
        // 口径 e：transcript 出现次数 + summary 出现次数 + keywords 命中元素数 + todos 字符串字段出现次数
        ReplaceOutcome outcome = TaskMutationService.applyAll(
                texts(" foo bar foo ", "foo", "[\"foo\",\"bar\"]",
                        "[{\"action\":\"foo bar\",\"owner\":\"foo\",\"deadline\":null}]"),
                List.of(new Correction("foo", "baz")));

        assertEquals(2, outcome.transcriptHits());
        assertEquals(1, outcome.summaryHits());
        assertEquals(1, outcome.keywordElementHits(), "keywords 按命中元素数");
        assertEquals(2, outcome.todoFieldHits(), "todos 按字符串字段出现次数合计");
        assertEquals(6, outcome.transcriptHits() + outcome.summaryHits()
                + outcome.keywordElementHits() + outcome.todoFieldHits());
    }

    @Test
    void nullSummaryOrJsonbColumnsStayNull() {
        // 无 summary/keywords/todos 的老任务：替换只动 transcript，其余保持 null（不假装有数据）
        ReplaceOutcome outcome = TaskMutationService.applyAll(
                texts("只有转写。", null, null, null), List.of(new Correction("只有", "仅有")));

        assertEquals("仅有转写。", outcome.transcript());
        assertEquals(null, outcome.summary());
        assertEquals(null, outcome.keywordsJson());
        assertEquals(null, outcome.todosJson());
    }

    // ---- 编排：校验 + SQL 形状 + upsert 语义 ----

    @Test
    void applyCorrectionsUpdatesAllColumnsAndUpsertsGlossary() {
        stubTaskExists(true);
        stubTexts("章总来了。", "章总发言", "[\"章总\"]", "[{\"action\":\"等章总确认\",\"owner\":null,\"deadline\":null}]");
        when(glossaryRepository.upsert(1L, "章总", "张总"))
                .thenReturn(new GlossaryRepository.Entry("章总", "张总", 1));

        TaskMutationService.CorrectionOutcome outcome =
                newService().applyCorrections(7L, 1L, List.of(new Correction("章总", "张总")));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("UPDATE task_result"), "替换必须写库");
        assertTrue(sql.getValue().contains("?::jsonb"), "jsonb 列必须参数化写回（禁拼字符串）");
        // 口径 c：replaced=0 也要沉淀词表——这里每条 correction 都 upsert，不做"命中才写"
        verify(glossaryRepository, times(1)).upsert(1L, "章总", "张总");
        assertEquals(4, outcome.replacedCount(), "transcript 1 + summary 1 + kw 元素 1 + todos 字段 1");
        assertEquals(1, outcome.glossary().get(0).hits());
    }

    @Test
    void glossaryUpsertAccumulatesHitsViaOnConflict() {
        // 验收②：同一错词改两次 → hits 1→2（幂等累积，不是插新行）——由 UPSERT_SQL 的 ON CONFLICT 保证
        stubTaskExists(true);
        stubTexts("章总来了。", null, null, null);
        when(glossaryRepository.upsert(1L, "章总", "张总"))
                .thenReturn(new GlossaryRepository.Entry("章总", "张总", 2));

        TaskMutationService.CorrectionOutcome outcome =
                newService().applyCorrections(7L, 1L, List.of(new Correction("章总", "张总")));

        verify(glossaryRepository, times(1)).upsert(1L, "章总", "张总");
        assertEquals(2, outcome.glossary().get(0).hits(), "重复改词 hits 递增");
        assertTrue(GlossaryRepository.UPSERT_SQL.contains("ON CONFLICT (user_id, wrong_text)"),
                "必须走唯一约束裁决的更新分支");
        assertTrue(GlossaryRepository.UPSERT_SQL.contains("glossary.hits + 1"), "hits 必须在原值上累加");
        assertFalse(GlossaryRepository.UPSERT_SQL.contains("priority = EXCLUDED.priority"),
                "裁决 1：冲突时 priority 保持原值，不被本次请求覆盖");
    }

    @Test
    void invalidCorrectionsRejectedWith400() {
        TaskMutationService service = newService();
        assertEquals(400, assertThrows(ApiException.class,
                () -> service.applyCorrections(7L, 1L, List.of())).getStatus());
        assertEquals("INVALID_PARAM", assertThrows(ApiException.class,
                () -> service.applyCorrections(7L, 1L,
                        List.of(new Correction("  ", "张总")))).getCode());
        assertEquals("INVALID_PARAM", assertThrows(ApiException.class,
                () -> service.applyCorrections(7L, 1L,
                        List.of(new Correction("章总", "章总")))).getCode(), "wrong = right → 400");
        assertEquals("INVALID_PARAM", assertThrows(ApiException.class,
                () -> service.applyCorrections(7L, 1L,
                        List.of(new Correction("章".repeat(101), "张总")))).getCode(), "超 100 字 → 400");
    }

    @Test
    void missingTaskIs404AndEmptyTranscriptIs400() {
        // 口径 b：任务不存在 → 404；存在但 task_result 缺失 / transcript 空 → 400（不是 404）
        stubTaskExists(false);
        assertEquals(404, assertThrows(ApiException.class,
                () -> newService().applyCorrections(99L, 1L,
                        List.of(new Correction("章总", "张总")))).getStatus());

        stubTaskExists(true);
        when(jdbc.query(contains("FROM task_result"), any(RowMapper.class), ArgumentMatchers.<Object[]>any()))
                .thenReturn(List.of());
        assertEquals(400, assertThrows(ApiException.class,
                () -> newService().applyCorrections(7L, 1L,
                        List.of(new Correction("章总", "张总")))).getStatus(),
                "task_result 缺失 → 400 INVALID_PARAM");
    }

    @Test
    void patchTodoDoneWritesJsonbAndKeepsOldFieldsCompat() {
        // 验收③ + done 兼容老数据：jsonb 无 done 字段 → 视图 false；PATCH 写回后 done=true
        stubTaskExists(true);
        when(jdbc.query(contains("FROM task_result"), any(RowMapper.class), ArgumentMatchers.<Object[]>any()))
                .thenReturn(List.of("[{\"action\":\"整理材料\",\"owner\":\"张三\",\"deadline\":\"下周三\"}]"));

        TaskMutationService.TodoData data = newService().setTodoDone(7L, 1L, 0, true).todoData();

        assertTrue(data.done(), "老数据（无 done 字段）读出 false，PATCH 后 true");
        assertEquals("整理材料", data.action());
        assertEquals("张三", data.owner());
        assertEquals("下周三", data.deadline());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("UPDATE task_result"), "勾选必须写库（不许前端假状态）");
        assertTrue(sql.getValue().contains("todos = ?::jsonb"), "todos 必须参数化写回");
    }

    @Test
    void patchIsIdempotentAndDeleteShiftsIndexes() {
        stubTaskExists(true);
        when(jdbc.query(contains("FROM task_result"), any(RowMapper.class), ArgumentMatchers.<Object[]>any()))
                .thenReturn(List.of("[{\"action\":\"甲\"},{\"action\":\"乙\"}]"));

        newService().setTodoDone(7L, 1L, 0, true);
        newService().setTodoDone(7L, 1L, 0, true); // 幂等：重复设同值仍 200 路径，不抛异常

        TaskMutationService.TodoDeleteResult deleted = newService().deleteTodo(7L, 1L, 0);
        assertEquals(1, deleted.remainingCount(), "删除后数组少 1 条");
        assertEquals(400, assertThrows(ApiException.class,
                () -> newService().deleteTodo(7L, 1L, 5)).getStatus(), "越界 → 400 INVALID_PARAM（裁决 3）");
    }

    @Test
    void nullTodosIs400Not500() {
        // 口径 b：todos 为 null → 400（不许 500）
        stubTaskExists(true);
        when(jdbc.query(contains("FROM task_result"), any(RowMapper.class), ArgumentMatchers.<Object[]>any()))
                .thenReturn(java.util.Arrays.asList((String) null));
        ApiException e = assertThrows(ApiException.class, () -> newService().setTodoDone(7L, 1L, 0, true));
        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());
    }

    // ---- stub 工具 ----

    private void stubTaskExists(boolean exists) {
        when(jdbc.queryForList(anyString(), ArgumentMatchers.eq(Long.class), any(), any()))
                .thenReturn(exists ? List.of(7L) : List.of());
    }

    private void stubTexts(String transcript, String summary, String keywords, String todos) {
        when(jdbc.query(contains("FROM task_result"), any(RowMapper.class), ArgumentMatchers.<Object[]>any()))
                .thenReturn(List.of(new TaskTexts(transcript, summary, keywords, todos)));
    }

    private static com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("jsonb 序列化结果必须是合法 JSON：" + json, e);
        }
    }
}
