package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.TaskCreatedResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.7 批量提交：0 文件 / 超上限 → 400 INVALID_PARAM；逐项独立（失败隔离、逐项映射）；
 * 时长汇总跳过无时长项（PASTE/字幕）；预估耗时纯函数；配置无死配置（上限来自构造参数）。
 * 不起新 Spring 上下文（K-42：避免抢真实队列），依赖全 Mockito。
 */
@ExtendWith(MockitoExtension.class)
class BatchTaskServiceTest {

    @Mock
    private TaskService taskService;
    @Mock
    private TextTaskService textTaskService;

    private BatchTaskService newService(int maxFiles) {
        return new BatchTaskService(taskService, textTaskService, maxFiles, 0.15);
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("files", name, "application/octet-stream", content);
    }

    private static TaskCreatedResponse created(long taskId, Long durationSec, boolean reused) {
        return new TaskCreatedResponse(taskId, reused ? "DONE" : "PENDING",
                "ab".repeat(32), durationSec, 1L, reused);
    }

    @Test
    void zeroFilesRejectedWith400InvalidParam() throws Exception {
        BatchTaskService service = newService(20);
        ApiException nullCase = assertThrows(ApiException.class, () -> service.createBatch(null, null));
        ApiException emptyCase = assertThrows(ApiException.class,
                () -> service.createBatch(Collections.emptyList(), null));
        assertEquals(400, nullCase.getStatus());
        assertEquals("INVALID_PARAM", nullCase.getCode());
        assertEquals(400, emptyCase.getStatus());
        assertEquals("INVALID_PARAM", emptyCase.getCode());
        verify(taskService, never()).createUpload(any(), any());
        verify(textTaskService, never()).createSubtitle(any(), any());
    }

    @Test
    void overLimitRejectedWith400AndNothingSubmitted() throws Exception {
        BatchTaskService service = newService(20);
        List<MultipartFile> tooMany = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            tooMany.add(file("f" + i + ".wav", new byte[]{'x'}));
        }
        ApiException over = assertThrows(ApiException.class, () -> service.createBatch(tooMany, null));
        assertEquals(400, over.getStatus());
        assertEquals("INVALID_PARAM", over.getCode());
        assertTrue(over.getMessage().contains("20"), "错误信息写明上限：" + over.getMessage());
        verify(taskService, never()).createUpload(any(), any());

        // 配置不是死的：同一个服务实例按构造参数收紧到 3，4 个文件同样拒绝
        BatchTaskService tight = newService(3);
        ApiException tightOver = assertThrows(ApiException.class,
                () -> tight.createBatch(tooMany.subList(0, 4), null));
        assertEquals(400, tightOver.getStatus());
    }

    @Test
    void mixedResultsAreIsolatedAndMappedPerItemOrdered() throws Exception {
        MockMultipartFile ok = file("a.wav", new byte[]{'R', 'I', 'F', 'F'});
        MockMultipartFile bad = file("bad.mp3", "plain text".getBytes());
        MockMultipartFile reused = file("b.wav", new byte[]{'R', 'I', 'F', 'F'});
        when(taskService.createUpload(eq(ok), any())).thenReturn(created(11L, 60L, false));
        when(taskService.createUpload(eq(reused), any())).thenReturn(created(12L, 30L, true));
        when(taskService.createUpload(eq(bad), any()))
                .thenThrow(new ApiException(400, "INVALID_MEDIA", "文件头无法识别为受支持的音/视频格式"));

        BatchTaskService.BatchResult result = newService(20).createBatch(List.of(ok, bad, reused), null);

        assertEquals(3, result.items().size());
        BatchTaskService.BatchItem first = result.items().get(0);
        assertEquals("a.wav", first.fileName());
        assertEquals(11L, first.taskId());
        assertEquals("PENDING", first.status());
        assertEquals(false, first.reused());
        BatchTaskService.BatchItem failed = result.items().get(1);
        assertEquals("bad.mp3", failed.fileName());
        assertEquals("INVALID_MEDIA", failed.code());
        assertEquals("文件头无法识别为受支持的音/视频格式", failed.message());
        BatchTaskService.BatchItem third = result.items().get(2);
        assertEquals(12L, third.taskId());
        assertEquals(Boolean.TRUE, third.reused());

        BatchTaskService.BatchSummary summary = result.summary();
        // accepted 含 reused（卡片口径）：2 = 1 新受理 + 1 复用
        assertEquals(2, summary.accepted());
        assertEquals(1, summary.reused());
        assertEquals(1, summary.failed());
        assertEquals(90L, summary.totalDurationSec());
        assertEquals(Math.round(90 * 0.15 + 2 * 2.0), summary.estimatedSec());
    }

    @Test
    void subtitleRoutedToTextPathAndNullDurationSkipped() throws Exception {
        MockMultipartFile subtitle = file("a.srt", "1\n00:00:00,000 --> 00:00:01,000\nhi".getBytes());
        when(textTaskService.createSubtitle(eq(subtitle), any()))
                .thenReturn(created(21L, null, false));

        BatchTaskService.BatchResult result = newService(20).createBatch(List.of(subtitle), "meeting");

        assertEquals(21L, result.items().get(0).taskId());
        verify(taskService, never()).createUpload(any(), any());
        assertEquals(1, result.summary().accepted());
        assertEquals(0L, result.summary().totalDurationSec(), "无时长项（PASTE/字幕）跳过汇总");
        assertEquals(Math.round(0 * 0.15 + 1 * 2.0), result.summary().estimatedSec());
    }

    @Test
    void estimateSecIsPureAndRoundingIsConsistent() {
        assertEquals(0, BatchTaskService.estimateSec(0, 0, 0.15));
        assertEquals(110, BatchTaskService.estimateSec(600, 10, 0.15));
        assertEquals(20, BatchTaskService.estimateSec(0, 10, 0.15));
        assertEquals(0, BatchTaskService.estimateSec(100, 0, 0.0));
    }
}
