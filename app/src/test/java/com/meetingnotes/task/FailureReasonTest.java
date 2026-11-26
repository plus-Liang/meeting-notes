package com.meetingnotes.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * T4.95/C7：错误码 → 人话映射（FailureReason）逐字契约。
 * 文案是 D-239（页面就是唯一告警渠道）下用户直接阅读的内容，改动必须走卡面；
 * 未知码绝不编造（title = 原始码字面量，hint = 兜底文案）。
 */
class FailureReasonTest {

    @Test
    void storageErrorTextIsVerbatim() {
        var h = FailureReason.of("STORAGE_ERROR");
        assertEquals("文件读取失败", h.title());
        assertEquals("检查对象存储是否可用、文件是否被移动或删除；重新提交即可重试。", h.hint());
    }

    @Test
    void asrErrorTextIsVerbatim() {
        var h = FailureReason.of("ASR_ERROR");
        assertEquals("语音识别失败", h.title());
        assertEquals("检查 ASR 容器是否健康（`/api/v1/admin/storage` 页或 `docker logs`）；换一种音频格式再试。", h.hint());
    }

    @Test
    void workerCrashTextIsVerbatim() {
        var h = FailureReason.of("WORKER_CRASH");
        assertEquals("处理进程异常中断", h.title());
        assertEquals("该任务连续中断已达上限；查看 `docker logs` 中 `taskId` 对应的 ERROR，必要时重启服务。", h.hint());
    }

    @Test
    void queueUnavailableTextIsVerbatim() {
        var h = FailureReason.of("QUEUE_UNAVAILABLE");
        assertEquals("队列不可用", h.title());
        assertEquals("Redis 可能不可用；恢复后点「重试」即可。", h.hint());
    }

    @Test
    void unknownErrorTextIsVerbatim() {
        var h = FailureReason.of("UNKNOWN_ERROR");
        assertEquals("未知错误", h.title());
        assertEquals("把这段错误码复制给开发者，或查看容器日志。", h.hint());
    }

    /** 未知码绝不编造：title = 原始码字面量，hint = 兜底文案非空。 */
    @Test
    void unknownCodeKeepsRawLiteralAsTitle() {
        var h = FailureReason.of("SOME_FUTURE_CODE");
        assertEquals("SOME_FUTURE_CODE", h.title(), "未知码 title 必须原样返回码字面量（不编造）");
        assertNotNull(h.hint());
        assertFalse(h.hint().isBlank(), "未知码 hint 必须给兜底建议");
        assertEquals("把这段错误码复制给开发者，或查看容器日志。", h.hint());
    }

    /** null / 空串不抛异常，回退 UNKNOWN_ERROR。 */
    @Test
    void nullOrBlankCodeFallsBackToUnknownWithoutThrowing() {
        var n = FailureReason.of(null);
        assertEquals("未知错误", n.title());
        var b = FailureReason.of("   ");
        assertEquals("未知错误", b.title());
    }
}
