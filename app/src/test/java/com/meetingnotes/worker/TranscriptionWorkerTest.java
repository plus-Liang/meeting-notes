package com.meetingnotes.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-048：ASR 返回空文本（纯音乐/静音/无人声）是业务结果，不是故障，必须正常落 task_result。
 * F-1/T3.2（D-069）：DONE 必须最后置 —— transcript 落库 → 结构化 → DONE，
 * 外部读到 DONE 时 summary/todos 必已落库；AI 失败不阻塞 DONE。
 * T3.3（F-7）：结构化是**一次**调用（摘要+关键词+待办合并），worker 只调一次、只调一个服务。
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
    private com.meetingnotes.ai.StructuringService structuringService;
    @Mock
    private PasteTextReader pasteTextReader;
    @Mock
    private MonoWavExtractor monoWavExtractor;
    @Mock
    private com.meetingnotes.webhook.WebhookDeliveryService webhookService;
    @Mock
    private ListOperations<String, String> listOps;

    private TranscriptionWorker newWorker() {
        return new TranscriptionWorker(redis, jdbc, s3, transcriptionModel,
                structuringService, webhookService, pasteTextReader, monoWavExtractor,
                "meetingnotes:task-queue", 10, "meetingnotes", 3);
    }

    @Test
    void emptyTextPersistsAsEmptyTranscriptAndMarksDone() {
        TranscriptionWorker worker = newWorker();
        worker.finalizeResult(5L, "");

        verify(jdbc).update(contains("task_result"), eq(5L), eq(""));
        verify(jdbc).update(contains("'DONE'"), eq(5L));
    }

    @Test
    void nullTextIsNormalizedToEmptyTranscriptAndMarksDone() {
        TranscriptionWorker worker = newWorker();
        worker.finalizeResult(6L, null);

        ArgumentCaptor<String> transcript = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("task_result"), eq(6L), transcript.capture());
        verify(jdbc).update(contains("'DONE'"), eq(6L));
        assertEquals("", transcript.getValue(), "null 必须归一化为空串（D-048），而不是把 null 写进库");
    }

    @Test
    void nonEmptyTextPersistsVerbatimAndMarksDone() {
        TranscriptionWorker worker = newWorker();
        worker.finalizeResult(7L, "你好，会议开始。");

        verify(jdbc).update(contains("task_result"), eq(7L), eq("你好，会议开始。"));
        verify(jdbc).update(contains("'DONE'"), eq(7L));
    }

    /* ---- T3.9 坑 3：worker 只更新自己的列，不得把提交时写好的 template 覆盖成 NULL ---- */

    @Test
    void saveTranscriptSqlNeverTouchesTemplateColumn() {
        TranscriptionWorker worker = newWorker();
        worker.finalizeResult(30L, "会议内容");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(30L), eq("会议内容"));
        assertTrue(sql.getValue().contains("ON CONFLICT (task_id)"), "upsert 幂等保留：" + sql.getValue());
        assertFalse(sql.getValue().contains("template"),
                "T3.9 坑 3：worker 不得把 template 写成 NULL：" + sql.getValue());
    }

    /* ---- F-1/T3.2（D-069）+ T3.3 F-7：DONE 顺序 —— transcript → 结构化（一次调用）→ 最后 DONE ---- */

    @Test
    void doneIsSetOnlyAfterTranscriptAndStructuring() {
        TranscriptionWorker worker = newWorker();
        worker.finalizeResult(11L, "会议明确了下一步安排");

        InOrder order = inOrder(jdbc, structuringService);
        order.verify(jdbc).update(contains("task_result"), eq(11L), eq("会议明确了下一步安排"));
        order.verify(structuringService).structure(11L, "会议明确了下一步安排");
        order.verify(jdbc).update(contains("'DONE'"), eq(11L));
        verify(structuringService, times(1)).structure(11L, "会议明确了下一步安排");
    }

    @Test
    void aiFailureDoesNotBlockDone() {
        TranscriptionWorker worker = newWorker();
        when(structuringService.structure(anyLong(), anyString())).thenThrow(new RuntimeException("LLM 超时"));

        worker.finalizeResult(12L, "会议内容");

        verify(jdbc).update(contains("'DONE'"), eq(12L));
    }

    /* ---- T3.7：PASTE/字幕任务走无音频分支（跳过 ffmpeg/ASR，管线仍守 F-1/D-069） ---- */

    @Test
    void pasteTaskSkipsAsrAndFfmpegAndFinalizesDirectly() throws Exception {
        TranscriptionWorker worker = newWorker();
        when(jdbc.queryForMap(contains("FROM task WHERE id = ?"), eq(20L))).thenReturn(java.util.Map.of(
                "status", "PENDING", "attempts", 0, "source_type", "PASTE",
                "source_uri", "s3://meetingnotes/tasks/20/paste.txt"));
        when(pasteTextReader.read("tasks/20/paste.txt")).thenReturn("粘贴的一段会议文本");

        ReflectionTestUtils.invokeMethod(worker, "process", 20L);

        verify(transcriptionModel, never()).transcribe(ArgumentMatchers.any());
        verify(monoWavExtractor, never()).extract(ArgumentMatchers.any(), ArgumentMatchers.any());
        InOrder order = inOrder(jdbc, structuringService);
        order.verify(jdbc).update(contains("task_result"), eq(20L), eq("粘贴的一段会议文本"));
        order.verify(structuringService).structure(20L, "粘贴的一段会议文本");
        order.verify(jdbc).update(contains("'DONE'"), eq(20L));
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

    /* ---- T2.3 崩溃恢复：启动时回收 RUNNING 僵尸（独立 StartupRecovery）---- */

    @Test
    void startupRecoveryRequeuesRunningTasksAsPending() {
        com.meetingnotes.worker.StartupRecovery recovery = new com.meetingnotes.worker.StartupRecovery(
                jdbc, redis, "meetingnotes:task-queue");
        doReturn(List.of(5L, 7L)).when(jdbc).query(contains("WHERE status = 'RUNNING'"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any());
        when(redis.opsForList()).thenReturn(listOps);

        recovery.run(null);

        verify(jdbc).query(contains("WHERE status = 'RUNNING'"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any());
        verify(listOps).leftPush("meetingnotes:task-queue", "5");
        verify(listOps).leftPush("meetingnotes:task-queue", "7");
    }

    @Test
    void startupRecoveryWithNoZombiesLogsZeroAndPushesNothing() {
        com.meetingnotes.worker.StartupRecovery recovery = new com.meetingnotes.worker.StartupRecovery(
                jdbc, redis, "meetingnotes:task-queue");
        doReturn(List.of()).when(jdbc).query(contains("WHERE status = 'RUNNING'"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any());

        recovery.run(null);

        verify(listOps, org.mockito.Mockito.never()).leftPush(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString());
    }
}
