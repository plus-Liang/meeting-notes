package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.80/P-29 前半：RateLimitInterceptor 语义（fixed window）——
 * ①登录桶第 11 次 429 且带 Retry-After 属性；②窗口滚动后恢复；③不同 IP 互不影响；
 * ④任务桶按用户隔离、未设置身份放行；⑤enabled=false 全放行；⑥非目标路径永不计数/拒绝。
 * 全部用 {@link MutableClock} 时间旅行，不 Thread.sleep（卡面 A2 硬要求）。
 */
class RateLimitInterceptorTest {

    /** 可手推的测试时钟：卡面要求 Clock 注入做时间旅行。 */
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-11-24T03:00:00Z");

        void advanceMillis(long ms) { instant = instant.plusMillis(ms); }

        @Override
        public Instant instant() { return instant; }

        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(ZoneId zone) { return this; }
    }

    private final MutableClock clock = new MutableClock();
    private final RateLimitInterceptor interceptor =
            new RateLimitInterceptor(true, 60, 10, 5, 30, clock);

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    /** 单次尝试：返回 HTTP 语义状态码（放行 200 / 被限流 429），并把请求对象记到 out[0] 供断言属性。 */
    private int attempt(MockHttpServletRequest[] out, String method, String uri, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(remoteAddr);
        out[0] = request;
        try {
            boolean pass = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
            return pass ? 200 : 500;
        } catch (ApiException e) {
            return e.getStatus();
        }
    }

    /** ① 登录桶：第 1~10 次放行，第 11 次 429 RATE_LIMITED，且请求属性 rateLimitRetryAfterSec ≥1。 */
    @Test
    void loginBucketRejects11thAttemptWithRetryAfterAttribute() {
        MockHttpServletRequest[] req = new MockHttpServletRequest[1];
        for (int i = 0; i < 10; i++) {
            assertEquals(200, attempt(req, "POST", "/api/v1/auth/login", "10.0.0.1"), "第 " + (i + 1) + " 次应放行");
        }
        MockHttpServletRequest rejected = postLogin("10.0.0.1");
        ApiException e = assertThrows(ApiException.class,
                () -> interceptor.preHandle(rejected, new MockHttpServletResponse(), new Object()),
                "第 11 次必须被限流");
        assertEquals(429, e.getStatus());
        assertEquals("RATE_LIMITED", e.getCode());
        Object retryAfter = rejected.getAttribute(RateLimitInterceptor.RETRY_AFTER_ATTR);
        assertNotNull(retryAfter, "超限前必须写入 rateLimitRetryAfterSec 属性");
        assertTrue(Integer.parseInt(retryAfter.toString()) >= 1, "剩余秒数至少 1，实际 " + retryAfter);
        assertEquals("60", retryAfter.toString(), "窗口 60s 同刻打满 → 剩余整 60s（向上取整）");
    }

    /** ② 窗口滚动后恢复：clock 推进 windowSec+1 → 又放行 10 次（fixed window 重置）。 */
    @Test
    void windowRolloverRestoresLimit() {
        for (int i = 0; i < 10; i++) {
            assertEquals(200, attempt(new MockHttpServletRequest[1], "POST", "/api/v1/auth/login", "10.0.0.1"));
        }
        assertThrows(ApiException.class,
                () -> interceptor.preHandle(postLogin("10.0.0.1"), new MockHttpServletResponse(), new Object()));
        clock.advanceMillis(61 * 1000L); // 窗口 60s，推进 61s → 必然滚入新窗口
        for (int i = 0; i < 10; i++) {
            assertEquals(200, attempt(new MockHttpServletRequest[1], "POST", "/api/v1/auth/login", "10.0.0.1"),
                    "滚动后第 " + (i + 1) + " 次应放行");
        }
        assertThrows(ApiException.class,
                () -> interceptor.preHandle(postLogin("10.0.0.1"), new MockHttpServletResponse(), new Object()),
                "新窗口第 11 次再次限流（计数确实重置而非放行一切）");
    }

    /** ③ 不同 IP 互不影响：A 打满不影响 B。 */
    @Test
    void differentIpsAreIndependent() {
        for (int i = 0; i < 10; i++) {
            assertEquals(200, attempt(new MockHttpServletRequest[1], "POST", "/api/v1/auth/login", "10.0.0.1"));
        }
        assertThrows(ApiException.class,
                () -> interceptor.preHandle(postLogin("10.0.0.1"), new MockHttpServletResponse(), new Object()));
        assertEquals(200, attempt(new MockHttpServletRequest[1], "POST", "/api/v1/auth/login", "10.0.0.2"),
                "另一个 IP 必须不受影响");
    }

    /** ④ 任务桶按用户：user 1 打满不影响 user 2；UserContext 未设置时放行（不因身份缺失误伤）。 */
    @Test
    void tasksBucketIsPerUserAndPassesWhenIdentityMissing() {
        UserContext.set(1);
        for (int i = 0; i < 30; i++) {
            assertEquals(200, attempt(new MockHttpServletRequest[1], "POST", "/api/v1/tasks", "10.0.0.9"));
        }
        ApiException e = assertThrows(ApiException.class,
                () -> interceptor.preHandle(postTasks(), new MockHttpServletResponse(), new Object()));
        assertEquals(429, e.getStatus());
        UserContext.set(2);
        assertEquals(200, attempt(new MockHttpServletRequest[1], "POST", "/api/v1/tasks", "10.0.0.9"),
                "user 2 必须不受 user 1 影响");
        UserContext.clear();
        assertEquals(200, attempt(new MockHttpServletRequest[1], "POST", "/api/v1/tasks", "10.0.0.9"),
                "身份缺失（未认证链路）必须放行");
    }

    /** ⑤ enabled=false 时全放行：20 次登录无 429（止血开关）。 */
    @Test
    void disabledInterceptorPassesEverything() {
        RateLimitInterceptor disabled = new RateLimitInterceptor(false, 60, 10, 5, 30, clock);
        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest request = postLogin("10.0.0.1");
            assertTrue(disabled.preHandle(request, new MockHttpServletResponse(), new Object()),
                    "enabled=false 时第 " + (i + 1) + " 次也必须放行");
        }
    }

    /** ⑥ 非目标路径/方法永不计数、永不拒绝：GET /api/v1/tasks、/actuator/health、OPTIONS 预检、其它 POST。 */
    @Test
    void nonTargetPathsAndMethodsAreNeverCountedOrRejected() {
        for (int i = 0; i < 40; i++) {
            assertEquals(200, attempt(new MockHttpServletRequest[1], "GET", "/api/v1/tasks", "10.0.0.1"),
                    "GET 不在限流范围");
            assertEquals(200, attempt(new MockHttpServletRequest[1], "GET", "/actuator/health", "10.0.0.1"));
            assertEquals(200, attempt(new MockHttpServletRequest[1], "OPTIONS", "/api/v1/auth/login", "10.0.0.1"),
                    "OPTIONS 预检放行");
            assertEquals(200, attempt(new MockHttpServletRequest[1], "POST", "/api/v1/version", "10.0.0.1"),
                    "非目标 POST 放行");
        }
        // 关键：上面 160 次未计数 → 登录桶仍然干净（10 次放行 + 第 11 次才 429）
        for (int i = 0; i < 10; i++) {
            assertEquals(200, attempt(new MockHttpServletRequest[1], "POST", "/api/v1/auth/login", "10.0.0.1"));
        }
        assertThrows(ApiException.class,
                () -> interceptor.preHandle(postLogin("10.0.0.1"), new MockHttpServletResponse(), new Object()));
    }

    /** ⑦ 过期窗口清理（防无界）：塞满超阈值条目后仍能正常计数，过期条目被顺手清掉。 */
    @Test
    void expiredWindowsAreCleanedWhenMapOverflows() {
        for (int i = 0; i < RateLimitInterceptor.CLEANUP_THRESHOLD + 5; i++) {
            String ip = "10.1." + (i / 250) + "." + (i % 250);
            assertEquals(200, attempt(new MockHttpServletRequest[1], "POST", "/api/v1/auth/register", ip),
                    "注册桶第 " + i + " 个独立 IP 应放行");
        }
        clock.advanceMillis(61 * 1000L); // 全部过期
        assertEquals(200, attempt(new MockHttpServletRequest[1], "POST", "/api/v1/auth/register", "10.9.9.9"),
                "清理后新身份照常放行（size>10000 触发的遍历清理不炸、不误伤）");
    }

    private MockHttpServletRequest postLogin(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(ip);
        return request;
    }

    private MockHttpServletRequest postTasks() {
        return new MockHttpServletRequest("POST", "/api/v1/tasks");
    }
}
