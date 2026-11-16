package com.meetingnotes.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T4.18：转写 AI 加标点——独立 LLM 调用（不并入结构化主调用），仅 CLOUD 档可用、
 * transcript 非空且 ≤ LLM_PUNCTUATE_MAX_CHARS（0=关闭）时做；失败/超时/截断/长度异常
 * → false 且 transcript_punctuated 留 null（任务仍 DONE、ai_tier 不动，D-068/D-075 同款）。
 * 可证伪断言：任何失败路径都不得写库；成功路径 SQL 只更新 transcript_punctuated。
 */
@ExtendWith(MockitoExtension.class)
class TranscriptPunctuatorTest {

    private static final String TRANSCRIPT = "我们开会讨论一下项目进度然后张三说下周三之前要提交材料大家都同意了";
    private static final String PUNCTUATED = "我们开会讨论一下项目进度。然后张三说，下周三之前要提交材料，大家都同意了。";

    @Mock
    private ChatModel cloudModel;
    @Mock
    private ObjectProvider<ChatModel> cloudProvider;
    @Mock
    private JdbcTemplate jdbc;

    private TranscriptPunctuator newPunctuator(int maxChars) {
        return new TranscriptPunctuator(cloudProvider, jdbc, maxChars);
    }

    private static ChatResponse response(String text, String finishReason) {
        Generation generation = new Generation(new AssistantMessage(text == null ? "" : text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        return new ChatResponse(List.of(generation));
    }

    @Test
    void successPersistsPunctuatedTranscript() {
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(PUNCTUATED, "stop"));

        assertTrue(newPunctuator(6000).punctuateAndPersist(7L, TRANSCRIPT));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(PUNCTUATED), eq(7L));
        assertTrue(sql.getValue().contains("transcript_punctuated"),
                "只更新 transcript_punctuated 列：" + sql.getValue());
        assertFalse(sql.getValue().contains("UPDATE task SET"),
                "只动 task_result，不得碰 task 表或原始 transcript：" + sql.getValue());
    }

    @Test
    void promptCarriesNoRewriteConstraintAndTranscript() {
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(PUNCTUATED, "stop"));

        newPunctuator(6000).punctuateAndPersist(8L, TRANSCRIPT);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(cloudModel, times(1)).call(captor.capture());
        String prompt = captor.getValue().getContents();
        assertTrue(prompt.contains("不得改写用词") && prompt.contains("不得删句") && prompt.contains("不得增加内容"),
                "提示词必须写死只加标点约束");
        assertTrue(prompt.contains(TRANSCRIPT), "transcript 原样注入");
        assertTrue(prompt.contains("只加标点"), "任务口径进提示词");
    }

    @Test
    void disabledWhenMaxCharsZero() {
        assertFalse(newPunctuator(0).punctuateAndPersist(9L, TRANSCRIPT), "0=关闭");
        verifyNoInteractions(cloudModel, jdbc);
    }

    @Test
    void skipsWhenTranscriptTooLong() {
        assertFalse(newPunctuator(10).punctuateAndPersist(10L, "这句话超过十个字的长度限制了吗"), "超上限跳过");
        verifyNoInteractions(cloudModel, jdbc);
    }

    @Test
    void skipsWhenCloudModelUnavailable() {
        when(cloudProvider.getIfAvailable()).thenReturn(null);

        assertFalse(newPunctuator(6000).punctuateAndPersist(11L, TRANSCRIPT), "CLOUD 档不可用跳过");
        verifyNoInteractions(cloudModel, jdbc);
    }

    @Test
    void skipsWhenFinishReasonLength() {
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(PUNCTUATED, "length"));

        assertFalse(newPunctuator(6000).punctuateAndPersist(12L, TRANSCRIPT), "截断输出不可信");
        verifyNoInteractions(jdbc);
    }

    @Test
    void skipsWhenCallThrows() {
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM 超时"));

        assertFalse(newPunctuator(6000).punctuateAndPersist(13L, TRANSCRIPT), "异常 → false，任务仍 DONE");
        verifyNoInteractions(jdbc);
    }

    @Test
    void skipsWhenOutputBlank() {
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response("  ", "stop"));

        assertFalse(newPunctuator(6000).punctuateAndPersist(14L, TRANSCRIPT));
        verifyNoInteractions(jdbc);
    }

    @Test
    void skipsWhenOutputLengthAbnormal() {
        // 长度异常：输出删掉了约 20% 内容（去标点后差异 >5%）→ 不落库（防改写/删句漏网）
        String shortened = "我们开会讨论一下。张三说下周三提交材料。";
        when(cloudProvider.getIfAvailable()).thenReturn(cloudModel);
        when(cloudModel.call(any(Prompt.class))).thenReturn(response(shortened, "stop"));

        assertFalse(newPunctuator(6000).punctuateAndPersist(15L, TRANSCRIPT), "删句式输出必须被长度检查拦下");
        verifyNoInteractions(jdbc);
    }

    @Test
    void blankTranscriptSkips() {
        assertFalse(newPunctuator(6000).punctuateAndPersist(16L, "   "));
        verifyNoInteractions(cloudModel, jdbc);
    }

    @Test
    void lengthPlausibleToleranceIsFivePercent() {
        String base = "字".repeat(100);
        assertTrue(TranscriptPunctuator.lengthPlausible(base, base + "。！？，"), "只加标点 → 合理");
        assertFalse(TranscriptPunctuator.lengthPlausible(base, base.substring(0, 90)), "删 10% → 异常");
        assertTrue(TranscriptPunctuator.lengthPlausible(base, base.substring(0, 96)), "差 4% → 容忍");
        assertFalse(TranscriptPunctuator.lengthPlausible(base, base + base), "翻倍 → 异常");
        assertTrue(TranscriptPunctuator.lengthPlausible("", ""), "空对空退化为合理（上层已拦空输入）");
    }

    @Test
    void strippedLengthCountsOnlyLettersDigitsAndCjk() {
        assertEquals(8, TranscriptPunctuator.strippedLength("张三，说：ok 123。"), "汉字+字母+数字计数，标点/空格不计");
        assertEquals(0, TranscriptPunctuator.strippedLength("，。！？ "));
    }
}
