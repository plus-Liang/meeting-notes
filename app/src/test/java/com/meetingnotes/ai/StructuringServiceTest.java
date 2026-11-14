package com.meetingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    private final TodoService todoService = new TodoService();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 首选档 = CLOUD（有 key + 非离线）；本地档是否可用由 localProvider 决定。 */
    private StructuringService newService(boolean extractiveEnabled) {
        AiStructuringRouter router = new AiStructuringRouter(true, false, false, "", probe);
        return new StructuringService(cloudProvider, localProvider, router, todoService, jdbc, extractiveEnabled);
    }

    private StructuringService extractiveOnlyService() {
        AiStructuringRouter router = new AiStructuringRouter(false, false, false, "", probe);
        return new StructuringService(cloudProvider, localProvider, router, todoService, jdbc, true);
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

        verifyNoInteractions(jdbc);
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
}
