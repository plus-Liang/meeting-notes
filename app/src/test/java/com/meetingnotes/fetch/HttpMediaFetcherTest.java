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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.32a：HttpMediaFetcher 行为单测——本机 com.sun.net.httpserver 起临时服务（loopback，
 * allow-hosts 放行 127.0.0.1 以便打到测试端口）；重定向逐跳校验 / 超上限 / 超时 / 超大小 /
 * 网页拒 / 远端错误码，均按卡判据断言固定错误码。
 */
class HttpMediaFetcherTest {

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

    private static final byte[] WAV = java.nio.ByteBuffer.allocate(44)
            .put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F')
            .putInt(36).put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E')
            .array();

    /** 生产构造（真实 UrlGuard）+ allow-hosts 放行 loopback 测试端口。 */
    private HttpMediaFetcher productionFetcher(int maxRedirects, int readTimeoutMs, long maxBytes) {
        UrlGuard guard = new UrlGuard("127.0.0.1");
        return new HttpMediaFetcher(guard, maxRedirects, 2000, readTimeoutMs, maxBytes);
    }

    @Test
    void streamsBodyToFileAndReportsContentType() throws Exception {
        HttpServer s = start(ex -> bytes(ex, 200, "audio/wav", WAV));
        Path dest = Files.createTempFile("mn-fetch-test-", ".bin");
        HttpMediaFetcher fetcher = productionFetcher(5, 3000, 1024 * 1024);
        MediaFetcher.FetchResult result = fetcher.fetch(base(s) + "/a/sample.wav", dest);
        assertEquals("audio/wav", result.contentType());
        assertEquals(WAV.length, result.sizeBytes());
        assertEquals(WAV.length, Files.size(dest));
        Files.deleteIfExists(dest);
    }

    @Test
    void redirectFollowedWhenEachHopAllowed() throws Exception {
        HttpServer s = start(ex -> {
            if (ex.getRequestURI().getPath().equals("/r1")) {
                ex.getResponseHeaders().set("Location", "/final.wav");
                bytes(ex, 302, null, new byte[0]);
            } else {
                bytes(ex, 200, "audio/wav", WAV);
            }
        });
        Path dest = Files.createTempFile("mn-fetch-test-", ".bin");
        MediaFetcher.FetchResult result = productionFetcher(5, 3000, 1024 * 1024)
                .fetch(base(s) + "/r1", dest);
        assertEquals(WAV.length, result.sizeBytes());
        Files.deleteIfExists(dest);
    }

    /** 302 → 内网：重定向**每一跳**都重跑 UrlGuard（不是只验首跳，卡判据）。 */
    @Test
    void redirectIntoInternalIpBlocked() throws Exception {
        HttpServer s = start(ex -> {
            ex.getResponseHeaders().set("Location", "http://internal.test/x.wav");
            bytes(ex, 302, null, new byte[0]);
        });
        UrlGuard guard = new UrlGuard(host -> {
            if (host.equals("internal.test")) {
                return List.of(InetAddress.getByName("10.9.9.9"));
            }
            throw new UnknownHostException(host);
        }, List.of("127.0.0.1"));
        HttpMediaFetcher fetcher = new HttpMediaFetcher(guard, 5, 2000, 3000, 1024 * 1024);
        Path dest = Files.createTempFile("mn-fetch-test-", ".bin");
        ApiException e = assertThrows(ApiException.class, () -> fetcher.fetch(base(s) + "/start", dest));
        assertEquals(400, e.getStatus());
        assertEquals("URL_BLOCKED", e.getCode());
        Files.deleteIfExists(dest);
    }

    @Test
    void tooManyRedirectsBlocked() throws Exception {
        HttpServer s = start(ex -> {
            int hop = Integer.parseInt(ex.getRequestURI().getPath().replace("/hop", ""));
            if (hop < 7) {
                ex.getResponseHeaders().set("Location", "/hop" + (hop + 1));
                bytes(ex, 302, null, new byte[0]);
            } else {
                bytes(ex, 200, "audio/wav", WAV);
            }
        });
        Path dest = Files.createTempFile("mn-fetch-test-", ".bin");
        ApiException e = assertThrows(ApiException.class,
                () -> productionFetcher(5, 3000, 1024 * 1024).fetch(base(s) + "/hop1", dest));
        assertEquals(400, e.getStatus());
        assertEquals("URL_BLOCKED", e.getCode());
        Files.deleteIfExists(dest);
    }

    @Test
    void readTimeoutMapsToFetchFailed() throws Exception {
        HttpServer s = start(ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            bytes(ex, 200, "audio/wav", WAV);
        });
        Path dest = Files.createTempFile("mn-fetch-test-", ".bin");
        ApiException e = assertThrows(ApiException.class,
                () -> productionFetcher(5, 300, 1024 * 1024).fetch(base(s) + "/slow", dest));
        assertEquals(502, e.getStatus());
        assertEquals("FETCH_FAILED", e.getCode());
        assertFalse(Files.exists(dest), "失败后不留半截文件");
    }

    @Test
    void remoteErrorStatusMapsToFetchFailed() throws Exception {
        HttpServer s = start(ex -> bytes(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8)));
        ApiException e = assertThrows(ApiException.class,
                () -> productionFetcher(5, 3000, 1024 * 1024).fetch(base(s) + "/missing", Files.createTempFile("mn-fetch-test-", ".bin")));
        assertEquals(502, e.getStatus());
        assertEquals("FETCH_FAILED", e.getCode());
    }

    @Test
    void htmlContentTypeRejectedAsNotDirectMedia() throws Exception {
        HttpServer s = start(ex -> bytes(ex, 200, "text/html; charset=utf-8",
                "<html><body>page</body></html>".getBytes(StandardCharsets.UTF_8)));
        ApiException e = assertThrows(ApiException.class,
                () -> productionFetcher(5, 3000, 1024 * 1024).fetch(base(s) + "/page", Files.createTempFile("mn-fetch-test-", ".bin")));
        assertEquals(400, e.getStatus());
        assertEquals("NOT_DIRECT_MEDIA", e.getCode());
    }

    @Test
    void oversizeAbortsAtLimitAndLeavesNoPartialFile() throws Exception {
        byte[] big = new byte[1000];
        HttpServer s = start(ex -> bytes(ex, 200, "application/octet-stream", big));
        Path dest = Files.createTempFile("mn-fetch-test-", ".bin");
        ApiException e = assertThrows(ApiException.class,
                () -> productionFetcher(5, 3000, 100).fetch(base(s) + "/big", dest));
        assertEquals(413, e.getStatus());
        assertEquals("SIZE_EXCEEDED", e.getCode());
        assertFalse(Files.exists(dest), "超大小中止后删除半截文件");
    }

    /** 卡禁止：只发匿名 GET，不带 cookie/鉴权头。 */
    @Test
    void sendsNoCookieOrAuthHeaders() throws Exception {
        AtomicReference<String> cookie = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        List<String> methods = new CopyOnWriteArrayList<>();
        HttpServer s = start(ex -> {
            cookie.set(ex.getRequestHeaders().getFirst("Cookie"));
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            methods.add(ex.getRequestMethod());
            bytes(ex, 200, "audio/wav", WAV);
        });
        Path dest = Files.createTempFile("mn-fetch-test-", ".bin");
        productionFetcher(5, 3000, 1024 * 1024).fetch(base(s) + "/headers", dest);
        assertNull(cookie.get());
        assertNull(auth.get());
        assertEquals(List.of("GET"), methods);
        Files.deleteIfExists(dest);
    }
}
