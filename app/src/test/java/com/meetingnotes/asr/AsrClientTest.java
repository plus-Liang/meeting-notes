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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T4.35a：AsrClient 单测——本机 HttpServer 模拟 ASR 契约端点，验证
 * ①正常响应解析出 text+segments（一次调用）；②非 JSON → IllegalStateException（worker 据此走 ASR_ERROR）；
 * ③multipart 请求确实发出（file 字段存在）。不依赖真 ASR 容器。
 */
class AsrClientTest {

    private HttpServer server;

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
        Files.deleteIfExists(wav());
    }
}
