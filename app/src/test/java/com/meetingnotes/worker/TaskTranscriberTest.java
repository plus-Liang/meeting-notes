package com.meetingnotes.worker;

import com.meetingnotes.asr.AsrClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4.99/C5/C9：转写步骤独立组件——语言透传 + ASR_ERROR 人话脱敏（cause 保留）。 */
@ExtendWith(MockitoExtension.class)
class TaskTranscriberTest {

    @Mock
    private AsrClient asrClient;

    @Test
    void languagePassesThroughToAsrClientAndEmptyBecomesNull() throws Exception {
        Path wav = Path.of("/tmp/x.wav");
        when(asrClient.transcribe(wav, null)).thenReturn(
                new AsrClient.Transcription("文本", List.of(), null));
        when(asrClient.transcribe(wav, "en")).thenReturn(
                new AsrClient.Transcription("hello", List.of(), "en"));

        assertEquals("文本", new TaskTranscriber(asrClient).transcribe(null, wav).text(), "null 透传");
        assertEquals("hello", new TaskTranscriber(asrClient).transcribe("en", wav).text(), "显式码透传");

        new TaskTranscriber(asrClient).transcribe("", wav); /* COALESCE 占位 '' → null（不加字段） */
        verify(asrClient).transcribe(eq(wav), eq("en"));
        verify(asrClient, org.mockito.Mockito.times(2)).transcribe(eq(wav), eq((String) null)); /* null 与 '' 各一次 */
    }

    @Test
    void asrFailureBecomesSanitizedAsrErrorWithCauseKept() throws Exception {
        Path wav = Path.of("/tmp/x.wav");
        when(asrClient.transcribe(any(Path.class), eq("en")))
                .thenThrow(new RuntimeException("Connection refused: http://asr:8000/v1/audio/transcriptions"));

        ProcessingException e = assertThrows(ProcessingException.class,
                () -> new TaskTranscriber(asrClient).transcribe("en", wav));

        assertEquals("ASR_ERROR", e.code, "稳定错误码不变（页面/台账依赖）");
        assertEquals("语音识别服务调用失败，请检查 ASR 服务是否正常（详见服务端日志）", e.getMessage(), "人话逐字（Lead A6）");
        assertFalse(e.getMessage().contains("http"), "不得含内部地址");
        assertFalse(e.getMessage().contains("asr:8000"), "不得含容器名/端口");
        assertFalse(e.getMessage().contains("Exception"), "不得含异常类名");
        assertNotEquals(e.getMessage(), e.getCause().getMessage(), "原文只进 cause（服务端日志），不进用户可见文案");
        assertNotNull(e.getCause(), "cause 必须保留（log.error 输出完整堆栈）");
        assertEquals("Connection refused: http://asr:8000/v1/audio/transcriptions", e.getCause().getMessage());
        assertTrue(new TaskTranscriber(asrClient) instanceof TaskTranscriber);
    }
}
