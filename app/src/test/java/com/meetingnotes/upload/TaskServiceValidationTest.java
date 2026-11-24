package com.meetingnotes.upload;

import com.meetingnotes.task.QueuePublisher;
import com.meetingnotes.task.QuotaService;
import com.meetingnotes.task.TaskRequeueSupport;
import com.meetingnotes.task.TaskService;
import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传校验链路单测（T1.8 / spec N6·N8 / D-028）：校验顺序 magic → 大小 → 时长 → 幂等 → 配额。
 * 依赖全部 mock，不依赖 PG/MinIO/Redis/ffprobe；每条断言都断言具体值（错误码 / 状态 / 字段）。
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceValidationTest {

    private static final String RIFF_WAV_HEAD = "RIFF1234WAVEfmt ";

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private S3Client s3;
    @Mock
    private AudioDurationProbe probe;
    @Mock
    private QuotaService quotaService;
    @Mock
    private QueuePublisher queuePublisher;
    @Mock
    private TaskRequeueSupport taskRequeueSupport;
    @Mock
    private TransactionTemplate transactionTemplate;

    private TaskService newService(long maxSizeBytes, long maxDurationSec) {
        return new TaskService(jdbc, s3, probe, quotaService, queuePublisher, taskRequeueSupport, transactionTemplate,
                maxSizeBytes, maxDurationSec, "meetingnotes");
    }

    /** 让 transactionTemplate.execute(...) 直接执行回调（事务管理器行为不在本单测范围）。 */
    private void runCallbackForReal() {
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
    }

    private static MockMultipartFile wav(long extraBytes) {
        byte[] body = new byte[(int) extraBytes];
        byte[] all = new byte[RIFF_WAV_HEAD.length() + body.length];
        System.arraycopy(RIFF_WAV_HEAD.getBytes(), 0, all, 0, RIFF_WAV_HEAD.length());
        System.arraycopy(body, 0, all, RIFF_WAV_HEAD.length(), body.length);
        return new MockMultipartFile("file", "audio.wav", null, all);
    }


    @org.junit.jupiter.api.BeforeEach
    void setUpUserContextT437a() {
        com.meetingnotes.common.UserContext.set(1L);
    }

    @org.junit.jupiter.api.AfterEach
    void clearUserContextT437a() {
        com.meetingnotes.common.UserContext.clear();
    }

    @Test
    void executableRenamedToMp3IsRejected400InvalidMedia() {
        // MZ 头（Windows PE）改名 .mp3：扩展名不作数，文件头必拒（spec N6）
        byte[] mz = {(byte) 0x4D, (byte) 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "fake.mp3", null, mz);

        ApiException e = assertThrows(ApiException.class,
                () -> newService(209715200L, 3600L).createUpload(file));

        assertEquals(400, e.getStatus());
        assertEquals("INVALID_MEDIA", e.getCode());
    }

    @Test
    void unknownContainerRejected400WithMp3FallbackHint() {
        // T3.10 / spec §4：未识别容器（随机字节 .xyz）→ 400 INVALID_MEDIA，message 含"转成 mp3"兜底指引
        byte[] mystery = {0x13, (byte) 0xAB, 0x00, (byte) 0x9C, 0x42, 0x11, (byte) 0xF0, 0x07,
                0x33, 0x5A, (byte) 0xCD, 0x21, 0x64, 0x09, (byte) 0x88, 0x15};
        MockMultipartFile file = new MockMultipartFile("file", "mystery.xyz", null, mystery);

        ApiException e = assertThrows(ApiException.class,
                () -> newService(209715200L, 3600L).createUpload(file));

        assertEquals(400, e.getStatus());
        assertEquals("INVALID_MEDIA", e.getCode());
        assertTrue(e.getMessage().contains("转成 mp3"),
                "失败提示必须含 mp3 转码兜底指引，实际：" + e.getMessage());
    }

    @Test
    void oversizeWavIsRejected413SizeExceeded() {
        // 合法 wav 头但超过 max-size-bytes=1024：走 servlet 之后的第二道流式复核
        MockMultipartFile file = wav(4096);

        ApiException e = assertThrows(ApiException.class,
                () -> newService(1024L, 3600L).createUpload(file));

        assertEquals(413, e.getStatus());
        assertEquals("SIZE_EXCEEDED", e.getCode());
        assertTrue(e.getMessage().contains("1024"), "错误消息应带上限值，实际：" + e.getMessage());
    }

    @Test
    void overDurationWavIsRejected400DurationExceeded() throws IOException {
        // 合法 wav、大小过关，但 ffprobe 读出 90s > max-duration-sec=60（D-028）
        when(probe.durationSeconds(any())).thenReturn(90.0);
        MockMultipartFile file = wav(64);

        ApiException e = assertThrows(ApiException.class,
                () -> newService(209715200L, 60L).createUpload(file));

        assertEquals(400, e.getStatus());
        assertEquals("DURATION_EXCEEDED", e.getCode());
        assertTrue(e.getMessage().contains("90"), "错误消息应带实际时长，实际：" + e.getMessage());
    }

    @Test
    void validWavPassesAllChecksPersistsTaskAndEnqueues() throws IOException {
        // 正向：格式/大小/时长/配额全过 → task(PENDING) + source_uri 回填 + Redis 入队 + 响应字段
        when(probe.durationSeconds(any())).thenReturn(90.0);
        runCallbackForReal();
        // T2.1：INSERT ... ON CONFLICT (user_id, content_hash) DO NOTHING RETURNING id → 新任务返回 7
        doReturn(7L).when(jdbc).query(contains("ON CONFLICT (user_id, content_hash)"),
                ArgumentMatchers.<ResultSetExtractor<Long>>any(), eq(1L), any(), any(), any(), any());

        TaskService service = newService(209715200L, 3600L);
        com.meetingnotes.common.TaskCreatedResponse resp = service.createUpload(wav(64));

        assertEquals(7L, resp.taskId());
        assertEquals("PENDING", resp.status());
        assertFalse(resp.reused(), "新文件不是幂等命中");
        assertEquals(90L, resp.durationSec().longValue(), "音频任务 duration_sec 仍是非空数字（T3.7 只放宽类型不改键）");
        verify(jdbc).update(contains("source_uri"), eq("s3://meetingnotes/tasks/7/audio.wav"), eq(7L));
        verify(queuePublisher).publish(7L);
        verify(quotaService).consume(eq(1L), anyInt());
    }

    /* ---- T2.1 幂等：唯一约束冲突 → 回查已有任务（reused）→ DEAD 复活重投（D-049） ---- */

    @Test
    void duplicateUploadReturnsExistingTaskWithoutQuotaOrEnqueue() throws IOException {
        // 冲突：INSERT 返回空 → 回查已有任务（真实 status=DONE）→ reused，不消耗配额、不入队
        when(probe.durationSeconds(any())).thenReturn(90.0);
        runCallbackForReal();
        doReturn(null).when(jdbc).query(contains("ON CONFLICT (user_id, content_hash)"),
                ArgumentMatchers.<ResultSetExtractor<Long>>any(), eq(1L), any(), any(), any(), any());
        doReturn(new TaskService.ExistingTask(7L, "DONE")).when(jdbc)
                .query(contains("SELECT id, status FROM task"),
                        ArgumentMatchers.<ResultSetExtractor<TaskService.ExistingTask>>any(), any(), eq(1L));

        com.meetingnotes.common.TaskCreatedResponse resp = newService(209715200L, 3600L).createUpload(wav(64));

        assertEquals(7L, resp.taskId());
        assertEquals("DONE", resp.status(), "幂等命中必须返回库里真实状态");
        assertTrue(resp.reused());
        verify(quotaService, never()).consume(anyLong(), anyInt());
        verify(queuePublisher, never()).publish(anyLong());
    }

    @Test
    void duplicateDeadTaskIsRevivedRequeuedAndReportedAsPending() throws IOException {
        // D-049：命中 DEAD → attempts=0 / status=PENDING / error_code=NULL → 重新 leftPush
        when(probe.durationSeconds(any())).thenReturn(90.0);
        runCallbackForReal();
        doReturn(null).when(jdbc).query(contains("ON CONFLICT (user_id, content_hash)"),
                ArgumentMatchers.<ResultSetExtractor<Long>>any(), eq(1L), any(), any(), any(), any());
        doReturn(new TaskService.ExistingTask(8L, "DEAD")).when(jdbc)
                .query(contains("SELECT id, status FROM task"),
                        ArgumentMatchers.<ResultSetExtractor<TaskService.ExistingTask>>any(), any(), eq(1L));

        com.meetingnotes.common.TaskCreatedResponse resp = newService(209715200L, 3600L).createUpload(wav(64));

        assertEquals(8L, resp.taskId());
        assertEquals("PENDING", resp.status(), "DEAD 复活后返回 PENDING");
        assertTrue(resp.reused());
        /* T4.60 B2：复活 SQL 收敛到 TaskRequeueSupport（SQL 文本由 TaskRequeueSupportTest 钉住） */
        verify(taskRequeueSupport).reviveDeadThenPublish(8L);
        verify(queuePublisher, never()).publish(anyLong());
    }

    @Test
    void storageFailureYields500StorageError() throws IOException {
        // T2.11 F-2：MinIO 故障 → 500 STORAGE_ERROR（稳定错误码），而不是裸 SdkException
        when(probe.durationSeconds(any())).thenReturn(90.0);
        runCallbackForReal();
        doReturn(11L).when(jdbc).query(contains("ON CONFLICT (user_id, content_hash)"),
                ArgumentMatchers.<ResultSetExtractor<Long>>any(), eq(1L), any(), any(), any(), any());
        when(s3.putObject(ArgumentMatchers.<software.amazon.awssdk.services.s3.model.PutObjectRequest>any(),
                ArgumentMatchers.<software.amazon.awssdk.core.sync.RequestBody>any()))
                .thenThrow(software.amazon.awssdk.core.exception.SdkClientException.builder()
                        .message("connection refused").build());

        ApiException e = assertThrows(ApiException.class,
                () -> newService(209715200L, 3600L).createUpload(wav(64)));

        assertEquals(500, e.getStatus());
        assertEquals("STORAGE_ERROR", e.getCode());
        assertFalse(e.getMessage().contains("connection refused"), "错误消息不得外泄底层异常细节");
    }

    @Test
    void duplicateRetryingTaskReturnsRealStatusWithoutRevival() throws IOException {
        // RETRYING：markFailure 已重新入队过，命中时只返回真实状态，不重复入队/不复活
        when(probe.durationSeconds(any())).thenReturn(90.0);
        runCallbackForReal();
        doReturn(null).when(jdbc).query(contains("ON CONFLICT (user_id, content_hash)"),
                ArgumentMatchers.<ResultSetExtractor<Long>>any(), eq(1L), any(), any(), any(), any());
        doReturn(new TaskService.ExistingTask(9L, "RETRYING")).when(jdbc)
                .query(contains("SELECT id, status FROM task"),
                        ArgumentMatchers.<ResultSetExtractor<TaskService.ExistingTask>>any(), any(), eq(1L));

        com.meetingnotes.common.TaskCreatedResponse resp = newService(209715200L, 3600L).createUpload(wav(64));

        assertEquals(9L, resp.taskId());
        assertEquals("RETRYING", resp.status());
        assertTrue(resp.reused());
        verify(jdbc, never()).update(contains("'PENDING'"), eq(9L));
        verify(queuePublisher, never()).publish(anyLong());
    }

    /* ---- T3.9 模板：非法值先行 400；文件名自动推荐 + 提交事务内落 template 行 ---- */

    @Test
    void uploadWithInvalidTemplateRejected400BeforeAnySideEffect() throws IOException {
        ApiException e = assertThrows(ApiException.class,
                () -> newService(209715200L, 3600L).createUpload(wav(64), "weekly"));

        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());
        assertEquals("template 只支持 meeting/course/interview/podcast/shortvideo", e.getMessage(),
                "不静默回落（US-12 case 4），message 逐字固定（D-085）");
        verify(probe, never()).durationSeconds(any());
        verify(transactionTemplate, never()).execute(any());
        verify(quotaService, never()).consume(anyLong(), anyInt());
    }

    @Test
    void uploadRecommendsTemplateFromFilenameAndPersistsRow() throws IOException {
        when(probe.durationSeconds(any())).thenReturn(90.0);
        runCallbackForReal();
        doReturn(15L).when(jdbc).query(contains("ON CONFLICT (user_id, content_hash)"),
                ArgumentMatchers.<ResultSetExtractor<Long>>any(), eq(1L), any(), any(), any(), any());

        // 不传 template：文件名"公开课Lecture03" 命中 course 关键字 → course（来源：文件名）
        MockMultipartFile file = new MockMultipartFile("file", "公开课Lecture03.wav", null, wav(64).getBytes());
        com.meetingnotes.common.TaskCreatedResponse resp = newService(209715200L, 3600L).createUpload(file, null);

        assertEquals(15L, resp.taskId());
        verify(jdbc).update(contains("INSERT INTO task_result"), eq(15L), eq("course"));
    }
}
