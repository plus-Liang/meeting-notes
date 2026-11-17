package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.TaskCreatedResponse;
import com.meetingnotes.fetch.MediaFetcher;
import com.meetingnotes.upload.AudioDurationProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.32a：链接导入管线单测——fetch（stub）→ magic → 时长 → 幂等（ON CONFLICT，冲突不耗配额）→
 * 配额 → MinIO → 入队，与上传管线同序（卡：复用现有上传管线）；source_type='LINK'；
 * 安全违规/非媒体/空 url 在管线早期短路且无副作用。
 */
@ExtendWith(MockitoExtension.class)
class LinkTaskServiceTest {

    private static final byte[] WAV = ByteBuffer.allocate(44)
            .put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F')
            .putInt(36).put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E')
            .array();

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private S3Client s3;
    @Mock
    private AudioDurationProbe probe;
    @Mock
    private QuotaService quotaService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private MediaFetcher fetcher;
    @Mock
    private com.meetingnotes.fetch.ProcessMediaFetcher byofFetcher;
    @Mock
    private ListOperations<String, String> listOps;

    private LinkTaskService newService() {
        return new LinkTaskService(jdbc, s3, probe, quotaService, redis, transactionTemplate, fetcher,
                byofFetcher, 3600L, "meetingnotes", "meetingnotes:task-queue");
    }

    @SuppressWarnings("unchecked")
    private void txExecutesCallback() {
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<Long>) inv.getArgument(0)).doInTransaction(null));
    }

    /** stub 抓取器：把指定字节写到目标临时文件（模拟成功下载）。 */
    private void fetcherWrites(byte[] data) {
        doAnswer(inv -> {
            Files.write((Path) inv.getArgument(1), data);
            return new MediaFetcher.FetchResult("audio/wav", data.length);
        }).when(fetcher).fetch(anyString(), any(Path.class));
    }

    @SuppressWarnings("unchecked")
    private void stubInsertInserts(long newId) {
        when(jdbc.query(contains("INSERT INTO task"), ArgumentMatchers.<ResultSetExtractor<Long>>any(),
                eq(1L), anyString(), anyLong(), anyLong())).thenReturn(newId);
    }

    @SuppressWarnings("unchecked")
    private void stubConflict() {
        when(jdbc.query(contains("INSERT INTO task"), ArgumentMatchers.<ResultSetExtractor<Long>>any(),
                eq(1L), anyString(), anyLong(), anyLong())).thenReturn(null);
    }

    @SuppressWarnings("unchecked")
    private void stubExisting(LinkTaskService.ExistingTask existing) {
        when(jdbc.query(contains("SELECT id, status FROM task"),
                ArgumentMatchers.<ResultSetExtractor<LinkTaskService.ExistingTask>>any(),
                any(), any())).thenReturn(existing);
    }

    @Test
    void linkCreatesTaskStoresObjectAndEnqueues() throws Exception {
        fetcherWrites(WAV);
        txExecutesCallback();
        stubInsertInserts(9L);
        when(probe.durationSeconds(any(Path.class))).thenReturn(2.0);
        when(redis.opsForList()).thenReturn(listOps);

        TaskCreatedResponse resp = newService().createLink("http://example.com/a/sample.wav", false);

        assertEquals(9L, resp.taskId());
        assertEquals("PENDING", resp.status());
        assertEquals(2L, resp.durationSec());
        assertEquals(WAV.length, resp.sizeBytes());
        assertEquals(false, resp.reused());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), ArgumentMatchers.<ResultSetExtractor<Long>>any(),
                eq(1L), anyString(), anyLong(), anyLong());
        assertTrue(sql.getValue().contains("'LINK'"), "source_type 用卡面逐字的 LINK（V1 无 CHECK 约束）");
        assertTrue(sql.getValue().contains("ON CONFLICT (user_id, content_hash) DO NOTHING"), "幂等靠唯一约束裁决");
        verify(quotaService).consume(1L, 1);
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        assertTrue(put.getValue().key().endsWith("/sample.wav"), "对象 key 取 URL 文件名：" + put.getValue().key());
        verify(jdbc).update(contains("source_uri"), contains("s3://meetingnotes/tasks/9/sample.wav"), eq(9L));
        verify(listOps).leftPush("meetingnotes:task-queue", "9");
    }

    @Test
    void blankOrNullUrlRejectedBeforeAnySideEffect() {
        LinkTaskService service = newService();
        ApiException blank = assertThrows(ApiException.class, () -> service.createLink("   ", false));
        assertEquals(400, blank.getStatus());
        assertEquals("INVALID_PARAM", blank.getCode());
        ApiException nullUrl = assertThrows(ApiException.class, () -> service.createLink(null, false));
        assertEquals(400, nullUrl.getStatus());
        verify(fetcher, never()).fetch(anyString(), any(Path.class));
        verify(jdbc, never()).query(anyString(), ArgumentMatchers.<ResultSetExtractor<Object>>any());
    }

    @Test
    void fetcherSecurityRejectionPropagatesWithoutSideEffects() {
        doAnswer(inv -> {
            throw new ApiException(400, "URL_BLOCKED", "目标地址指向被保护网段");
        }).when(fetcher).fetch(anyString(), any(Path.class));
        ApiException e = assertThrows(ApiException.class, () -> newService().createLink("http://127.0.0.1/x.wav", false));
        assertEquals(400, e.getStatus());
        assertEquals("URL_BLOCKED", e.getCode());
        verify(jdbc, never()).query(anyString(), ArgumentMatchers.<ResultSetExtractor<Object>>any());
        verify(quotaService, never()).consume(anyLong(), anyInt());
    }

    @Test
    void nonMediaBytesRejectedAsInvalidMedia() {
        fetcherWrites("plain text, not media".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ApiException e = assertThrows(ApiException.class, () -> newService().createLink("http://example.com/a/x.bin", false));
        assertEquals(400, e.getStatus());
        assertEquals("INVALID_MEDIA", e.getCode());
        verify(quotaService, never()).consume(anyLong(), anyInt());
    }

    @Test
    void overlongDurationRejected() {
        fetcherWrites(WAV);
        when(probe.durationSeconds(any(Path.class))).thenReturn(7200.0);
        ApiException e = assertThrows(ApiException.class, () -> newService().createLink("http://example.com/a/x.wav", false));
        assertEquals(400, e.getStatus());
        assertEquals("DURATION_EXCEEDED", e.getCode());
        verify(quotaService, never()).consume(anyLong(), anyInt());
    }

    @Test
    void idempotentHitReturnsExistingStatusAndConsumesNoQuota() {
        fetcherWrites(WAV);
        txExecutesCallback();
        stubConflict();
        stubExisting(new LinkTaskService.ExistingTask(9L, "DONE"));
        when(probe.durationSeconds(any(Path.class))).thenReturn(2.0);

        TaskCreatedResponse resp = newService().createLink("http://example.com/a/sample.wav", false);

        assertEquals(9L, resp.taskId());
        assertEquals("DONE", resp.status());
        assertTrue(resp.reused());
        verify(quotaService, never()).consume(anyLong(), anyInt());
        verify(redis, never()).opsForList();
    }

    /** T4.32b：use_byof=true 且未配置 FETCHER_CMD → 400 FETCHER_UNAVAILABLE（新码，码表只增）。 */
    @Test
    void byofRequestedButUnconfiguredRejectedWithFetcherUnavailable() {
        when(byofFetcher.isConfigured()).thenReturn(false);
        ApiException e = assertThrows(ApiException.class,
                () -> newService().createLink("http://example.com/some/page", true));
        assertEquals(400, e.getStatus());
        assertEquals("FETCHER_UNAVAILABLE", e.getCode());
        verify(fetcher, never()).fetch(anyString(), any(Path.class));
        verify(byofFetcher, never()).fetch(anyString(), any(Path.class));
        verify(quotaService, never()).consume(anyLong(), anyInt());
    }

    /** T4.32b：use_byof=true 时走 BYOF 抓取器（不是直链下载器），抓到的内容进同一条建任务管线。 */
    @Test
    void byofRequestedRoutesToByofFetcherAndCreatesTask() throws Exception {
        when(byofFetcher.isConfigured()).thenReturn(true);
        doAnswer(inv -> {
            Files.write((Path) inv.getArgument(1), WAV);
            return new MediaFetcher.FetchResult("application/octet-stream", WAV.length);
        }).when(byofFetcher).fetch(anyString(), any(Path.class));
        txExecutesCallback();
        stubInsertInserts(9L);
        when(probe.durationSeconds(any(Path.class))).thenReturn(2.0);
        when(redis.opsForList()).thenReturn(listOps);

        TaskCreatedResponse resp = newService().createLink("http://example.com/some/page", true);

        assertEquals(9L, resp.taskId());
        verify(byofFetcher).fetch(anyString(), any(Path.class));
        verify(fetcher, never()).fetch(anyString(), any(Path.class));
    }
}
