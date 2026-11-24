package com.meetingnotes.web;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.86/P-26：RequestIdFilter 语义——合法头透传 / 缺失生成 32 位 hex / 非法头被替换（不回显）/
 * 响应头与 MDC 一致 / 请求结束 MDC 清空（防线程池复用串号）。Mock 对象直驱，不起 Spring 上下文。
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    /** 运行 filter：chain 内捕获 MDC（requestId 在请求处理期间才有值，filter 返回后已被清理）。 */
    private MockHttpServletResponse run(MockHttpServletRequest request,
                                        java.util.concurrent.atomic.AtomicReference<String> mdcInChain)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response,
                (req, res) -> mdcInChain.set(MDC.get("requestId")));
        return response;
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws ServletException, IOException {
        return run(request, new java.util.concurrent.atomic.AtomicReference<>());
    }

    /** 合法头（8–64 位合法字符）→ 原样透传：响应头与 MDC 同值。 */
    @Test
    void legalHeaderIsPassedThroughAndMatchesMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/version");
        request.addHeader("X-Request-Id", "t486-aaaa1");
        java.util.concurrent.atomic.AtomicReference<String> mdcInChain = new java.util.concurrent.atomic.AtomicReference<>();
        MockHttpServletResponse response = run(request, mdcInChain);
        assertEquals("t486-aaaa1", response.getHeader("X-Request-Id"));
        assertEquals("t486-aaaa1", mdcInChain.get(), "请求处理期间 MDC 与响应头一致");
        assertNull(MDC.get("requestId"), "filter 返回后 MDC 必须已清空");
    }

    /** 缺失 → 服务端生成 32 位小写 hex。 */
    @Test
    void missingHeaderGenerates32Hex() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> mdcInChain = new java.util.concurrent.atomic.AtomicReference<>();
        MockHttpServletResponse response = run(new MockHttpServletRequest("GET", "/api/v1/version"), mdcInChain);
        String rid = response.getHeader("X-Request-Id");
        assertTrue(rid != null && rid.matches("^[0-9a-f]{32}$"), "32 位小写 hex：" + rid);
        assertEquals(rid, mdcInChain.get(), "请求处理期间 MDC 与响应头一致");
        assertNull(MDC.get("requestId"), "filter 返回后 MDC 必须已清空");
    }

    /** 非法头（含空格与感叹号）→ 被替换成 32 位 hex，绝不原样回显。 */
    @Test
    void illegalHeaderIsReplacedNotEchoed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/version");
        request.addHeader("X-Request-Id", "bad id!");
        MockHttpServletResponse response = run(request);
        String rid = response.getHeader("X-Request-Id");
        assertTrue(rid != null && rid.matches("^[0-9a-f]{32}$"), "非法值不得回显：" + rid);
        assertTrue(!"bad id!".equals(rid));
    }

    /** 请求结束后 MDC 清空（Tomcat 线程池复用，漏了就是跨请求串号）。 */
    @Test
    void mdcIsClearedAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/version");
        request.addHeader("X-Request-Id", "t486-aaaa1");
        run(request);
        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("taskId"));
    }

    /** 边界形态：63 位合法字符仍透传（正则上限 64）。 */
    @Test
    void boundaryLengthStillLegal() throws Exception {
        String rid63 = "a".repeat(63);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/version");
        request.addHeader("X-Request-Id", rid63);
        MockHttpServletResponse response = run(request);
        assertEquals(rid63, response.getHeader("X-Request-Id"));
        assertTrue(Pattern.compile("^[A-Za-z0-9._-]{8,64}$").matcher(rid63).matches());
    }
}
