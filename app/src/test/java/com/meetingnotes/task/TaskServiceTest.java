package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.TaskCreatedResponse;
import com.meetingnotes.common.UserContext;
import com.meetingnotes.upload.AudioDurationProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.50：幂等命中 PENDING → 重新入队（"客户端重传即自愈"）；Redis 不可用时入队失败 →
 * 503 QUEUE_UNAVAILABLE（不得假成功 201）。只覆盖幂等命中路径——全新任务路径由 E2E 覆盖。
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final String QUEUE_KEY = "meetingnotes:task-queue";

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

    @BeforeEach
    void setUpUserContext() {
        UserContext.set(1L);
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    private TaskService newService() {
        return new TaskService(jdbc, s3, probe, quotaService, queuePublisher, taskRequeueSupport, transactionTemplate,
                1_000_000L, 7200L, "test-bucket");
    }

    /** 最小合法 WAV 头（RIFF....WAVEfmt，16 字节，MediaMagic 只认文件头）。 */
    private MockMultipartFile wav() {
        return new MockMultipartFile("file", "a.wav", "audio/wav",
                "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
    }

    /** 事务闭包直接执行；INSERT..RETURNING 冲突返回 null（幂等命中）→ 回查到 PENDING 旧任务。 */
    private void stubIdempotentHitPending(long existingId) throws Exception {
        when(transactionTemplate.execute(ArgumentMatchers.<TransactionCallback<Long>>any()))
                .thenAnswer(inv -> ((TransactionCallback<Long>) inv.getArgument(0)).doInTransaction(null));
        when(jdbc.query(contains("INSERT INTO task"), ArgumentMatchers.<ResultSetExtractor<Long>>any(),
                eq(1L), anyString(), eq(60L), eq(16L))).thenReturn(null);
        when(jdbc.query(contains("SELECT id, status FROM task"),
                ArgumentMatchers.<ResultSetExtractor<TaskService.ExistingTask>>any(),
                anyString(), eq(1L))).thenReturn(new TaskService.ExistingTask(existingId, "PENDING"));
        when(probe.durationSeconds(any())).thenReturn(60.0);
    }

    @Test
    void idempotentHitPendingRequeuesT450() throws Exception {
        stubIdempotentHitPending(9L);

        TaskCreatedResponse resp = newService().createUpload(wav());

        assertEquals(9L, resp.taskId());
        assertEquals("PENDING", resp.status(), "幂等命中返回库里真实状态");
        assertTrue(resp.reused(), "幂等命中 reused=true");
        verify(taskRequeueSupport).publishPending(eq(9L), ArgumentMatchers.contains("重传命中 PENDING"));
        verify(quotaService, never()).consume(ArgumentMatchers.anyLong(), ArgumentMatchers.anyInt());
    }

    @Test
    void idempotentHitPendingWithRedisDownThrows503T450() throws Exception {
        stubIdempotentHitPending(9L);
        doThrow(new ApiException(503, "QUEUE_UNAVAILABLE", "队列暂不可用，请稍后重试"))
                .when(taskRequeueSupport).publishPending(eq(9L), anyString());

        ApiException ex = assertThrows(ApiException.class, () -> newService().createUpload(wav()));

        assertEquals(503, ex.getStatus(), "重投失败必须 503，不得假成功（T4.50）");
        assertEquals("QUEUE_UNAVAILABLE", ex.getCode());
    }
}
