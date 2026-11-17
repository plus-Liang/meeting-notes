package com.meetingnotes.fetch;

import com.meetingnotes.common.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.32b：LinkPreviewService 单测——内网 URL 走 UrlGuard 400（卡判据）；本机 loopback
 * 临时服务（allow-hosts 放行 127.0.0.1）验 kind 分类（direct/page/unsupported）、og 解析、
 * 相对路径 image 补全、重定向逐跳校验、解析失败 502。
 */
class LinkPreviewServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpServer start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static String base(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    private static void bytes(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            ex.getResponseHeaders().set("Content-Type", contentType);
        }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private LinkPreviewService service() {
        UrlGuard guard = new UrlGuard("127.0.0.1");
        return new LinkPreviewService(guard, 5, 2000, 3000, 2097152, "");
    }

    /** 卡判据：preview 的内网 URL 与 /tasks/link 走同一道 UrlGuard → 400 URL_BLOCKED。 */
    @Test
    void internalUrlBlockedByUrlGuard() {
        LinkPreviewService svc = new LinkPreviewService(new UrlGuard(""),
                5, 2000, 3000, 2097152, "");
        ApiException e = assertThrows(ApiException.class, () -> svc.preview("http://127.0.0.1/anything"));
        assertEquals(400, e.getStatus());
        assertEquals("URL_BLOCKED", e.getCode());
        ApiException privateIp = assertThrows(ApiException.class,
                () -> svc.preview("http://192.168.1.1/anything"));
        assertEquals("URL_BLOCKED", privateIp.getCode());
    }

    @Test
    void blankUrlInvalidParam() {
        ApiException e = assertThrows(ApiException.class, () -> service().preview("   "));
        assertEquals(400, e.getStatus());
        assertEquals("INVALID_PARAM", e.getCode());
    }

    @Test
    void audioContentTypeClassifiedDirect() throws Exception {
        HttpServer s = start(ex -> bytes(ex, 200, "audio/mpeg", new byte[]{1, 2, 3}));
        LinkPreviewService.LinkPreviewResponse r = service().preview(base(s) + "/a.mp3");
        assertEquals("direct", r.kind());
        assertEquals("audio/mpeg", r.contentType());
        assertEquals(base(s) + "/a.mp3", r.finalUrl());
        assertNull(r.title());
        assertFalse(r.byofAvailable(), "默认 FETCHER_CMD 空 → byof_available=false");
    }

    @Test
    void videoAndOctetStreamClassifiedDirect() throws Exception {
        HttpServer s = start(ex -> bytes(ex, 200, "video/mp4", new byte[]{1}));
        assertEquals("direct", service().preview(base(s) + "/a.mp4").kind());
        HttpServer s2 = start(ex -> bytes(ex, 200, "application/octet-stream", new byte[]{1}));
        assertEquals("direct", service().preview(base(s2) + "/a.bin").kind());
    }

    @Test
    void htmlWithOgClassifiedPageAndFieldsParsed() throws Exception {
        String html = "<html><head><meta property=\"og:title\" content=\"每月复盘会回放\">"
                + "<meta property=\"og:image\" content=\"/img/cover.jpg\">"
                + "<meta property=\"og:video:duration\" content=\"3661\"></head></html>";
        HttpServer s = start(ex -> bytes(ex, 200, "text/html; charset=utf-8",
                html.getBytes(StandardCharsets.UTF_8)));
        LinkPreviewService.LinkPreviewResponse r = service().preview(base(s) + "/video/page.html");
        assertEquals("page", r.kind());
        assertEquals("每月复盘会回放", r.title());
        assertEquals(3661L, r.durationSec());
        assertEquals(base(s) + "/img/cover.jpg", r.image(), "相对路径 og:image 按 final_url 补全");
        assertNull(r.reason());
    }

    @Test
    void htmlWithoutOgFallsBackToTitleTag() throws Exception {
        HttpServer s = start(ex -> bytes(ex, 200, "text/html",
                "<html><head><title>备用标题</title></head></html>".getBytes(StandardCharsets.UTF_8)));
        LinkPreviewService.LinkPreviewResponse r = service().preview(base(s) + "/plain");
        assertEquals("page", r.kind());
        assertEquals("备用标题", r.title());
        assertNull(r.image());
        assertNull(r.durationSec());
    }

    @Test
    void htmlWithoutAnyTitleReturnsNullTitle() throws Exception {
        HttpServer s = start(ex -> bytes(ex, 200, "text/html",
                "<html><head></head><body>x</body></html>".getBytes(StandardCharsets.UTF_8)));
        LinkPreviewService.LinkPreviewResponse r = service().preview(base(s) + "/no-title");
        assertEquals("page", r.kind());
        assertNull(r.title());
    }

    @Test
    void otherContentTypeUnsupportedWithReadableReason() throws Exception {
        HttpServer s = start(ex -> bytes(ex, 200, "application/pdf", new byte[]{1, 2}));
        LinkPreviewService.LinkPreviewResponse r = service().preview(base(s) + "/doc.pdf");
        assertEquals("unsupported", r.kind());
        assertTrue(r.reason().contains("application/pdf"), "reason 可读且带 Content-Type：" + r.reason());
    }

    @Test
    void redirectIntoInternalIpBlocked() throws Exception {
        HttpServer s = start(ex -> {
            ex.getResponseHeaders().set("Location", "http://internal.test/x.wav");
            bytes(ex, 302, null, new byte[0]);
        });
        UrlGuard guard = new UrlGuard(host -> {
            if (host.equals("internal.test")) {
                return List.of(InetAddress.getByName("10.1.1.1"));
            }
            throw new UnknownHostException(host);
        }, List.of("127.0.0.1"));
        LinkPreviewService svc = new LinkPreviewService(guard, 5, 2000, 3000, 2097152, "");
        ApiException e = assertThrows(ApiException.class, () -> svc.preview(base(s) + "/start"));
        assertEquals(400, e.getStatus());
        assertEquals("URL_BLOCKED", e.getCode());
    }

    @Test
    void unknownDomainMapsToFetchFailed() {
        LinkPreviewService svc = new LinkPreviewService(new UrlGuard(""),
                5, 2000, 3000, 2097152, "");
        ApiException e = assertThrows(ApiException.class, () -> svc.preview("http://no-such-domain.invalid/x"));
        assertEquals(502, e.getStatus());
        assertEquals("FETCH_FAILED", e.getCode());
    }

    @Test
    void byofAvailableReflectsConfiguredCmd() throws Exception {
        HttpServer s = start(ex -> bytes(ex, 200, "text/html",
                "<title>t</title>".getBytes(StandardCharsets.UTF_8)));
        LinkPreviewService configured = new LinkPreviewService(new UrlGuard("127.0.0.1"),
                5, 2000, 3000, 2097152, "my-downloader --out {out} {url}");
        assertTrue(configured.preview(base(s) + "/p").byofAvailable());
    }

    private static void assertFalse(boolean cond, String msg) {
        org.junit.jupiter.api.Assertions.assertFalse(cond, msg);
    }
}
