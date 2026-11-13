package com.meetingnotes.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

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
}
