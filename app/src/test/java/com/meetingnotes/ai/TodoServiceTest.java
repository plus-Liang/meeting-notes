package com.meetingnotes.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3.2：待办抽取——提示词约束（严格 JSON + 严禁猜 owner/deadline）、合法解析落库、
 * 缺字段容错（只给 action 也行，owner/deadline 归一化为 null）、宁缺毋滥（空 action 丢弃）、
 * 解析失败/调用失败降级不抛不重试、无 ChatModel 跳过。
 */
@ExtendWith(MockitoExtension.class)
class TodoServiceTest {

    @Mock
    private ChatModel chatModel;
    @Mock
    private ObjectProvider<ChatModel> chatModelProvider;
    @Mock
    private JdbcTemplate jdbc;

    private TodoService newService() {
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        return new TodoService(chatModelProvider, jdbc);
    }

    @Test
    void promptContainsStrictJsonAndNoGuessConstraints() {
        String prompt = TodoService.buildPrompt("转写全文示例");

        assertTrue(prompt.contains("JSON"), "必须要求模型只输出 JSON（可判）");
        assertTrue(prompt.contains("todos"), "输出形状必须是 todos 数组");
        assertTrue(prompt.contains("严禁猜测"), "owner/deadline 未知必须为 null，不许猜");
        assertTrue(prompt.contains("不得编造或引申"), "action 必须有 transcript 依据");
        assertTrue(prompt.contains("转写全文示例"), "必须把 transcript 交给模型");
    }

    @Test
    void validTodosJsonPersistsAllFields() {
        when(chatModel.call(anyString()))
                .thenReturn("{\"todos\":[{\"action\":\"整理会议纪要\",\"owner\":\"张三\",\"deadline\":\"周五\"}]}");

        boolean ok = newService().extract(7L, "转写全文示例");

        assertTrue(ok);
        verify(jdbc).update(contains("todos = ?::jsonb"),
                eq("[{\"action\":\"整理会议纪要\",\"owner\":\"张三\",\"deadline\":\"周五\"}]"), eq(7L));
    }

    @Test
    void missingOwnerDeadlineToleratedAsNull() {
        // 只给 action 也行：owner/deadline 缺失归一化为 null（不许补猜）
        when(chatModel.call(anyString())).thenReturn("{\"todos\":[{\"action\":\"发周报\"}]}");

        boolean ok = newService().extract(8L, "转写全文示例");

        assertTrue(ok);
        verify(jdbc).update(contains("todos = ?::jsonb"),
                eq("[{\"action\":\"发周报\",\"owner\":null,\"deadline\":null}]"), eq(8L));
    }

    @Test
    void blankActionEntriesDroppedRatherThanGuessed() {
        // 宁缺毋滥：action 为空的条目直接丢弃，剩下的正常保留
        when(chatModel.call(anyString()))
                .thenReturn("{\"todos\":[{\"action\":\"\",\"owner\":\"张三\"},{\"action\":\"发周报\"}]}");

        boolean ok = newService().extract(9L, "转写全文示例");

        assertTrue(ok);
        verify(jdbc).update(contains("todos = ?::jsonb"),
                eq("[{\"action\":\"发周报\",\"owner\":null,\"deadline\":null}]"), eq(9L));
    }

    @Test
    void emptyTodosArrayPersistsAsEmptyArrayNotFailure() {
        when(chatModel.call(anyString())).thenReturn("{\"todos\":[]}");

        boolean ok = newService().extract(10L, "转写全文示例");

        assertTrue(ok, "合法的空 todos 是正常业务结果，落 [] 而不是保持 null");
        verify(jdbc).update(contains("todos = ?::jsonb"), eq("[]"), eq(10L));
    }

    @Test
    void malformedJsonWarnsLeavesTodosNullAndDoesNotRetry() {
        String longGarbage = "x".repeat(500);
        lenient().when(chatModel.call(anyString())).thenReturn(longGarbage);

        boolean ok = newService().extract(11L, "转写全文示例");

        assertFalse(ok, "解析失败不得抛出、不得写库");
        verify(jdbc, never()).update(contains("task_result"), any(), anyLong());
        verify(chatModel, never()).call(contains("重试"));
    }

    @Test
    void todosMissingArrayTreatedAsParseFailure() {
        // 模型输出形状漂移（没有 todos 数组）→ 与解析失败同路：WARN + 留空，不写库
        lenient().when(chatModel.call(anyString())).thenReturn("{\"summary\":\"形状不对\"}");

        assertFalse(newService().extract(12L, "转写全文示例"));
        verify(jdbc, never()).update(anyString(), any(), anyLong());
    }

    @Test
    void chatModelFailureIsSwallowedAndTaskOutcomeUnaffected() {
        when(chatModel.call(anyString())).thenThrow(new RuntimeException("LLM 超时"));

        boolean ok = newService().extract(13L, "转写全文示例");

        assertFalse(ok, "LLM 失败只 WARN，任务仍 DONE");
        verify(jdbc, never()).update(anyString(), any(), anyLong());
    }

    @Test
    void missingChatModelSkipsSilently() {
        when(chatModelProvider.getIfAvailable()).thenReturn(null);
        TodoService svc = new TodoService(chatModelProvider, jdbc);

        assertFalse(svc.extract(14L, "转写全文示例"), "无 ChatModel（未配 key）必须优雅跳过");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
