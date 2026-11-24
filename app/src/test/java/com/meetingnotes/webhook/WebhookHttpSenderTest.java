package com.meetingnotes.webhook;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.fetch.UrlGuard;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.83/P-33：WebhookHttpSender 的 SSRF 防护语义（零真实网络）：
 * ①回环目标拒（400 URL_BLOCKED）；②链路本地/云元数据拒；③allow-private=true 放行（显式开关）；
 * ④send 在 blocked 时抛 ApiException 且一个字节都没出网卡（回环计数服务 hits=0）。
 * UrlGuard 用真实现（SYSTEM_RESOLVER 对 127.0.0.1 / 169.254.169.254 本地解析，不查 DNS）。
 */
class WebhookHttpSenderTest {

    private WebhookHttpSender newSender(boolean allowPrivate) {
        return new WebhookHttpSender(new UrlGuard(UrlGuard.SYSTEM_RESOLVER, List.of()),
                allowPrivate, "t483-secret", 10);
    }

    /** ① 回环目标：400 URL_BLOCKED（回环是私有网段的最常见形态）。 */
    @Test
    void loopbackTargetRejected() {
        ApiException e = assertThrows(ApiException.class,
                () -> newSender(false).verifyTarget("http://127.0.0.1:8080/x"));
        assertEquals(400, e.getStatus());
        assertEquals("URL_BLOCKED", e.getCode());
    }

    /** ② 链路本地 / 云元数据地址：400 URL_BLOCKED（169.254.169.254 是云厂商元数据端点）。 */
    @Test
    void linkLocalMetadataRejected() {
        ApiException e = assertThrows(ApiException.class,
                () -> newSender(false).verifyTarget("http://169.254.169.254/latest/meta-data/"));
        assertEquals(400, e.getStatus());
        assertEquals("URL_BLOCKED", e.getCode());
    }

    /** ③ allow-private=true：同一 URL 不抛（显式开关生效——内网接收方的合法出口，风险自担）。 */
    @Test
    void allowPrivateBypassesGuard() {
        assertDoesNotThrow(() -> newSender(true).verifyTarget("http://127.0.0.1:8080/x"));
        assertDoesNotThrow(() -> newSender(true).verifyTarget("http://169.254.169.254/latest/meta-data/"));
    }

    /** ④ send 在 blocked 时抛 ApiException 且没有任何请求发出：回环计数服务 hits=0。 */
    @Test
    void sendBlockedThrowsWithoutTouchingNetwork() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/hook", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            ApiException e = assertThrows(ApiException.class,
                    () -> newSender(false).send(url, "task.done", "d-1",
                            "{\"event\":\"task.done\"}".getBytes(StandardCharsets.UTF_8)));
            assertEquals("URL_BLOCKED", e.getCode());
            assertEquals(0, hits.get(), "被拒目标必须一个请求都没发出去（校验在发送前）");
        } finally {
            server.stop(0);
        }
    }
}
