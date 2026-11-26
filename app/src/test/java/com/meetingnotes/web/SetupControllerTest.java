package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.93 C3：SetupController——三个向导端点 + /setup 页面映射（A4 方案①：adminClaimed=true 全 404，
 * 不暴露「已完成」字样；响应体走全站 404 口径由 ApiExceptionHandler 转）。
 * 只 mock AuthService/SetupChecks；页面回源走 classpath:static/setup.html（测试类路径含 main 资源）。
 */
@ExtendWith(MockitoExtension.class)
class SetupControllerTest {

    @Mock
    private AuthService authService;
    @Mock
    private SetupChecks setupChecks;

    private SetupController newController() {
        return new SetupController(authService, setupChecks);
    }

    private static SetupChecks.Check check(String key, boolean ok) {
        return new SetupChecks.Check(key, "标签-" + key, ok, "detail-" + key);
    }

    /* ---- GET /api/v1/setup/status ---- */

    @Test
    void statusReturnsNeedsSetupAndFiveChecksWhenUnclaimed() throws Exception {
        when(authService.adminClaimed()).thenReturn(false);
        when(setupChecks.runAll()).thenReturn(List.of(
                check("postgres", true), check("redis", true), check("minio", true),
                check("asr", true), check("disk", true)));

        Map<String, Object> body = newController().status();

        assertEquals(true, body.get("needs_setup"));
        assertEquals(5, ((List<?>) body.get("checks")).size());
    }

    @Test
    void statusIs404WhenAdminClaimed() {
        when(authService.adminClaimed()).thenReturn(true);
        assertThrows(NoResourceFoundException.class, () -> newController().status(),
                "A4：已初始化实例的向导面必须 404（不是 403，不暴露入口曾存在）");
    }

    /* ---- POST /api/v1/setup/admin ---- */

    @Test
    void createAdminDelegatesWithSetupCodeAndReturnsSession() throws Exception {
        when(authService.adminClaimed()).thenReturn(false);
        when(authService.setupClaimAdmin("boss", "password123", "the-code")).thenReturn(
                new AuthService.AuthSession(1L, "boss", "ADMIN", "tok-1"));

        Map<String, Object> body = newController().createAdmin(
                new SetupController.SetupAdminRequest("boss", "password123", "the-code"));

        assertEquals(1L, body.get("user_id"));
        assertEquals("boss", body.get("username"));
        assertEquals("ADMIN", body.get("role"));
        assertEquals("tok-1", body.get("token"));
        verify(authService).setupClaimAdmin("boss", "password123", "the-code");
    }

    @Test
    void createAdminIs404WhenAdminClaimed() {
        when(authService.adminClaimed()).thenReturn(true);
        assertThrows(NoResourceFoundException.class,
                () -> newController().createAdmin(
                        new SetupController.SetupAdminRequest("boss", "password123", "the-code")));
    }

    @Test
    void createAdminNullBodyPassesNullsToService() throws Exception {
        when(authService.adminClaimed()).thenReturn(false);
        when(authService.setupClaimAdmin(null, null, null))
                .thenThrow(new ApiException(400, "INVALID_PARAM", "用户名必须为 3–32 个字符，仅限字母/数字/下划线/连字符/中文"));

        assertThrows(ApiException.class, () -> newController().createAdmin(null));
    }

    /* ---- GET /api/v1/setup/model ---- */

    @Test
    void modelPassthroughWhenUnclaimed() throws Exception {
        when(authService.adminClaimed()).thenReturn(false);
        when(setupChecks.asrModelStatus()).thenReturn(Map.of("model", "small", "ready", true));

        Map<String, Object> body = newController().model();

        assertEquals("small", body.get("model"));
        assertEquals(true, body.get("ready"));
    }

    @Test
    void modelIs404WhenAdminClaimed() {
        when(authService.adminClaimed()).thenReturn(true);
        assertThrows(NoResourceFoundException.class, () -> newController().model());
    }

    /* ---- A4：/setup 与 /setup.html 页面 ---- */

    @Test
    void pageServesClasspathHtmlWhenUnclaimed() throws Exception {
        when(authService.adminClaimed()).thenReturn(false);

        var response = newController().page();

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().length > 0);
        String html = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(html.startsWith("<!DOCTYPE html>"), "回源必须是完整静态页");
        assertTrue(html.contains("创建管理员"), "页面必须包含创建管理员屏");
    }

    @Test
    void pageIs404WhenAdminClaimed() {
        when(authService.adminClaimed()).thenReturn(true);
        assertThrows(NoResourceFoundException.class, () -> newController().page(),
                "A4：静态页也必须 404（controller 显式映射优先于静态资源 handler）");
    }
}
