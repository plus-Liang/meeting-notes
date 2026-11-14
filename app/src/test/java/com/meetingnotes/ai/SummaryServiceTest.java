package com.meetingnotes.ai;

import com.meetingnotes.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3.1：摘要服务——成功落库（summary/keywords/ai_tier）、解析失败与异常降级不抛、
 * 无 ChatModel 跳过、提示词带"禁止编造"约束（D-068：失败不阻塞任务，不重试）。
 */
@ExtendWith(MockitoExtension.class)
class SummaryServiceTest {

    @Mock
    private ChatModel chatModel;
    @Mock
    private ObjectProvider<ChatModel> chatModelProvider;
    @Mock
    private JdbcTemplate jdbc;

    private SummaryService newService() {
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        return new SummaryService(chatModelProvider, jdbc);
    }

    @Test
    void promptContainsStrictJsonAndNoFabricationConstraints() {
        String prompt = SummaryService.buildPrompt("转写全文示例");

        assertTrue(prompt.contains("JSON"), "必须要求模型只输出 JSON（可判）");
        assertTrue(prompt.contains("严禁编造"), "必须带'严禁编造'约束（D-068）");
        assertTrue(prompt.contains("转写全文示例"), "必须把 transcript 交给模型");
    }

    @Test
    void validJsonPersistsSummaryKeywordsAndTier() {
        when(chatModel.call(contains("转写全文示例")))
                .thenReturn("{\"summary\":\"会议讨论了学习路线\",\"keywords\":[\"学习路线\",\"AI\"]}");

        boolean ok = newService().summarize(7L, "转写全文示例");

        assertTrue(ok);
        verify(jdbc).update(contains("ai_tier = 'CLOUD'"), eq("会议讨论了学习路线"),
                eq("[\"学习路线\",\"AI\"]"), eq(7L));
    }

    @Test
    void malformedJsonWarnsAndLeavesResultUntouched() {
        String longGarbage = "x".repeat(500);
        lenient().when(chatModel.call(org.mockito.ArgumentMatchers.anyString())).thenReturn(longGarbage);

        boolean ok = newService().summarize(8L, "转写全文示例");

        assertFalse(ok, "解析失败不得抛出、不得写库");
        verify(jdbc, never()).update(contains("task_result"), any(), any(), anyLong());
    }

    @Test
    void chatModelFailureIsSwallowedAndTaskOutcomeUnaffected() {
        when(chatModel.call(org.mockito.ArgumentMatchers.anyString())).thenThrow(new RuntimeException("LLM 超时"));

        boolean ok = newService().summarize(9L, "转写全文示例");

        assertFalse(ok, "LLM 失败只 WARN，任务仍 DONE");
        verify(jdbc, never()).update(contains("task_result"), any(), any(), anyLong());
    }

    @Test
    void missingChatModelSkipsSilently() {
        when(chatModelProvider.getIfAvailable()).thenReturn(null);
        SummaryService svc = new SummaryService(chatModelProvider, jdbc);

        assertFalse(svc.summarize(10L, "转写全文示例"), "无 ChatModel（未配 key）必须优雅跳过");
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.<String>any(),
                org.mockito.ArgumentMatchers.<Object[]>any());
    }
}
