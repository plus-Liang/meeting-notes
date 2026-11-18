package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * T4.35b：音频流签名与 Range 纯逻辑单测（卡判据）——
 * 签名矩阵（正确/改 taskId/改 exp/过期/空 sig/长度异常 → 拒绝）；Range 解析矩阵
 * （bytes=0-9 / bytes=10- / bytes=-10 / 越界 → 416 / 无 Range）；签发拒绝（PASTE/非 DONE/不存在）。
 */
@ExtendWith(MockitoExtension.class)
class AudioStreamServiceTest {

    private static final String TOKEN = "test-token-123";

    @Mock
    private JdbcTemplate jdbc;

    private AudioStreamService newService() {
        return new AudioStreamService(jdbc, TOKEN, 600L);
    }

    private void stubTask(long taskId, String status, String sourceType, String sourceUri) {
        when(jdbc.query(contains("FROM task WHERE id = ? AND user_id = ?"),
                ArgumentMatchers.<ResultSetExtractor<Map<String, Object>>>any(), eq(taskId), eq(1L)))
                .thenReturn(sourceUri == null
                        ? Map.of("status", status, "source_type", sourceType, "source_uri", "")
                        : Map.of("status", status, "source_type", sourceType, "source_uri", sourceUri));
    }

    /* ---- 签发 ---- */

    @Test
    void issueReturnsSignedUrlBoundToTaskAndExp() {
        stubTask(543L, "DONE", "UPLOAD", "s3://meetingnotes/tasks/543/a.wav");
        AudioStreamService.AudioTokenResponse t = newService().issue(543L, 1L);
        assertNotNull(t.url());
        assertTrue(t.url().startsWith("/api/v1/tasks/543/audio?u=1&exp="),
                "T4.37a：url 绑定 taskId 且带 u=userId：" + t.url());
        assertFalse(t.url().contains(TOKEN), "URL 里绝不出现 API_TOKEN");
        String sig = t.url().substring(t.url().indexOf("sig=") + 4);
        assertEquals(64, sig.length(), "HMAC-SHA256 hex 64 位");
        String exp = t.url().split("exp=")[1].split("&")[0];
        assertTrue(newService().verify(543L, 1L, exp, sig), "刚签发的签名必须验过");
        assertNotNull(t.expiresAt());
    }

    @Test
    void issueRejectsUnknownTaskWith404() {
        when(jdbc.query(contains("FROM task WHERE id = ? AND user_id = ?"),
                ArgumentMatchers.<ResultSetExtractor<Map<String, Object>>>any(), eq(999L), eq(1L)))
                .thenReturn(null);
        ApiException e = assertThrows(ApiException.class, () -> newService().issue(999L, 1L));
        assertEquals(404, e.getStatus());
        assertEquals("TASK_NOT_FOUND", e.getCode());
    }

    @Test
    void issueRejectsPasteAndNonDoneAndBlankUriWith400() {
        stubTask(536L, "DONE", "PASTE", "s3://meetingnotes/tasks/536/paste.txt");
        ApiException paste = assertThrows(ApiException.class, () -> newService().issue(536L, 1L));
        assertEquals(400, paste.getStatus());
        assertEquals("INVALID_PARAM", paste.getCode());

        stubTask(60L, "RUNNING", "UPLOAD", "s3://meetingnotes/tasks/60/a.wav");
        ApiException running = assertThrows(ApiException.class, () -> newService().issue(60L, 1L));
        assertEquals(400, running.getStatus());

        stubTask(61L, "DONE", "UPLOAD", null);
        ApiException blank = assertThrows(ApiException.class, () -> newService().issue(61L, 1L));
        assertEquals(400, blank.getStatus());
    }

    /* ---- 验签矩阵（卡判据：正确/改 taskId/改 exp/改 u/过期/空 sig/长度异常 → 拒绝） ---- */

    @Test
    void signatureMatrix() {
        AudioStreamService svc = newService();
        long exp = java.time.Instant.now().getEpochSecond() + 300;
        String sig = svc.sign(543L, exp, 1L);
        assertTrue(svc.verify(543L, 1L, Long.toString(exp), sig), "正确签名通过");

        assertFalse(svc.verify(544L, 1L, Long.toString(exp), sig), "改 taskId → 拒绝（绑定 taskId）");
        assertFalse(svc.verify(543L, 2L, Long.toString(exp), sig), "改 userId → 拒绝（T4.37a 绑定 u）");
        assertFalse(svc.verify(543L, 1L, Long.toString(exp + 1), sig), "改 exp → 拒绝（绑定 exp）");
        assertFalse(svc.verify(543L, 1L, Long.toString(exp - 3600),
                svc.sign(543L, exp - 3600, 1L)), "过去时间（过期）→ 拒绝");
        assertFalse(svc.verify(543L, 1L, Long.toString(exp), ""), "空 sig → 拒绝");
        assertFalse(svc.verify(543L, 1L, Long.toString(exp), null), "null sig → 拒绝");
        assertFalse(svc.verify(543L, 1L, "", sig), "空 exp → 拒绝");
        assertFalse(svc.verify(543L, 1L, "abc", sig), "非数字 exp → 拒绝");
        assertFalse(svc.verify(543L, 1L, Long.toString(exp), sig.substring(0, 63)), "长度异常（63 位）→ 拒绝");
        assertFalse(svc.verify(543L, 1L, Long.toString(exp), sig + "0"), "长度异常（65 位）→ 拒绝");
        assertFalse(svc.verify(543L, 1L, Long.toString(exp), sig.toUpperCase()), "大写 hex → 拒绝（形状不符）");
    }

    /* ---- Range 解析矩阵（卡判据） ---- */

    private AudioStreamService.RangeSpec parse(String header) {
        return newService().parseRange(header, 10000L);
    }

    @Test
    void rangeMatrix() {
        assertNull(parse(null), "无 Range → null（200 全量）");
        assertNull(parse(""), "空 Range → null");

        AudioStreamService.RangeSpec closed = parse("bytes=0-9");
        assertEquals(0, closed.start());
        assertEquals(9, closed.last());
        assertEquals(10, closed.length());

        AudioStreamService.RangeSpec openEnd = parse("bytes=10-");
        assertEquals(10, openEnd.start());
        assertEquals(9999, openEnd.last());

        AudioStreamService.RangeSpec suffix = parse("bytes=-10");
        assertEquals(9990, suffix.start());
        assertEquals(9999, suffix.last());

        AudioStreamService.RangeSpec clamped = parse("bytes=9990-20000");
        assertEquals(9990, clamped.start());
        assertEquals(9999, clamped.last(), "b 超文件尾收在 size-1（HTTP 语义）");
    }

    @Test
    void illegalOrOutOfRangeRangesThrow416() {
        for (String bad : new String[]{
                "bytes=10000-10010",   // 起点越界
                "bytes=10000-",        // 起点越界（开尾）
                "bytes=10-9",          // 乱序 a>b
                "bytes=-0",            // suffix n=0
                "bytes=abc",           // 非数字
                "bytes=1-2,5-6",       // 多段不支持
                "items=0-9",           // 非 bytes= 前缀
                "bytes=-abc",          // suffix 非数字
                "bytes=1.5-2"}) {      // 小数
            ApiException e = assertThrows(ApiException.class, () -> parse(bad),
                    "非法 Range 必须按 416 拒：" + bad);
            assertEquals(416, e.getStatus(), bad);
            assertEquals("RANGE_NOT_SATISFIABLE", e.getCode(), bad);
        }
        assertThrows(ApiException.class, () -> new AudioStreamService(jdbc, TOKEN, 600L)
                .parseRange("bytes=0-9", 0L), "空文件无字节可给 → 416");
    }

    @Test
    void differentTasksAndUsersProduceDifferentSignatures() {
        AudioStreamService svc = newService();
        long exp = java.time.Instant.now().getEpochSecond() + 300;
        assertNotEquals(svc.sign(1L, exp, 1L), svc.sign(2L, exp, 1L), "签名必须绑定 taskId");
        assertNotEquals(svc.sign(1L, exp, 1L), svc.sign(1L, exp, 2L), "签名必须绑定 userId（T4.37a）");
    }

    /* ---- Content-Type 映射 ---- */

    @Test
    void contentTypeFollowsObjectExtension() {
        assertEquals("audio/wav", AudioStreamService.contentTypeFor("s3://b/tasks/1/a.wav"));
        assertEquals("audio/mpeg", AudioStreamService.contentTypeFor("s3://b/tasks/1/a.mp3"));
        assertEquals("audio/mp4", AudioStreamService.contentTypeFor("s3://b/tasks/1/a.m4a"));
        assertEquals("audio/ogg", AudioStreamService.contentTypeFor("s3://b/tasks/1/a.opus"));
        assertEquals("audio/webm", AudioStreamService.contentTypeFor("s3://b/tasks/1/a.webm"));
        assertEquals("application/octet-stream", AudioStreamService.contentTypeFor("s3://b/tasks/1/a.txt"));
        assertEquals("application/octet-stream", AudioStreamService.contentTypeFor("s3://b/tasks/1/noext"));
    }
}
