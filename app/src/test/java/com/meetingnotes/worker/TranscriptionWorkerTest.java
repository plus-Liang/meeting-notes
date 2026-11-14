package com.meetingnotes.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-048：ASR 返回空文本（纯音乐/静音/无人声）是业务结果，不是故障。
 * 必须正常落 task_result（transcript=""）并把任务置 DONE，不得抛异常进 RETRYING/DEAD。
 */
@ExtendWith(MockitoExtension.class)
class TranscriptionWorkerTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private S3Client s3;
    @Mock
    private TranscriptionModel transcriptionModel;
    @Mock
    private ListOperations<String, String> listOps;

    private TranscriptionWorker newWorker() {
        return new TranscriptionWorker(redis, jdbc, s3, transcriptionModel,
                "meetingnotes:task-queue", 10, "ffmpeg", "meetingnotes", 3);
    }

    @Test
    void emptyTextPersistsAsEmptyTranscriptAndMarksDone() {
        TranscriptionWorker worker = newWorker();
        worker.persistResult(5L, "");

        verify(jdbc).update(contains("task_result"), eq(5L), eq(""));
        verify(jdbc).update(contains("'DONE'"), eq(5L));
    }

    @Test
    void nullTextIsNormalizedToEmptyTranscriptAndMarksDone() {
        TranscriptionWorker worker = newWorker();
        worker.persistResult(6L, null);

        ArgumentCaptor<String> transcript = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("task_result"), eq(6L), transcript.capture());
        verify(jdbc).update(contains("'DONE'"), eq(6L));
        assertEquals("", transcript.getValue(), "null 必须归一化为空串（D-048），而不是把 null 写进库");
    }

    @Test
    void nonEmptyTextPersistsVerbatimAndMarksDone() {
        TranscriptionWorker worker = newWorker();
        worker.persistResult(7L, "你好，会议开始。");

        verify(jdbc).update(contains("task_result"), eq(7L), eq("你好，会议开始。"));
        verify(jdbc).update(contains("'DONE'"), eq(7L));
    }

    /* ---- S-1 状态机（T1.8）：失败走 RETRYING 重新入队 / 重试耗尽走 DEAD + dead_letter ---- */

    @Test
    void failureUnderRetryLimitMarksRetryingAndRequeues() {
        TranscriptionWorker worker = newWorker();
        // UPDATE ... RETURNING attempts → 本次失败后 attempts=1，未达上限 3
        doReturn(1).when(jdbc).query(contains("RETURNING attempts"),
                ArgumentMatchers.<ResultSetExtractor<Integer>>any(), eq(9L));
        when(redis.opsForList()).thenReturn(listOps);

        ReflectionTestUtils.invokeMethod(worker, "markFailure", 9L,
                new TranscriptionWorker.ProcessingException("ASR_ERROR", "ASR 转写调用失败：连接拒绝", null));

        verify(jdbc).update(contains("'RETRYING'"), eq("ASR_ERROR"), eq(9L));
        verify(listOps).rightPush("meetingnotes:task-queue", "9");
        verify(jdbc, org.mockito.Mockito.never()).update(contains("'DEAD'"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failureExhaustedMarksDeadAndWritesDeadLetterWithReason() {
        TranscriptionWorker worker = newWorker();
        // 重试耗尽：attempts=3 >= max-retries=3
        doReturn(3).when(jdbc).query(contains("RETURNING attempts"),
                ArgumentMatchers.<ResultSetExtractor<Integer>>any(), eq(10L));

        ReflectionTestUtils.invokeMethod(worker, "markFailure", 10L,
                new TranscriptionWorker.ProcessingException("ASR_ERROR", "ASR 转写调用失败：服务不可达", null));

        verify(jdbc).update(contains("'DEAD'"), eq("ASR_ERROR"), eq(10L));
        verify(jdbc).update(contains("dead_letter"), eq(10L), contains("服务不可达"));
        verify(listOps, org.mockito.Mockito.never()).rightPush(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}
