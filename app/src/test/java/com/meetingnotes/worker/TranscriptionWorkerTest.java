package com.meetingnotes.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private com.meetingnotes.asr.AsrClient asrClient;
    @Mock
    private com.meetingnotes.ai.StructuringService structuringService;
    @Mock
    private com.meetingnotes.ai.TranscriptPunctuator punctuator;
    @Mock
    private PasteTextReader pasteTextReader;
    @Mock
    private MonoWavExtractor monoWavExtractor;
    @Mock
    private com.meetingnotes.webhook.WebhookDeliveryService webhookService;
    @Mock
    private ListOperations<String, String> listOps;
    @Mock
    private ZSetOperations<String, String> zSetOps;
    @Mock
    private com.meetingnotes.task.QueuePublisher queuePublisher;
    @Mock
    private com.meetingnotes.task.DeadTaskRecorder deadTaskRecorder;
    @Mock
    private TaskHeartbeat taskHeartbeat;
    @Mock
    private TaskAttempts taskAttempts; // T4.94：markFailure 的 attempts 读写改走可移植原语
    @Mock
    private StalePendingQueries stalePendingQueries;
    @Mock
    private TaskCrashRecovery crashRecovery; // T4.94：StartupRecovery 的崩溃计次委托（下方两个启动恢复用例用）

    private TranscriptionWorker newWorker() {
        return new TranscriptionWorker(redis, jdbc, s3, asrClient,
                structuringService, punctuator, webhookService, pasteTextReader, monoWavExtractor,
                queuePublisher, deadTaskRecorder, taskHeartbeat, "meetingnotes:task-queue", 10, "meetingnotes", 3,
                new RedisUnavailableReporter(60, 2, java.time.Clock.systemUTC()), taskAttempts,
                new com.meetingnotes.worker.TaskTranscriber(asrClient)); /* T4.99：构造器连带（K-192 预授权）——真实 TaskTranscriber 包 mock asrClient */
    }

    @Test
    void emptyTextPersistsAsEmptyTranscriptAndMarksDone() {
        TranscriptionWorker worker = newWorker();
        worker.finalizeResult(5L, "", null);

        verify(jdbc).update(contains("task_result"), eq(5L), eq(""), ArgumentMatchers.isNull());
        verify(jdbc).update(contains("'DONE'"), eq(5L));
    }

    @Test
    void nullTextIsNormalizedToEmptyTranscriptAndMarksDone() {
        TranscriptionWorker worker = newWorker();
        worker.finalizeResult(6L, null, null);

        ArgumentCaptor<String> transcript = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("task_result"), eq(6L), transcript.capture(), ArgumentMatchers.isNull());
        verify(jdbc).update(contains("'DONE'"), eq(6L));
        assertEquals("", transcript.getValue(), "null 必须归一化为空串（D-048），而不是把 null 写进库");
    }

    @Test
    void nonEmptyTextPersistsVerbatimAndMarksDone() {
        TranscriptionWorker worker = newWorker();
        worker.finalizeResult(7L, "你好，会议开始。", null);

        verify(jdbc).update(contains("task_result"), eq(7L), eq("你好，会议开始。"), ArgumentMatchers.isNull());
        verify(jdbc).update(contains("'DONE'"), eq(7L));
    }

    /* ---- T3.9 坑 3：worker 只更新自己的列，不得把提交时写好的 template 覆盖成 NULL ---- */

    @Test
    void saveTranscriptSqlNeverTouchesTemplateColumn() {
        TranscriptionWorker worker = newWorker();
        worker.finalizeResult(30L, "会议内容", null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(30L), eq("会议内容"), ArgumentMatchers.isNull());
        assertTrue(sql.getValue().contains("ON CONFLICT (task_id)"), "upsert 幂等保留：" + sql.getValue());
        assertTrue(sql.getValue().contains("segments"), "T4.35a：segments 与 transcript 同一语句落库：" + sql.getValue());
        assertFalse(sql.getValue().contains("template"),
                "T3.9 坑 3：worker 不得把 template 写成 NULL：" + sql.getValue());
    }

    /* ---- F-1/T3.2（D-069）+ T3.3 F-7：DONE 顺序 —— transcript → 结构化（一次调用）→ 最后 DONE ---- */

    @Test
    void doneIsSetOnlyAfterTranscriptAndStructuring() {
        TranscriptionWorker worker = newWorker();
        worker.finalizeResult(11L, "会议明确了下一步安排", null);

        InOrder order = inOrder(jdbc, structuringService);
        order.verify(jdbc).update(contains("task_result"), eq(11L), eq("会议明确了下一步安排"), ArgumentMatchers.isNull());
        order.verify(structuringService).structure(11L, "会议明确了下一步安排");
        order.verify(jdbc).update(contains("'DONE'"), eq(11L));
        verify(structuringService, times(1)).structure(11L, "会议明确了下一步安排");
    }

    @Test
    void aiFailureDoesNotBlockDone() {
        TranscriptionWorker worker = newWorker();
        when(structuringService.structure(anyLong(), anyString())).thenThrow(new RuntimeException("LLM 超时"));

        worker.finalizeResult(12L, "会议内容", null);

        verify(jdbc).update(contains("'DONE'"), eq(12L));
    }

    /* ---- T4.18：标点是结构化成功后的独立一步（失败留 null 不影响 DONE；结构化失败不做标点） ---- */

    @Test
    void punctuateRunsAfterSuccessfulStructuringAndBeforeDone() {
        TranscriptionWorker worker = newWorker();
        when(structuringService.structure(anyLong(), anyString())).thenReturn(true);
        when(punctuator.punctuateAndPersist(anyLong(), anyString())).thenReturn(true);

        worker.finalizeResult(40L, "会议明确了下一步安排", null);

        InOrder order = inOrder(structuringService, punctuator, jdbc);
        order.verify(structuringService).structure(40L, "会议明确了下一步安排");
        order.verify(punctuator).punctuateAndPersist(40L, "会议明确了下一步安排");
        order.verify(jdbc).update(contains("'DONE'"), eq(40L));
    }

    @Test
    void punctuateSkippedWhenStructuringFails() {
        TranscriptionWorker worker = newWorker();
        when(structuringService.structure(anyLong(), anyString())).thenReturn(false);

        worker.finalizeResult(41L, "会议内容", null);

        verify(punctuator, never()).punctuateAndPersist(anyLong(), anyString());
        verify(jdbc).update(contains("'DONE'"), eq(41L));
    }

    @Test
    void punctuateFailureDoesNotBlockDone() {
        TranscriptionWorker worker = newWorker();
        when(structuringService.structure(anyLong(), anyString())).thenReturn(true);
        when(punctuator.punctuateAndPersist(anyLong(), anyString())).thenThrow(new RuntimeException("标点超时"));

        worker.finalizeResult(42L, "会议内容", null);

        verify(jdbc).update(contains("'DONE'"), eq(42L));
    }

    /* ---- T3.7：PASTE/字幕任务走无音频分支（跳过 ffmpeg/ASR，管线仍守 F-1/D-069） ---- */

    @Test
    void pasteTaskSkipsAsrAndFfmpegAndFinalizesDirectly() throws Exception {
        TranscriptionWorker worker = newWorker();
        // T4.77：认领（SELECT/UPDATE）挪进 claim()，本用例直接调 process() 不再需要认领 stub；
        // process 现签名收认领时的 row（管线只读 source_uri/source_type）
        java.util.Map<String, Object> row = java.util.Map.of(
                "status", "PENDING", "attempts", 0, "source_type", "PASTE",
                "source_uri", "s3://meetingnotes/tasks/20/paste.txt");
        when(pasteTextReader.read("tasks/20/paste.txt")).thenReturn("粘贴的一段会议文本");

        ReflectionTestUtils.invokeMethod(worker, "process", row, 20L);

        verify(asrClient, never()).transcribe(ArgumentMatchers.any(), ArgumentMatchers.any()); /* T4.99：双参重载同样不调 */
        verify(monoWavExtractor, never()).extract(ArgumentMatchers.any(), ArgumentMatchers.any());
        InOrder order = inOrder(jdbc, structuringService);
        order.verify(jdbc).update(contains("task_result"), eq(20L), eq("粘贴的一段会议文本"), ArgumentMatchers.isNull());
        order.verify(structuringService).structure(20L, "粘贴的一段会议文本");
        order.verify(jdbc).update(contains("'DONE'"), eq(20L));
    }

    /* ---- T4.35a：ASR 结果收口时 segments 与 transcript 同一语句落库（落库点 = finalizeResult/saveTranscript；
     *  全链路真实 ASR→segments 由 E2ETest.n/o 两用例覆盖） ---- */

    @Test
    void segmentsAreWrittenAlongsideTranscriptInSameUpsertAndDoneComesLast() throws Exception {
        TranscriptionWorker worker = newWorker();
        when(structuringService.structure(anyLong(), anyString())).thenReturn(true);
        List<com.meetingnotes.asr.AsrResponseParser.Segment> segments =
                List.of(new com.meetingnotes.asr.AsrResponseParser.Segment(0.0, 1.5, "第一句。"),
                        new com.meetingnotes.asr.AsrResponseParser.Segment(1.5, 3.2, "第二句。"));

        worker.finalizeResult(60L, "第一句。第二句。", segments);

        String expected = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(segments);
        ArgumentCaptor<String> segmentsJson = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("segments"), eq(60L), eq("第一句。第二句。"), segmentsJson.capture());
        assertEquals(expected, segmentsJson.getValue(), "segments 以 jsonb 文本随 transcript 同语句写入");
        InOrder order = inOrder(jdbc, structuringService);
        order.verify(jdbc).update(contains("segments"), eq(60L), eq("第一句。第二句。"), eq(segmentsJson.getValue()));
        order.verify(structuringService).structure(60L, "第一句。第二句。");
        order.verify(jdbc).update(contains("'DONE'"), eq(60L));
    }

    /** ASR 响应不可解析（AsrClient 抛 IllegalStateException）→ process 的既有 catch 映射 ProcessingException("ASR_ERROR")；
     *  该映射代码路径未被 T4.35a 改动，失败→RETRYING/DEAD 由下方 S-1 两用例钉住；AsrClient 抛错行为见 AsrClientTest。 */

    /* ---- S-1 状态机（T1.8）：失败走 RETRYING 重新入队 / 重试耗尽走 DEAD + dead_letter ---- */

    @Test
    void failureUnderRetryLimitMarksRetryingAndRequeues() {
        TranscriptionWorker worker = newWorker();
        // T4.94：bump + read 两步替代 UPDATE...RETURNING → 本次失败后 attempts=1，未达上限 3
        when(taskAttempts.read(9L)).thenReturn(1);
        when(queuePublisher.tryPublishToTail(9L)).thenReturn(true);

        ReflectionTestUtils.invokeMethod(worker, "markFailure", 9L,
                new ProcessingException("ASR_ERROR", "ASR 转写调用失败：连接拒绝", null));

        verify(jdbc).update(contains("'RETRYING'"), eq("ASR_ERROR"), eq(9L));
        /* T4.60 B1：失败重投必须走队尾（tryPublishToTail），且绝不碰头插通道（不插队） */
        verify(queuePublisher).tryPublishToTail(9L);
        verify(queuePublisher, org.mockito.Mockito.never()).tryPublish(9L);
        verify(jdbc, org.mockito.Mockito.never()).update(contains("'DEAD'"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** T4.52 S6：重投失败不能停在 RETRYING（无任何机制回收）——必须退回 PENDING 等启动恢复/重提自愈 */
    @Test
    void repushFailureRollsBackToPendingNotStuckRetrying() {
        TranscriptionWorker worker = newWorker();
        when(taskAttempts.read(11L)).thenReturn(1); // T4.94：bump + read 两步（本条失败后 attempts=1）
        when(queuePublisher.tryPublishToTail(11L)).thenReturn(false);

        ReflectionTestUtils.invokeMethod(worker, "markFailure", 11L,
                new ProcessingException("ASR_ERROR", "ASR 转写调用失败：连接拒绝", null));

        verify(jdbc).update(contains("'RETRYING'"), eq("ASR_ERROR"), eq(11L));
        verify(jdbc).update(contains("status = 'PENDING'"), eq(11L));
        verify(jdbc, org.mockito.Mockito.never()).update(contains("'DEAD'"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failureExhaustedMarksDeadAndWritesDeadLetterWithReason() {
        TranscriptionWorker worker = newWorker();
        // 重试耗尽：attempts=3 >= max-retries=3
        when(taskAttempts.read(10L)).thenReturn(3); // T4.94：bump + read 两步

        ReflectionTestUtils.invokeMethod(worker, "markFailure", 10L,
                new ProcessingException("ASR_ERROR", "ASR 转写调用失败：服务不可达", null));

        // T4.87 C3：DEAD+dead_letter+退款 收敛到 DeadTaskRecorder（SQL 断言在 DeadTaskRecorderTest）
        verify(deadTaskRecorder).markDead(eq(10L), eq("ASR_ERROR"), contains("服务不可达"));
        verify(queuePublisher, org.mockito.Mockito.never()).tryPublishToTail(org.mockito.ArgumentMatchers.anyLong());
        verify(queuePublisher, org.mockito.Mockito.never()).tryPublish(org.mockito.ArgumentMatchers.anyLong());
    }

    /* ---- T4.99/C6：ASR_ERROR 人话进 dead_letter.reason，原文不泄露（与 TaskTranscriber 包装串联后的端到端口径） ---- */

    @Test
    void asrErrorDeadLetterReasonIsSanitizedVerbatim() {
        TranscriptionWorker worker = newWorker();
        when(taskAttempts.read(12L)).thenReturn(3); // 重试耗尽

        ReflectionTestUtils.invokeMethod(worker, "markFailure", 12L,
                new ProcessingException("ASR_ERROR", TaskTranscriber.ASR_FAILURE_MESSAGE,
                        new RuntimeException("Connection refused: http://asr:8000/v1/audio/transcriptions")));

        org.mockito.ArgumentCaptor<String> reason = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(deadTaskRecorder).markDead(eq(12L), eq("ASR_ERROR"), reason.capture());
        assertEquals("语音识别服务调用失败，请检查 ASR 服务是否正常（详见服务端日志）", reason.getValue(), "reason 逐字（Lead A6）");
        assertFalse(reason.getValue().contains("http"), "不得含内部地址");
        assertFalse(reason.getValue().contains("asr:8000"), "不得含容器名/端口");
        assertFalse(reason.getValue().contains("Exception"), "不得含异常类名");
    }

    @Test
    void unknownErrorReasonIsSanitizedToo() {
        TranscriptionWorker worker = newWorker();
        when(taskAttempts.read(13L)).thenReturn(3); // 重试耗尽

        /* 非 ProcessingException（如 ffmpeg/IO 裸异常）→ getMessage 原文不得进 reason */
        ReflectionTestUtils.invokeMethod(worker, "markFailure", 13L,
                new IllegalStateException("java.nio.file.NoSuchFileException: /tmp/mn-asr-123/in.wav (http://internal)"));

        org.mockito.ArgumentCaptor<String> reason = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(deadTaskRecorder).markDead(eq(13L), eq("UNKNOWN_ERROR"), reason.capture());
        assertEquals("处理过程发生异常，请重试或联系管理员（详见服务端日志）", reason.getValue());
        assertFalse(reason.getValue().contains("NoSuchFileException"), "原文只进日志");
        assertFalse(reason.getValue().contains("http"), "不得含内部地址");
    }

    /* ---- T2.3 崩溃恢复：启动时回收 RUNNING 僵尸（独立 StartupRecovery）---- */

    @Test
    void startupRecoveryRequeuesRunningTasksAsPending() {
        com.meetingnotes.worker.StartupRecovery recovery = new com.meetingnotes.worker.StartupRecovery(
                jdbc, queuePublisher, stalePendingQueries, crashRecovery);
        // T4.78：启动恢复 SELECT 带僵尸判据 cutoff 参数（query(String, RowMapper, Object...) 重载）
        doReturn(List.of(5L, 7L)).when(jdbc).query(contains("WHERE status = 'RUNNING'"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(), ArgumentMatchers.any());
        // T4.94：回收走崩溃计次（NEED_REQUEUE 才重投）
        when(crashRecovery.recover(5L, "startup")).thenReturn(TaskCrashRecovery.Outcome.NEED_REQUEUE);
        when(crashRecovery.recover(7L, "startup")).thenReturn(TaskCrashRecovery.Outcome.NEED_REQUEUE);

        recovery.run(null);

        verify(jdbc).query(contains("WHERE status = 'RUNNING'"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(), ArgumentMatchers.any());
        verify(queuePublisher).tryPublish(5L);
        verify(queuePublisher).tryPublish(7L);
    }

    @Test
    void startupRecoveryWithNoZombiesLogsZeroAndPushesNothing() {
        com.meetingnotes.worker.StartupRecovery recovery = new com.meetingnotes.worker.StartupRecovery(
                jdbc, queuePublisher, stalePendingQueries, crashRecovery);
        doReturn(List.of()).when(jdbc).query(contains("WHERE status = 'RUNNING'"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(), ArgumentMatchers.any());

        recovery.run(null);

        verify(queuePublisher, org.mockito.Mockito.never()).tryPublish(org.mockito.ArgumentMatchers.anyLong());
    }

    /* ---- T4.67-fix F4（S12 真修）：认领 = 原子条件 UPDATE + 影响行数校验 ---- */

    /** 影响行数 0（状态已被其它实例/流程改走）：跳过——不读对象、不调结构化、不落库、不置 DONE。
     *  T4.77：认领行为收口在 claim()（返回 null 即跳过），本用例直接测 claim。 */
    @Test
    void claimConflictSkipsProcessingEntirely() {
        TranscriptionWorker worker = newWorker();
        when(jdbc.queryForMap(contains("FROM task WHERE id = ?"), eq(21L))).thenReturn(java.util.Map.of(
                "status", "PENDING", "attempts", 0, "source_type", "PASTE",
                "source_uri", "s3://meetingnotes/tasks/21/paste.txt"));
        when(jdbc.update(contains("AND status IN ('PENDING','RETRYING')"), eq(21L))).thenReturn(0);

        Object claimed = ReflectionTestUtils.invokeMethod(worker, "claim", 21L);

        org.junit.jupiter.api.Assertions.assertNull(claimed, "认领冲突必须返回 null（跳过）");
        verify(taskHeartbeat, never()).start(21L);
        verify(pasteTextReader, never()).read(anyString());
        verify(structuringService, never()).structure(anyLong(), anyString());
        verify(jdbc, never()).update(contains("task_result"), eq(21L), ArgumentMatchers.any(), ArgumentMatchers.any());
        verify(jdbc, never()).update(contains("'DONE'"), eq(21L));
    }

    /** 影响行数 1：claim 启动心跳并返回会话；认领 SQL 必须带原子条件（A2 变异③落点：去掉条件时本用例必红）。 */
    @Test
    void claimSuccessProceedsAndSqlIsConditional() {
        TranscriptionWorker worker = newWorker();
        TaskHeartbeat.Session session = new TaskHeartbeat.Session() {
            @Override public void close() { }
        };
        when(jdbc.queryForMap(contains("FROM task WHERE id = ?"), eq(22L))).thenReturn(java.util.Map.of(
                "status", "PENDING", "attempts", 0, "source_type", "PASTE",
                "source_uri", "s3://meetingnotes/tasks/22/paste.txt"));
        when(jdbc.update(contains("AND status IN ('PENDING','RETRYING')"), eq(22L))).thenReturn(1);
        when(taskHeartbeat.start(22L)).thenReturn(session);

        Object claimed = ReflectionTestUtils.invokeMethod(worker, "claim", 22L);

        org.junit.jupiter.api.Assertions.assertNotNull(claimed, "认领成功必须返回会话");
        verify(jdbc).update(contains("AND status IN ('PENDING','RETRYING')"), eq(22L));
        verify(taskHeartbeat).start(22L);
    }

    /* ---- T4.77-fix B1：认领抛异常（毒丸：任务行不存在/DB 瞬时故障）→ worker 不死、不 markFailure，下一条照常处理 ---- */

    /** 队列里出现库里已不存在的 task id 时：handleOne 必须 catch 住认领异常只记 error（绝不 markFailure——
     *  行不存在时认领 SELECT 会抛 EmptyResultDataAccessException），worker 存活，下一条任务照常认领并走完管线置 DONE。 */
    @Test
    void claimExceptionDoesNotKillWorkerAndNextTaskProceeds() {
        TranscriptionWorker worker = newWorker();
        when(redis.opsForZSet()).thenReturn(zSetOps); // T4.79：handleOne finally 会 ZREM 回执
        when(jdbc.queryForMap(contains("FROM task WHERE id = ?"), eq(999999L)))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        java.util.Map<String, Object> row = java.util.Map.of(
                "status", "PENDING", "attempts", 0, "source_type", "PASTE",
                "source_uri", "s3://meetingnotes/tasks/1/paste.txt");
        when(jdbc.queryForMap(contains("FROM task WHERE id = ?"), eq(1L))).thenReturn(row);
        when(jdbc.update(contains("AND status IN ('PENDING','RETRYING')"), eq(1L))).thenReturn(1);
        TaskHeartbeat.Session session = org.mockito.Mockito.mock(TaskHeartbeat.Session.class);
        when(taskHeartbeat.start(1L)).thenReturn(session);
        when(pasteTextReader.read("tasks/1/paste.txt")).thenReturn("第二条任务的内容");

        worker.handleOne("999999");
        worker.handleOne("1");

        // 毒丸路径：绝不触发 markFailure（无 attempts 回写/RETRYING/DEAD），也绝无心跳会话
        verify(taskAttempts, never()).bump(999999L); // T4.94：markFailure 的入口现在是 taskAttempts.bump
        verify(jdbc, never()).update(contains("'RETRYING'"), ArgumentMatchers.any(), eq(999999L));
        verify(jdbc, never()).update(contains("'DEAD'"), ArgumentMatchers.any(), eq(999999L));
        verify(taskHeartbeat, never()).start(999999L);
        // 下一条照常：认领 → 心跳启动 → PASTE 管线 → DONE → 心跳关闭 → MDC 清理
        verify(taskHeartbeat).start(1L);
        verify(pasteTextReader).read("tasks/1/paste.txt");
        verify(jdbc).update(contains("'DONE'"), eq(1L));
        verify(session).close();
        org.junit.jupiter.api.Assertions.assertNull(org.slf4j.MDC.get("taskId"), "handleOne 结束必须清理 MDC");
    }

    /* ---- T4.79/P-02：取出即 ZADD「处理中」回执、结束 ZREM ---- */

    /** ① T4.79-fix F2：ZADD 失败（Redis 抖动）→ 返回 false、任务退回队尾（tryPublishToTail），绝不「既没回执又不在队列」，且本轮不进 handleOne。 */
    @Test
    void zaddFailureReturnsFalseRollsBackAndSkipsHandleOne() {
        TranscriptionWorker worker = newWorker();
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.add(anyString(), anyString(), ArgumentMatchers.anyDouble()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("redis down"));
        when(queuePublisher.tryPublishToTail(7L)).thenReturn(true);

        boolean proceed = worker.markProcessing("7"); // T4.79-fix F2：失败返回 false，本轮不处理

        assertFalse(proceed, "ZADD 失败必须返回 false（本轮不进入 handleOne）");
        verify(zSetOps).add(eq("meetingnotes:worker-processing"), eq("7"), ArgumentMatchers.anyDouble());
        verify(queuePublisher).tryPublishToTail(7L);
        verifyNoInteractions(jdbc, taskHeartbeat); // handleOne 未被调用（否则必查 jdbc 认领）
    }

    /** T4.79-fix F2 补充：ZADD 成功 → markProcessing 返回 true（调用方照常进入 handleOne）。 */
    @Test
    void markProcessingReturnsTrueOnSuccess() {
        TranscriptionWorker worker = newWorker();
        when(redis.opsForZSet()).thenReturn(zSetOps);

        assertTrue(worker.markProcessing("7"), "ZADD 成功必须返回 true");

        verify(zSetOps).add(eq("meetingnotes:worker-processing"), eq("7"), ArgumentMatchers.anyDouble());
    }

    /** ② 正常处理走完 handleOne 全路径（含 finally）→ 回执 ZREM 被调用、任务置 DONE。 */
    @Test
    void normalProcessingRemovesReceipt() {
        TranscriptionWorker worker = newWorker();
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(jdbc.queryForMap(contains("FROM task WHERE id = ?"), eq(1L))).thenReturn(java.util.Map.of(
                "status", "PENDING", "attempts", 0, "source_type", "PASTE",
                "source_uri", "s3://meetingnotes/tasks/1/paste.txt"));
        when(jdbc.update(contains("AND status IN ('PENDING','RETRYING')"), eq(1L))).thenReturn(1);
        TaskHeartbeat.Session session = org.mockito.Mockito.mock(TaskHeartbeat.Session.class);
        when(taskHeartbeat.start(1L)).thenReturn(session);
        when(pasteTextReader.read("tasks/1/paste.txt")).thenReturn("回执测试内容");

        worker.handleOne("1");

        verify(zSetOps).remove("meetingnotes:worker-processing", "1");
        verify(jdbc).update(contains("'DONE'"), eq(1L));
    }

    /** ③ 认领失败（claimed=null，别人已认领）→ 也必须 ZREM（否则僵尸回执堆积），且不启动心跳。 */
    @Test
    void claimConflictStillRemovesReceipt() {
        TranscriptionWorker worker = newWorker();
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(jdbc.queryForMap(contains("FROM task WHERE id = ?"), eq(21L))).thenReturn(java.util.Map.of(
                "status", "PENDING", "attempts", 0, "source_type", "PASTE",
                "source_uri", "s3://meetingnotes/tasks/21/paste.txt"));
        when(jdbc.update(contains("AND status IN ('PENDING','RETRYING')"), eq(21L))).thenReturn(0);

        worker.handleOne("21");

        verify(zSetOps).remove("meetingnotes:worker-processing", "21");
        verify(taskHeartbeat, never()).start(21L);
    }

    /* ---- T4.79-fix F1：坏队列成员不得打死 worker ---- */

    /** handleOne("not-a-number") 不抛异常、不认领、回执清理照走；随后下一条真实任务照常处理完（「继续」语义）。 */
    @Test
    void badQueueMemberDoesNotKillWorkerAndNextTaskProceeds() {
        TranscriptionWorker worker = newWorker();
        when(redis.opsForZSet()).thenReturn(zSetOps);

        assertDoesNotThrow(() -> worker.handleOne("not-a-number"));
        verify(zSetOps).remove("meetingnotes:worker-processing", "not-a-number"); // finally 清回执（ZREM 不存在成员无副作用）
        verify(jdbc, never()).queryForMap(anyString(), ArgumentMatchers.any()); // 没到认领那步
        verifyNoInteractions(taskHeartbeat);

        // 坏成员之后，下一条真实任务照常认领并处理完
        TaskHeartbeat.Session session = org.mockito.Mockito.mock(TaskHeartbeat.Session.class);
        when(jdbc.queryForMap(contains("FROM task WHERE id = ?"), eq(1L))).thenReturn(java.util.Map.of(
                "status", "PENDING", "attempts", 0, "source_type", "PASTE",
                "source_uri", "s3://meetingnotes/tasks/1/paste.txt"));
        when(jdbc.update(contains("AND status IN ('PENDING','RETRYING')"), eq(1L))).thenReturn(1);
        when(taskHeartbeat.start(1L)).thenReturn(session);
        when(pasteTextReader.read("tasks/1/paste.txt")).thenReturn("坏成员之后的正常任务");

        assertDoesNotThrow(() -> worker.handleOne("1"));
        verify(jdbc).update(contains("'DONE'"), eq(1L));
        verify(zSetOps).remove("meetingnotes:worker-processing", "1");
    }
}
