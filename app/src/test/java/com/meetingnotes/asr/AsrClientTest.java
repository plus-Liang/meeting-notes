package com.meetingnotes.asr;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T4.35a：AsrClient 单测——本机 HttpServer 模拟 ASR 契约端点，验证
 * ①正常响应解析出 text+segments（一次调用）；②非 JSON → IllegalStateException（worker 据此走 ASR_ERROR）；
 * ③multipart 请求确实发出（file 字段存在）。不依赖真 ASR 容器。
 */
class AsrClientTest {

    private HttpServer server;
    private byte[] lastRequestBody; // T4.99：捕获最近一次 multipart 请求体（断言 language 字段是否携带）

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AsrClient clientReturning(String responseBody) throws IOException {
        server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", ex -> {
            String contentType = ex.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.contains("multipart/form-data")) {
                byte[] err = "expected multipart".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(415, err.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(err);
                }
                return;
            }
            lastRequestBody = ex.getRequestBody().readAllBytes(); // T4.99：捕获请求体供三分支断言
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return new AsrClient(RestClient.builder(), base(), "small");
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Path wav() throws IOException {
        return Files.write(Files.createTempFile("mn-asrclient-", ".wav"), new byte[]{1, 2, 3});
    }

    @Test
    void parsesTextAndSegmentsFromSingleResponse() throws Exception {
        AsrClient client = clientReturning(
                "{\"text\":\"大家好，我们开始。\",\"duration\":9.5,\"cost_sec\":1.2,"
                        + "\"segments\":[{\"start\":0.12,\"end\":3.45,\"text\":\"大家好，我们开始。\"}]}");
        AsrClient.Transcription t = client.transcribe(wav());
        assertEquals("大家好，我们开始。", t.text());
        assertEquals(1, t.segments().size());
        assertEquals(3.45, t.segments().get(0).end(), 1e-9);
        Files.deleteIfExists(wav());
    }

    @Test
    void unparseableBodyThrowsIllegalStateForAsrErrorPath() throws Exception {
        AsrClient client = clientReturning("this is not json");
        Path wav = wav();
        assertThrows(IllegalStateException.class, () -> client.transcribe(wav));
        Files.deleteIfExists(wav);
    }

    @Test
    void legacyThreeKeyResponseStillWorks() throws Exception {
        AsrClient client = clientReturning("{\"text\":\"旧响应\",\"duration\":9.5,\"cost_sec\":1.2}");
        AsrClient.Transcription t = client.transcribe(wav());
        assertEquals("旧响应", t.text());
        assertNull(t.segments(), "老响应无 segments 键 → null（不是 []）");
        assertNull(t.language(), "老响应无 language 键 → null（T4.99 只增键，不报错）");
        Files.deleteIfExists(wav());
    }

    /* ---- T4.99/C3/C9：任务级 language 三分支（multipart 是否携带该字段） ---- */

    private String lastBodyAsText() {
        return lastRequestBody == null ? "" : new String(lastRequestBody, StandardCharsets.UTF_8);
    }

    @Test
    void languageFieldSentWhenProvidedAndEchoedBack() throws Exception {
        AsrClient client = clientReturning(
                "{\"text\":\"hello\",\"duration\":3.0,\"cost_sec\":0.4,\"language\":\"en\"}");
        AsrClient.Transcription t = client.transcribe(wav(), "en");
        String body = lastBodyAsText();
        assertTrue(body.contains("name=\"language\""), "显式 language 必须出现在 multipart 里");
        assertTrue(body.contains("en"), "language 值原样传递");
        assertEquals("en", t.language(), "响应带回的实际语言透传到 Transcription");
        Files.deleteIfExists(wav());
    }

    @Test
    void autoSentVerbatim() throws Exception {
        AsrClient client = clientReturning("{\"text\":\"x\",\"duration\":3.0,\"cost_sec\":0.4}");
        client.transcribe(wav(), "auto");
        String body = lastBodyAsText();
        assertTrue(body.contains("name=\"language\""), "auto 也要显式传给 ASR 服务端");
        assertTrue(body.contains("auto"), "auto 原样传（服务端解释为自动检测）");
        Files.deleteIfExists(wav());
    }

    @Test
    void languageOmittedWhenNull() throws Exception {
        AsrClient client = clientReturning("{\"text\":\"旧客户端\",\"duration\":3.0,\"cost_sec\":0.4}");
        client.transcribe(wav(), null);
        assertFalse(lastBodyAsText().contains("name=\"language\""),
                "null/缺省**不加** language 字段——老客户端行为与今天完全一致");
        Files.deleteIfExists(wav());
    }
}
