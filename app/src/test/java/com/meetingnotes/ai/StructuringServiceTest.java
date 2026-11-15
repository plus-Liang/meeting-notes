package com.meetingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * T3.3 主任务：结构化编排——**一次** LLM 调用出 summary/keywords/todos（F-7）、
 * 三档级联降级（D-075）、ai_tier 诚实落库（D-020）、⑨ 依据校验、⑦ 体积预算截断。
 *
 * <p>关键可证伪断言：{@code verify(model, times(1)).call(...)}——把实现改回"两次调用"或"失败重试"都会红。
 */
@ExtendWith(MockitoExtension.class)
class StructuringServiceTest {

    private static final String TRANSCRIPT = "由张三负责整理材料，下周三截止。";
    private static final String VALID_JSON = "{\"summary\":\"会议讨论了材料整理\",\"keywords\":[\"材料整理\"],"
            + "\"todos\":[{\"action\":\"整理材料\",\"owner\":\"张三\",\"deadline\":\"下周三\"}]}";

    @Mock
    private ChatModel cloudModel;
    @Mock
    private ChatModel localModel;
    @Mock
    private ObjectProvider<ChatModel> cloudProvider;
    @Mock
    private ObjectProvider<ChatModel> localProvider;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private OllamaProbe probe;
    @Mock
    private GlossaryTermSource termSource;

    private final TodoService todoService = new TodoService();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 首选档 = CLOUD（有 key + 非离线）；本地档是否可用由 localProvider 决定。 */
    private StructuringService newService(boolean extractiveEnabled) {
        AiStructuringRouter router = new AiStructuringRouter(true, false, false, "", probe);
        return new StructuringService(cloudProvider, localProvider, router, todoService, termSource, jdbc, extractiveEnabled);
    }

    private StructuringService extractiveOnlyService() {
        AiStructuringRouter router = new AiStructuringRouter(false, false, false, "", probe);
        return new StructuringService(cloudProvider, localProvider, router, todoService, termSource, jdbc, true);
    }

    private static ChatResponse response(String text, String finishReason) {
        Generation generation = new Generation(new AssistantMessage(text == null ? "" : text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        return new ChatResponse(List.of(generation));
    }

    @Test
    void oneCallProducesAllThreeFieldsAndPersistsTierCloud() {
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(VALID_JSON, "stop"));

        assertTrue(newService(true).structure(7L, TRANSCRIPT));

        verify(cloudModel, times(1)).call(any(Prompt.class));
        verify(jdbc).update(contains("UPDATE task_result"),
                eq("会议讨论了材料整理"), eq("[\"材料整理\"]"),
                eq("[{\"action\":\"整理材料\",\"owner\":\"张三\",\"deadline\":\"下周三\"}]"),
                eq("CLOUD"), eq(7L));
    }

    @Test
    void singleMergedPromptCarriesSummaryKeywordsAndTodosAtOnce() {
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(VALID_JSON, "stop"));

        newService(true).structure(8L, TRANSCRIPT);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(cloudModel).call(captor.capture());
        String prompt = captor.getValue().getContents();
        assertTrue(prompt.contains("\"summary\""), "一次调用里必须同时要 summary");
        assertTrue(prompt.contains("\"keywords\""), "一次调用里必须同时要 keywords");
        assertTrue(prompt.contains("\"todos\""), "一次调用里必须同时要 todos（F-7）");
        assertTrue(prompt.contains("最多 20 条"), "F-5：条数口径进提示词");
        assertTrue(prompt.contains(TRANSCRIPT), "transcript 只交一次");
    }

    @Test
    void cloudFailureCascadesToLocalAndTierIsHonest() {
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM 超时"));
        when(localProvider.getIfAvailable()).thenReturn(localModel);
        when(localModel.call(any(Prompt.class))).thenReturn(response(VALID_JSON, "stop"));

        assertTrue(newService(true).structure(9L, TRANSCRIPT));

        verify(cloudModel, times(1)).call(any(Prompt.class));
        verify(jdbc).update(contains("UPDATE task_result"), anyString(), anyString(), anyString(), eq("LOCAL"), eq(9L));
    }

    @Test
    void emptyContentCascadesToLocal() {
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response("", "stop"));
        when(localProvider.getIfAvailable()).thenReturn(localModel);
        when(localModel.call(any(Prompt.class))).thenReturn(response(VALID_JSON, "stop"));

        newService(true).structure(10L, TRANSCRIPT);

        verify(jdbc).update(contains("UPDATE task_result"), anyString(), anyString(), anyString(), eq("LOCAL"), eq(10L));
    }

    @Test
    void finishReasonLengthSkipsThatTierWithoutRetryingIt() {
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(VALID_JSON, "length"));
        when(localProvider.getIfAvailable()).thenReturn(localModel);
        when(localModel.call(any(Prompt.class))).thenReturn(response(VALID_JSON, "stop"));

        newService(true).structure(11L, TRANSCRIPT);

        verify(cloudModel, times(1)).call(any(Prompt.class));
        verify(jdbc).update(contains("UPDATE task_result"), anyString(), anyString(), anyString(), eq("LOCAL"), eq(11L));
    }

    @Test
    void parseFailureLeavesAllThreeFieldsEmptyAndFallsThroughToExtractive() {
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response("这不是 JSON", "stop"));

        assertTrue(newService(true).structure(12L, TRANSCRIPT));

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("UPDATE task_result"), summary.capture(),
                anyString(), anyString(), eq("EXTRACTIVE"), eq(12L));
        assertFalse(summary.getValue().isBlank(), "抽取式兜底必须给出非空 summary");
        assertTrue(summary.getValue().length() <= 200, "⑦ 预算：抽取式 summary 也受 200 字上限");
    }

    @Test
    void extractiveAlwaysProducesWhenNoModelIsAvailable() {
        assertTrue(extractiveOnlyService().structure(13L, TRANSCRIPT));

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("UPDATE task_result"), summary.capture(),
                anyString(), anyString(), eq("EXTRACTIVE"), eq(13L));
        assertFalse(summary.getValue().isBlank());
        verifyNoInteractions(cloudModel, localModel);
    }

    @Test
    void blankTranscriptSkipsStructuringAndLeavesTierNull() {
        assertFalse(newService(true).structure(14L, "   "), "空 transcript（D-048）跳过结构化");

        verifyNoInteractions(jdbc, cloudModel, localModel);
    }

    @Test
    void disabledExtractiveStopsCascadeWithoutWritingAnything() {
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response("坏 JSON", "stop"));

        assertFalse(newService(false).structure(15L, TRANSCRIPT), "第三档被开关关掉时降级链耗尽 → 三字段留空");

        // T3.8（K-31）：varargs never() 按"逐位 matcher"才不会空过——这里改为可靠断言：
        // jdbc 只发生 1 次 query（读模板，只读），除此之外**零交互**（失败路径零写库是真绿，
        // persist 若发生，verifyNoMoreInteractions 必红）
        verify(jdbc, times(1)).query(contains("COALESCE(template"),
                ArgumentMatchers.<RowMapper<String>>any(), eq(15L));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void fabricatedDeadlineAndOwnerAreNulledBeforePersist() {
        String transcript = "我们下周三再讨论这个问题。";
        String fabricated = "{\"summary\":\"讨论了问题\",\"keywords\":[],\"todos\":["
                + "{\"action\":\"再讨论这个问题\",\"owner\":\"李四\",\"deadline\":\"2023-04-15\"}]}";
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(fabricated, "stop"));

        newService(true).structure(16L, transcript);

        ArgumentCaptor<String> todosJson = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("UPDATE task_result"), anyString(), anyString(), todosJson.capture(),
                eq("CLOUD"), eq(16L));
        assertTrue(todosJson.getValue().contains("\"deadline\":null"), "⑨ 原文没有 2023-04-15 → 置 null，实际："
                + todosJson.getValue());
        assertTrue(todosJson.getValue().contains("\"owner\":null"), "⑨ 原文没有'李四' → 置 null");
    }

    @Test
    void summaryTruncatedTo200CharsAndKeywordsTo10() throws Exception {
        String longSummary = "会".repeat(300);
        StringBuilder keywords = new StringBuilder("[");
        for (int i = 1; i <= 15; i++) {
            keywords.append(i == 1 ? "" : ",").append("\"词").append(i).append("\"");
        }
        keywords.append("]");
        String json = "{\"summary\":\"" + longSummary + "\",\"keywords\":" + keywords + ",\"todos\":[]}";
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(json, "stop"));

        newService(true).structure(17L, TRANSCRIPT);

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keywordsJson = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("UPDATE task_result"), summary.capture(), keywordsJson.capture(),
                eq("[]"), eq("CLOUD"), eq(17L));
        assertEquals(200, summary.getValue().length(), "⑦ 预算：summary 代码层截断 200 字");
        JsonNode parsed = mapper.readTree(keywordsJson.getValue());
        assertEquals(10, parsed.size(), "⑦ 预算：keywords 代码层截断 10 个");
    }

    @Test
    void usageDiagnosticsIncludeReasoningSlot() {
        ChatResponse withUsage = new ChatResponse(
                List.of(new Generation(new AssistantMessage(VALID_JSON),
                        ChatGenerationMetadata.builder().finishReason("stop").build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10, 20, 30)).build());

        String usage = StructuringService.usageOf(withUsage);

        assertTrue(usage.contains("prompt=10"), "⑥ 诊断要带 prompt tokens，实际：" + usage);
        assertTrue(usage.contains("reasoning=null"), "⑥ 诊断要带 reasoning tokens 槽位（原生 usage 缺失时为 null）");
    }

    @Test
    void todosTruncatedTo20WhenModelOverProduces() {
        StringBuilder todos = new StringBuilder("[");
        for (int i = 1; i <= 25; i++) {
            todos.append(i == 1 ? "" : ",").append("{\"action\":\"整理材料").append(i).append("\"}");
        }
        todos.append("]");
        String json = "{\"summary\":\"会议\",\"keywords\":[],\"todos\":" + todos + "}";
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(json, "stop"));

        newService(true).structure(18L, TRANSCRIPT);

        ArgumentCaptor<String> todosJson = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("UPDATE task_result"), anyString(), anyString(), todosJson.capture(),
                eq("CLOUD"), eq(18L));
        assertEquals(20, todosJson.getValue().split("action", -1).length - 1,
                "F-5：代码层截断 20 条，实际：" + todosJson.getValue());
    }

    @Test
    void arrayArgumentsStayJsonArrays() {
        lenient().when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(
                "{\"summary\":\"会议\",\"todos\":[]}", "stop"));

        newService(true).structure(19L, TRANSCRIPT);

        verify(jdbc).update(contains("UPDATE task_result"), eq("会议"), eq("[]"), eq("[]"), eq("CLOUD"), eq(19L));
    }

    // ---- T3.4 ④ JSON 抽取容错（云/本地共用） ----

    @Test
    void fencedJsonInsideProseIsExtractedBeforeParsing() {
        // ```json 围栏 + 前后散文：抽取成功 → 正常落库 CLOUD，而不是无谓降级（T3.3 证据行 98/101 的病）
        String raw = "好的，以下是结构化结果：\n```json\n" + VALID_JSON + "\n```\n希望有帮助。";
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(raw, "stop"));

        assertTrue(newService(true).structure(20L, TRANSCRIPT));

        verify(jdbc).update(contains("UPDATE task_result"), eq("会议讨论了材料整理"),
                anyString(), anyString(), eq("CLOUD"), eq(20L));
    }

    @Test
    void proseWrappedJsonIsExtractedBeforeParsing() {
        // 无围栏、前后散文：从第一个 { 括号配对抽取
        String raw = "结构化结果如下：" + VALID_JSON + "，以上就是全部内容。";
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(raw, "stop"));

        assertTrue(newService(true).structure(21L, TRANSCRIPT));

        verify(jdbc).update(contains("UPDATE task_result"), eq("会议讨论了材料整理"),
                anyString(), anyString(), eq("CLOUD"), eq(21L));
    }

    @Test
    void unextractableOutputStillDegrades() {
        // 反例（验收③）：{ 不闭合 → 抽取不到 → 按解析失败走降级，容错不许吞掉失败
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response("{\"summary\":\"没闭合", "stop"));

        assertTrue(newService(true).structure(22L, TRANSCRIPT));

        verify(jdbc).update(contains("UPDATE task_result"), anyString(), anyString(), anyString(),
                eq("EXTRACTIVE"), eq(22L));
    }

    // ---- T3.6 术语注入（glossary → 提示词，US-3 闭环） ----

    @Test
    void glossaryInjectionReachesThePrompt() {
        // 词表非空：注入段（含"术语表"与改写指令）必须真的进 Prompt 内容
        when(termSource.inject()).thenReturn(new GlossaryTermSource.GlossaryInjection(
                "【术语表（原文说法 → 正确写法）】章总→张总", 1));
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(VALID_JSON, "stop"));

        newService(true).structure(30L, TRANSCRIPT);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(cloudModel).call(captor.capture());
        String prompt = captor.getValue().getContents();
        assertTrue(prompt.contains("术语表"), "注入段必须进提示词：" + prompt.substring(0, 120));
        assertTrue(prompt.contains("一律改用正确写法"), "改写指令必须进提示词");
        assertTrue(prompt.contains("章总→张总"), "词条必须进提示词");
        assertTrue(prompt.contains("\"summary\""), "JSON 结构约束原样保留（T3.6 不改字段定义）");
    }

    @Test
    void emptyGlossaryKeepsPromptByteIdentical() {
        // 空表回归判据：不注入任何内容——提示词里没有"术语表"字样（termSource 未打桩返回 null，同样按空处理）
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(VALID_JSON, "stop"));

        newService(true).structure(31L, TRANSCRIPT);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(cloudModel).call(captor.capture());
        assertFalse(captor.getValue().getContents().contains("术语表"), "空词表不得改变提示词");
        verify(termSource, times(1)).inject();
    }

    // ---- T3.9 模板机制 ----

    @Test
    void structureReadsTemplateFromTaskResultAndUsesItsPrompt() {
        // task_result 里落了 course → 提示词必须切换到 course 模板（角色行/关注点都换）
        when(jdbc.query(contains("COALESCE(template"), ArgumentMatchers.<RowMapper<String>>any(), eq(40L)))
                .thenReturn(List.of("course"));
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(VALID_JSON, "stop"));

        newService(true).structure(40L, TRANSCRIPT);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(cloudModel).call(captor.capture());
        String prompt = captor.getValue().getContents();
        assertTrue(prompt.contains("课堂知识点"), "course 模板关注点必须进提示词：" + prompt.substring(0, 120));
        assertFalse(prompt.contains("会议纪要助手"), "course 不得复用 meeting 角色行");
    }

    @Test
    void missingTemplateRowFallsBackToMeeting() {
        // 旧任务 / task_result 无行（jdbc 未打桩返回空列表）→ 回落 meeting，结构化照常
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(VALID_JSON, "stop"));

        newService(true).structure(41L, TRANSCRIPT);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(cloudModel).call(captor.capture());
        assertTrue(captor.getValue().getContents().contains("会议纪要助手"), "查不到模板行必须回落 meeting");
    }

    @Test
    void persistSqlUpdatesOnlyItsOwnColumnsAndNeverTouchesTemplate() {
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(VALID_JSON, "stop"));

        newService(true).structure(42L, TRANSCRIPT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), anyString(), anyString(), anyString(), eq("CLOUD"), eq(42L));
        assertTrue(sql.getValue().contains("summary") && sql.getValue().contains("ai_tier"),
                "persist 只更新 summary/keywords/todos/ai_tier：" + sql.getValue());
        assertFalse(sql.getValue().contains("template"), "T3.9 坑 3：结构化不得把 template 覆盖成 NULL");
    }

    // ---- T3.8 测试补强：畸形 JSON 矩阵 + LOCAL 异常降级 ----

    @Test
    void localFailureCascadesToExtractiveWithoutRetryingSameTier() {
        // T3.8 三档矩阵补缺：CLOUD 不可用（provider 空）→ LOCAL 调用抛异常 → **不重试同一档**（times(1)）
        // → 降级 EXTRACTIVE，ai_tier 诚实落 EXTRACTIVE，任务不失败
        when(localProvider.getIfAvailable()).thenReturn(localModel);
        when(localModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Ollama 进程崩溃"));

        assertTrue(newService(true).structure(50L, TRANSCRIPT));

        verify(localModel, times(1)).call(any(Prompt.class));
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("UPDATE task_result"), summary.capture(),
                anyString(), anyString(), eq("EXTRACTIVE"), eq(50L));
        assertFalse(summary.getValue().isBlank(), "EXTRACTIVE 兜底必须产出非空 summary");
    }

    @Test
    void nonStringSummaryFailsParsingAndDegradesToExtractive() {
        // 畸形矩阵：summary 是 JSON 对象（非字符串）→ 解析失败 → 三字段一起留空并降级，任务不失败
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(
                "{\"summary\":{\"text\":\"对象型 summary\"},\"keywords\":[],\"todos\":[]}", "stop"));

        assertTrue(newService(true).structure(51L, TRANSCRIPT));

        verify(jdbc).update(contains("UPDATE task_result"), anyString(), anyString(), anyString(),
                eq("EXTRACTIVE"), eq(51L));
    }

    @Test
    void keywordNonStringElementsAreCoercedOrDroppedNotFatal() {
        // 畸形矩阵：keywords 元素非字符串——数字被 asText 强转（"123"），对象/数组转空串被丢弃；其余照常
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(
                "{\"summary\":\"正常摘要\",\"keywords\":[123,{\"bad\":true},\"有效词\"],\"todos\":[]}", "stop"));

        assertTrue(newService(true).structure(52L, TRANSCRIPT));

        verify(jdbc).update(contains("UPDATE task_result"), eq("正常摘要"),
                eq("[\"123\",\"有效词\"]"), eq("[]"), eq("CLOUD"), eq(52L));
    }

    @Test
    void todoMissingActionOrNonObjectElementIsDropped() {
        // 畸形矩阵：todos 元素缺 action / 元素是非对象（纯字符串）→ 该元素丢弃（宁缺毋滥），todos=[]，任务不失败
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(
                "{\"summary\":\"摘要\",\"keywords\":[],\"todos\":[{\"owner\":\"张三\",\"deadline\":\"下周三\"},\"纯文本\"]}",
                "stop"));

        assertTrue(newService(true).structure(53L, TRANSCRIPT));

        verify(jdbc).update(contains("UPDATE task_result"), eq("摘要"), anyString(),
                eq("[]"), eq("CLOUD"), eq(53L));
    }

    @Test
    void truncatedJsonWithValidPrefixStillDegrades() {
        // 畸形矩阵：JSON 断在中间（合法前缀 + 截断）→ JsonExtractor 括号不闭合抽取不到 → 按解析失败降级
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(
                "{\"summary\":\"合法摘要\",\"keywords\":[\"关键词1\",", "stop"));

        assertTrue(newService(true).structure(54L, TRANSCRIPT));

        verify(jdbc).update(contains("UPDATE task_result"), anyString(), anyString(), anyString(),
                eq("EXTRACTIVE"), eq(54L));
    }
}
