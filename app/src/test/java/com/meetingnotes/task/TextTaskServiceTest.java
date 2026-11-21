package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.TaskCreatedResponse;
import com.meetingnotes.upload.SubtitleParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3.7：文本任务提交（PASTE/字幕共用）——幂等顺序与音频路径逐条一致（D-084）：
 * INSERT ON CONFLICT（冲突不耗配额）→ consume(0)（+1 任务/+0 分钟）→ 存对象 → 回填 source_uri → 入队；
 * 命中 DONE reused=true；命中 DEAD 复活重投清死信（D-049）；hash == sha256(最终文本 UTF-8)（裁决 1）。
 */
@ExtendWith(MockitoExtension.class)
class TextTaskServiceTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private S3Client s3;
    @Mock
    private QuotaService quotaService;
    @Mock
    private QueuePublisher queuePublisher;
    @Mock
    private TransactionTemplate transactionTemplate;

    private TextTaskService newService() {
        return new TextTaskService(jdbc, s3, quotaService, queuePublisher, transactionTemplate,
                "meetingnotes");
    }

    @SuppressWarnings("unchecked")
    private void txExecutesCallback() {
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<Long>) inv.getArgument(0)).doInTransaction(null));
    }

    @SuppressWarnings("unchecked")
    private void stubInsertInserts(long newId) {
        when(jdbc.query(contains("INSERT INTO task"), ArgumentMatchers.<ResultSetExtractor<Long>>any(),
                eq(1L), anyString(), anyLong())).thenReturn(newId);
    }

    @SuppressWarnings("unchecked")
    private void stubConflict() {
        when(jdbc.query(contains("INSERT INTO task"), ArgumentMatchers.<ResultSetExtractor<Long>>any(),
                eq(1L), anyString(), anyLong())).thenReturn(null);
    }

    @SuppressWarnings("unchecked")
    private void stubExisting(TextTaskService.ExistingTask existing) {
        when(jdbc.query(contains("SELECT id, status FROM task"),
                ArgumentMatchers.<ResultSetExtractor<TextTaskService.ExistingTask>>any(),
                any(), any())).thenReturn(existing);
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
    void pasteCreatesTaskConsumesZeroMinutesAndStoresTextObject() {
        txExecutesCallback();
        stubInsertInserts(9L);

        TaskCreatedResponse resp = newService().createPaste("  新的一段会议文本  ");

        assertEquals(9L, resp.taskId());
        assertEquals("PENDING", resp.status());
        assertNull(resp.durationSec(), "PASTE 无音频时长（键照常输出为 null）");
        assertEquals(false, resp.reused());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), ArgumentMatchers.<ResultSetExtractor<Long>>any(),
                eq(1L), anyString(), anyLong());
        assertTrue(sql.getValue().contains("'PASTE'"), "source_type 用 schema 预留的 PASTE");
        assertTrue(sql.getValue().contains("ON CONFLICT (user_id, content_hash) DO NOTHING"), "幂等靠唯一约束裁决");
        verify(quotaService).consume(1L, 0);
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        assertTrue(put.getValue().key().endsWith("/paste.txt"), "文本对象 key：" + put.getValue().key());
        verify(jdbc).update(contains("source_uri"), contains("s3://meetingnotes/tasks/9/paste.txt"), eq(9L));
        verify(queuePublisher).publish(9L);
    }

    /** T4.52 B1：Redis 不可用时建文本任务必须 503（不得假成功 201）。 */
    @Test
    void pasteCreateFails503WhenQueueDown() {
        txExecutesCallback();
        stubInsertInserts(9L);
        doThrow(new ApiException(503, "QUEUE_UNAVAILABLE", "队列暂不可用，请稍后重试"))
                .when(queuePublisher).publish(9L);

        ApiException e = assertThrows(ApiException.class, () -> newService().createPaste("一段会议文本"));

        assertEquals(503, e.getStatus());
        assertEquals("QUEUE_UNAVAILABLE", e.getCode());
    }

    /** T4.52 B1：幂等命中 PENDING 必须重新入队（重提即自愈），返回语义 reused=true/PENDING 不变。 */
    @Test
    void pasteIdempotentHitPendingRequeuesT452() {
        txExecutesCallback();
        stubConflict();
        stubExisting(new TextTaskService.ExistingTask(9L, "PENDING"));

        TaskCreatedResponse resp = newService().createPaste("重复的文本");

        assertEquals(9L, resp.taskId());
        assertEquals("PENDING", resp.status());
        assertTrue(resp.reused());
        verify(queuePublisher).publish(9L);
        verify(quotaService, never()).consume(anyLong(), anyInt());
    }

    @Test
    void hashIsSha256OfTrimmedFinalText() throws Exception {
        // 裁决 1：hash = sha256(最终文本 UTF-8)——trim 前后同文本必须同 hash（srt/ass/同文本不建重复任务）
        txExecutesCallback();
        stubInsertInserts(9L);

        TaskCreatedResponse first = newService().createPaste("稳定文本");
        TaskCreatedResponse second = newService().createPaste("  稳定文本  ");

        assertEquals(first.contentHash(), second.contentHash(), "trim 前后同文本同 hash");
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("稳定文本".getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, first.contentHash(), "hash 必须等于 sha256(最终文本 UTF-8)");
    }

    @Test
    void duplicateTextReusesExistingDoneTaskWithoutConsumingQuota() {
        txExecutesCallback();
        stubConflict();
        stubExisting(new TextTaskService.ExistingTask(5L, "DONE"));

        TaskCreatedResponse resp = newService().createPaste("重复文本");

        assertEquals(5L, resp.taskId());
        assertEquals("DONE", resp.status());
        assertTrue(resp.reused());
        verify(quotaService, never()).consume(anyLong(), anyInt());
        verify(queuePublisher, never()).publish(anyLong());
    }

    @Test
    void deadTaskRevivesAndRequeues() {
        // 命中 DEAD → 复活重投 + 清死信（D-049），PASTE 与音频路径同口径
        txExecutesCallback();
        stubConflict();
        stubExisting(new TextTaskService.ExistingTask(6L, "DEAD"));

        TaskCreatedResponse resp = newService().createPaste("复活文本");

        assertEquals("PENDING", resp.status());
        assertTrue(resp.reused());
        verify(jdbc).update(contains("status = 'PENDING'"), eq(6L));
        verify(jdbc).update(contains("DELETE FROM dead_letter"), eq(6L));
        verify(queuePublisher).publish(6L);
    }

    @Test
    void blankOrOverlongTextRejectedWith400() {
        TextTaskService service = newService();
        assertEquals(400, assertThrows(ApiException.class, () -> service.createPaste(null)).getStatus());
        assertEquals(400, assertThrows(ApiException.class, () -> service.createPaste("   ")).getStatus());
        ApiException over = assertThrows(ApiException.class,
                () -> service.createPaste("字".repeat(100_001)));
        assertEquals(400, over.getStatus());
        assertEquals("INVALID_PARAM", over.getCode());
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void subtitleWithNoRecognizableTextRejectedWith400() {
        String emptySrt = "1\n00:00:01,000 --> 00:00:02,000\n\n\n";
        ApiException e = assertThrows(ApiException.class,
                () -> newService().createSubtitle(multipart("x.srt", emptySrt.getBytes(StandardCharsets.UTF_8))));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("没有可识别文本"));
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void subtitleOverFiveMegabytesRejectedWith413BeforeParsing() {
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);
        when(oversized.getSize()).thenReturn(SubtitleParser.MAX_SUBTITLE_BYTES + 1);

        ApiException e = assertThrows(ApiException.class, () -> newService().createSubtitle(oversized));

        assertEquals(413, e.getStatus());
        assertEquals("SIZE_EXCEEDED", e.getCode());
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void srtAndAssWithSameContentShareOneHash() {
        // 裁决 1 推论：同内容存成 srt/ass 不该建两个任务 → hash 都基于解析后文本
        txExecutesCallback();
        stubInsertInserts(9L);

        byte[] srt = "1\n00:00:01,000 --> 00:00:02,000\n会议开始\n".getBytes(StandardCharsets.UTF_8);
        String ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,会议开始\n";
        TaskCreatedResponse fromSrt = newService().createSubtitle(multipart("a.srt", srt));
        TaskCreatedResponse fromAss = newService().createSubtitle(multipart("b.ass", ass.getBytes(StandardCharsets.UTF_8)));

        assertEquals(fromSrt.contentHash(), fromAss.contentHash(), "同内容字幕/文本共享一个任务");
        verify(jdbc, times(2)).query(contains("INSERT INTO task"),
                ArgumentMatchers.<ResultSetExtractor<Long>>any(), eq(1L), anyString(), anyLong());
    }

    private static MultipartFile multipart(String name, byte[] bytes) {
        return new MultipartFile() {
            @Override
            public String getName() {
                return "file";
            }

            @Override
            public String getOriginalFilename() {
                return name;
            }

            @Override
            public String getContentType() {
                return "application/octet-stream";
            }

            @Override
            public boolean isEmpty() {
                return bytes.length == 0;
            }

            @Override
            public long getSize() {
                return bytes.length;
            }

            @Override
            public byte[] getBytes() {
                return bytes;
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public void transferTo(File dest) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /* ---- T3.9 模板：提交事务内写 template 行；非法值 400；字幕不做文件名推荐 ---- */

    @Test
    void pasteWritesTemplateRowInsideSubmitTransaction() {
        txExecutesCallback();
        stubInsertInserts(9L);

        TaskCreatedResponse resp = newService().createPaste("一段会议文本", "  COURSE ");

        assertEquals(9L, resp.taskId());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(9L), eq("course"));
        assertTrue(sql.getValue().contains("INSERT INTO task_result"),
                "提交事务内先写模板行（零迁移）：" + sql.getValue());
        assertTrue(sql.getValue().contains("template"), "INSERT 必须带 template 列");
    }

    @Test
    void pasteWithInvalidTemplateRejected400WithoutAnySideEffect() {
        ApiException e = assertThrows(ApiException.class,
                () -> newService().createPaste("一段会议文本", "weekly"));

        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());
        assertEquals("template 只支持 meeting/course/interview/podcast/shortvideo", e.getMessage(),
                "不静默回落（US-12 case 4），message 逐字固定（D-085）");
        verify(transactionTemplate, never()).execute(any());
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void subtitleTakesExplicitTemplateButNeverRecommendsFromFilename() {
        // 字幕不做自动推荐：文件名含"会议"关键字但不采纳；显式 MEETING → meeting
        txExecutesCallback();
        stubInsertInserts(12L);
        byte[] srt = "1\n00:00:01,000 --> 00:00:02,000\n会议开始\n".getBytes(StandardCharsets.UTF_8);

        TaskCreatedResponse resp = newService().createSubtitle(multipart("会议记录_2026.srt", srt), "MEETING");

        assertEquals(12L, resp.taskId());
        verify(jdbc).update(contains("INSERT INTO task_result"), eq(12L), eq("meeting"));
    }
}
