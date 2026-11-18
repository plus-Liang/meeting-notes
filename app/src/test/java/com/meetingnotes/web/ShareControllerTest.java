package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.task.ShareService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T4.28：匿名视图 404 口径（无效/撤销/过期统一 SHARE_NOT_FOUND）+ 12 键固定 + filter 豁免面收窄。 */
@ExtendWith(MockitoExtension.class)
class ShareControllerTest {

    @Mock
    private ShareService shareService;
    @Mock
    private JdbcTemplate jdbc;

    private ShareController newController() {
        return new ShareController(shareService, jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private void stubViewRow(long taskId, String token) {
        // view() 按token 的 SHA256 查询——stub 参数必须是 hash 而非明文（K-46 口径）
        when(jdbc.query(contains("FROM share_link s"), ArgumentMatchers.<RowMapper<ShareController.SharedRow>>any(),
                eq(com.meetingnotes.common.ShareTokens.sha256Hex(token))))
                .thenReturn(List.of(new ShareController.SharedRow(taskId,
                        "s3://meetingnotes/tasks/" + taskId + "/lead.mp3", 90L,
                        java.time.OffsetDateTime.now(), "会议讨论了材料整理", "[\"材料整理\"]", "[]", null,
                        "由张三负责整理材料，下周三截止。", null, "CLOUD", "meeting")));
    }

    @Test
    void viewForgedTokenReturns404ShareNotFound() {
        when(shareService.existsActiveByHash(any())).thenReturn(false);

        ApiException e = assertThrows(ApiException.class, () -> newController().view("forged-token"));
        assertEquals(404, e.getStatus());
        assertEquals("SHARE_NOT_FOUND", e.getCode());
    }

    @Test
    void viewRevokedOrExpiredReturnsSame404NoReasonLeak() {
        // 撤销/过期 → existsActiveByHash false → 与伪造同一 404 文案（防探测）
        when(shareService.existsActiveByHash(any())).thenReturn(false);
        ApiException e = assertThrows(ApiException.class, () -> newController().view("revoked-token"));
        assertEquals("SHARE_NOT_FOUND", e.getCode());
        assertTrue(e.getMessage().contains("无效或已过期"));
    }

    @Test
    void viewReturnsExactlyTwelveKeys() throws Exception {
        when(shareService.existsActiveByHash(com.meetingnotes.common.ShareTokens.sha256Hex("deadbeefhash"))).thenReturn(true);
        stubViewRow(7L, "deadbeefhash");

        ShareController.SharedTaskView view = newController().view("deadbeefhash");

        // 12 键（record 组件名即 JSON 键，snake_case）
        List<String> keys = List.of(java.util.Arrays.stream(ShareController.SharedTaskView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toArray(String[]::new));
        assertEquals(List.of("task_id", "original_name", "duration_sec", "created_at", "summary",
                "keywords", "keypoints", "todos", "transcript", "transcript_punctuated", "ai_tier", "template"), keys);
        assertEquals(7L, view.task_id());
        assertEquals("lead.mp3", view.original_name());
        assertEquals("CLOUD", view.ai_tier());
        assertEquals("meeting", view.template());
    }

    /* ===== ApiTokenFilter 豁免面（K-46：只放行 /api/v1/share/ 一条前缀） ===== */

    @Test
    void filterExemptsOnlySharePrefix() {
        com.meetingnotes.web.ApiTokenFilter filter =
                new com.meetingnotes.web.ApiTokenFilter("t", jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        HttpServletRequest shareReq = mock(HttpServletRequest.class);
        when(shareReq.getRequestURI()).thenReturn("/api/v1/share/abc-token");
        assertTrue(filter.shouldNotFilter(shareReq), "匿名只读分享前缀豁免");

        HttpServletRequest tasksReq = mock(HttpServletRequest.class);
        when(tasksReq.getRequestURI()).thenReturn("/api/v1/tasks");
        assertFalse(filter.shouldNotFilter(tasksReq), "列表仍需认证");

        HttpServletRequest shareTaskReq = mock(HttpServletRequest.class);
        when(shareTaskReq.getRequestURI()).thenReturn("/api/v1/tasks/7/share");
        assertFalse(filter.shouldNotFilter(shareTaskReq), "创建/撤销分享仍需认证");

        HttpServletRequest otherReq = mock(HttpServletRequest.class);
        when(otherReq.getRequestURI()).thenReturn("/share.html");
        assertTrue(filter.shouldNotFilter(otherReq), "静态页本就豁免");
    }
}
