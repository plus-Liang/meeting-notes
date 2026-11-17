package com.meetingnotes.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * N14 端到端（T4.2）：提交 → 处理 → 查询 → 导出 → 重试 → 错误语义，一条命令跑通。
 *
 * <p>基址：{@code E2E_BASE_URL} → {@code APP_BASE_URL} → 默认 {@code http://localhost:8080}
 * （只拼相对路径，不硬编码端口，D-066）。前置探活：栈不健康必须 **fail**，
 * 不许 skip 假装通过（假绿灯红线）；纯单元跑法 {@code scripts/test.ps1 -UnitOnly} 已排除本类。
 *
 * <p>场景 A 每次运行用 UUID 唯一文本新建 **1 条 PASTE 任务**（0 分钟配额）；B/C/D/E 复用 A 的任务
 * 或零任务（400/415/skip 都不落库）。JUnit 按注解顺序执行（A 必须最先，后续场景依赖其 taskId）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class E2ETest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static final List<String> DETAIL_KEYS = List.of(
            "task_id", "status", "duration_sec", "size_bytes", "created_at", "transcript",
            "error_code", "summary", "keywords", "ai_tier", "todos", "keypoints",
            "transcript_punctuated", "template", "segments");

    private static String textA;
    private static long taskId;

    private static String base() {
        String url = System.getenv("E2E_BASE_URL");
        if (url == null || url.isBlank()) {
            url = System.getenv("APP_BASE_URL");
        }
        return (url == null || url.isBlank()) ? "http://localhost:8080" : url.trim();
    }

    /**
     * API token（T4.6）：E2E_API_TOKEN 环境变量 → API_TOKEN 环境变量 → 仓库根 .env 的 API_TOKEN → 空。
     * test.ps1 全量跑会自动注入；手动跑也可 $env:E2E_API_TOKEN=… 或依赖 .env 兜底。
     */
    private static String token() {
        String t = System.getenv("E2E_API_TOKEN");
        if (t == null || t.isBlank()) {
            t = System.getenv("API_TOKEN");
        }
        if (t == null || t.isBlank()) {
            t = readDotEnvToken();
        }
        return t == null ? "" : t.trim();
    }

    /** 逐行找 .env 的 API_TOKEN（surefire 工作目录 = app/，仓库根在其上一级）。 */
    private static String readDotEnvToken() {
        for (Path candidate : List.of(Path.of(System.getProperty("user.dir"), ".env"),
                Path.of(System.getProperty("user.dir"), "..", ".env"))) {
            try {
                for (String line : Files.readAllLines(candidate)) {
                    if (line.matches("\\s*API_TOKEN\\s*=\\s*.*")) {
                        return line.substring(line.indexOf('=') + 1).trim();
                    }
                }
            } catch (Exception ignored) {
                // 找不到 .env 属正常（CI/外部基址），继续下一个候选
            }
        }
        return null;
    }

    private static HttpResponse<String> send(String method, String path, String body, String contentType)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(30));
        String t = token();
        if (!t.isEmpty()) {
            builder.header("Authorization", "Bearer " + t);
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("DELETE".equals(method)) {
            builder.DELETE(); /* T4.28：撤销分享用真 DELETE（此前 helper 只支持 GET/POST） */
        } else if ("PATCH".equals(method)) {
            builder.method("PATCH", HttpRequest.BodyPublishers.ofString(
                    body == null ? "" : body, StandardCharsets.UTF_8)); /* T4.29：meta 更新 */
            builder.header("Content-Type", contentType == null ? "application/json" : contentType);
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(
                    body == null ? "" : body, StandardCharsets.UTF_8));
            builder.header("Content-Type", contentType == null ? "application/json" : contentType);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 不带 Authorization 的原始请求（401 反例专用）。 */
    private static HttpResponse<String> sendWithoutToken(String method, String path, String body, String contentType)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(30));
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(
                    body == null ? "" : body, StandardCharsets.UTF_8));
            builder.header("Content-Type", contentType == null ? "application/json" : contentType);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String abbreviate(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    @BeforeAll
    static void requireHealthyStack() throws Exception {
        HttpResponse<String> resp = send("GET", "/actuator/health", null, null);
        assertEquals(200, resp.statusCode(),
                "端到端需要基础栈（5 服务 healthy）：先 docker compose up -d 并等 healthy；"
                        + "纯单元跑法 scripts\\test.ps1 -UnitOnly（已排除 E2ETest）。实际 HTTP "
                        + resp.statusCode() + "，响应：" + abbreviate(resp.body()));
    }

    @Test
    @Order(1)
    void a_pasteMainPathReachesDoneWith12KeyContract() throws Exception {
        textA = "端到端验收会议：讨论 N14 自动化测试覆盖范围，结论是五场景全部纳入全量套件，"
                + "音频回归排 T4.4。唯一标记 " + UUID.randomUUID() + "。";
        HttpResponse<String> resp = send("POST", "/api/v1/tasks",
                "{\"text\":" + M.writeValueAsString(textA) + "}", "application/json");
        assertEquals(201, resp.statusCode(), "PASTE 提交：" + abbreviate(resp.body()));
        JsonNode created = M.readTree(resp.body());
        taskId = created.get("task_id").asLong();
        assertTrue(taskId > 0, "task_id 必须为正：" + resp.body());
        assertEquals("PENDING", created.get("status").asText());
        assertFalse(created.get("reused").asBoolean(), "唯一文本不应命中幂等");
        assertEquals(64, created.get("content_hash").asText().length(), "content_hash 64 位");

        JsonNode detail = null;
        String last = "";
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            detail = M.readTree(send("GET", "/api/v1/tasks/" + taskId, null, null).body());
            last = detail.toString();
            String status = detail.get("status").asText();
            if ("DONE".equals(status) || "DEAD".equals(status)) {
                break;
            }
            Thread.sleep(500);
        }
        assertEquals("DONE", detail.get("status").asText(), "60s 未 DONE，最后状态：" + abbreviate(last));
        List<String> keys = new ArrayList<>();
        detail.fieldNames().forEachRemaining(keys::add);
        assertEquals(DETAIL_KEYS, keys, "详情键集 = 原 11 键 + template（第 12）+ keypoints（T4.17 第 13）（不多不少、键序不变）");
        assertEquals(textA.trim(), detail.get("transcript").asText(), "PASTE transcript 与输入一致（trim）");
        assertFalse(detail.get("summary").asText().isBlank(), "summary 非空");
        assertTrue(detail.get("keywords").isArray(), "keywords 是数组");
        assertTrue(detail.get("todos").isArray(), "todos 是数组");
        assertTrue(List.of("CLOUD", "LOCAL", "EXTRACTIVE").contains(detail.get("ai_tier").asText()),
                "ai_tier 三选一：" + detail.get("ai_tier"));
        assertEquals("meeting", detail.get("template").asText(), "未指定模板默认 meeting");
        assertTrue(detail.get("error_code").isNull());
        // T4.21：列表元素键集只增（原 6 键 + keywords/summary_head/template，D-114；详情 14 键不受影响）
        HttpResponse<String> listResp = send("GET", "/api/v1/tasks?limit=1", null, null);
        assertEquals(200, listResp.statusCode());
        JsonNode firstItem = M.readTree(listResp.body()).get("items").get(0);
        List<String> itemKeys = new ArrayList<>();
        firstItem.fieldNames().forEachRemaining(itemKeys::add);
        assertTrue(itemKeys.containsAll(List.of("id", "status", "original_name", "duration_sec",
                        "created_at", "error_code", "keywords", "summary_head", "template")),
                "列表元素键集 = 原 6 键 + T4.21 三键（只增不改），实际：" + itemKeys);
    }

    @Test
    @Order(2)
    void b_idempotentRepostReusesSameTask() throws Exception {
        HttpResponse<String> resp = send("POST", "/api/v1/tasks",
                "{\"text\":" + M.writeValueAsString(textA) + "}", "application/json");
        assertEquals(201, resp.statusCode(), "幂等命中仍返回 201（D-055）");
        JsonNode created = M.readTree(resp.body());
        assertTrue(created.get("reused").asBoolean(), "同文本必须 reused=true");
        assertEquals(taskId, created.get("task_id").asLong(), "返回同一个任务，不新建");
    }

    @Test
    @Order(3)
    void c_exportHonorsIncludeSelection() throws Exception {
        // 默认（不传 include）：摘要→关键词→（要点）→转写，**不含待办**（T4.18 观察期，D-109）
        HttpResponse<String> resp = send("GET", "/api/v1/tasks/" + taskId + "/export?format=md", null, null);
        assertEquals(200, resp.statusCode(), "导出：" + abbreviate(resp.body()));
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").startsWith("text/markdown"),
                "Content-Type 必须是 text/markdown");
        String body = resp.body();
        int summary = body.indexOf("## 摘要");
        int keywords = body.indexOf("## 关键词");
        int transcript = body.indexOf("## 转写");
        assertTrue(summary >= 0 && keywords > summary && transcript > keywords,
                "默认节固定顺序 摘要→关键词→（要点）→转写，实际：" + abbreviate(body));
        assertFalse(body.contains("## 待办"), "默认（不传 include）不得输出待办节：" + abbreviate(body));
        // 全选：5 节齐全（含待办），顺序 摘要→关键词→（要点）→转写→待办
        HttpResponse<String> all = send("GET", "/api/v1/tasks/" + taskId + "/export?format=md"
                + "&include=summary,keywords,keypoints,transcript,todos", null, null);
        assertEquals(200, all.statusCode(), "全选导出：" + abbreviate(all.body()));
        String allBody = all.body();
        int todos = allBody.indexOf("## 待办");
        assertTrue(allBody.contains("## 摘要") && allBody.contains("## 关键词")
                        && allBody.contains("## 转写") && todos > allBody.indexOf("## 转写"),
                "全选必须含摘要/关键词/转写/待办（要点仅在生成时出现），实际：" + abbreviate(allBody));
        // 只选 transcript → 只 1 节
        HttpResponse<String> only = send("GET", "/api/v1/tasks/" + taskId + "/export?format=md&include=transcript",
                null, null);
        assertEquals(200, only.statusCode(), "只选转写：" + abbreviate(only.body()));
        String onlyBody = only.body();
        assertTrue(onlyBody.startsWith("## 转写\n") && !onlyBody.contains("## 摘要") && !onlyBody.contains("## 待办"),
                "include=transcript 只输出转写一节，实际：" + abbreviate(onlyBody));
        // 非法 include → 400 INVALID_PARAM（消息列合法值）
        HttpResponse<String> bad = send("GET", "/api/v1/tasks/" + taskId + "/export?format=md&include=foo",
                null, null);
        assertEquals(400, bad.statusCode(), "非法 include → 400：" + abbreviate(bad.body()));
        assertTrue(M.readTree(bad.body()).get("message").asText().contains("include 只允许"),
                "400 消息要列合法值：" + abbreviate(bad.body()));
        // 空 include → 400
        assertEquals(400, send("GET", "/api/v1/tasks/" + taskId + "/export?format=md&include=", null, null)
                .statusCode(), "空 include → 400");
        assertEquals(400, send("GET", "/api/v1/tasks/" + taskId + "/export?format=pdf", null, null).statusCode(),
                "format=pdf → 400 INVALID_PARAM");
    }

    @Test
    @Order(4)
    void d_batchRetrySkipsNonDeadTask() throws Exception {
        HttpResponse<String> resp = send("POST", "/api/v1/tasks/retry",
                "{\"ids\":[" + taskId + "]}", "application/json");
        assertEquals(200, resp.statusCode(), "重试响应：" + abbreviate(resp.body()));
        JsonNode out = M.readTree(resp.body());
        assertEquals(0, out.get("retried").size(), "DONE 任务不许被重试");
        assertEquals("NOT_DEAD", out.get("skipped").get(0).get("reason").asText());
        assertEquals(taskId, out.get("skipped").get(0).get("id").asLong());
    }

    @Test
    @Order(5)
    void e_errorSemanticsInvalidTemplateAndUnsupportedMediaType() throws Exception {
        HttpResponse<String> badTemplate = send("POST", "/api/v1/tasks",
                "{\"text\":\"模板错误反例 " + UUID.randomUUID() + "\",\"template\":\"xxx\"}", "application/json");
        assertEquals(400, badTemplate.statusCode(), "非法 template → 400");
        JsonNode err = M.readTree(badTemplate.body());
        assertEquals("INVALID_PARAM", err.get("code").asText());
        assertEquals("template 只支持 meeting/course/interview/podcast/shortvideo",
                err.get("message").asText(), "message 逐字固定（D-085），实际：" + err.get("message"));

        HttpResponse<String> plain = send("POST", "/api/v1/tasks", "纯文本提交", "text/plain");
        assertEquals(415, plain.statusCode(), "Content-Type: text/plain → 415 UNSUPPORTED_MEDIA_TYPE");
    }

    /** T4.6：/api/v1/** 认证生效——不带 token 必须 401 + UNAUTHORIZED + WWW-Authenticate（豁免面见 ApiTokenFilterTest）。 */
    @Test
    @Order(6)
    void f_missingTokenIsRejectedWithUnifiedErrorBody() throws Exception {
        HttpResponse<String> resp = sendWithoutToken("POST", "/api/v1/tasks",
                "{\"text\":\"匿名反例 " + UUID.randomUUID() + "\"}", "application/json");
        assertEquals(401, resp.statusCode(), "缺 token 必须 401，实际：" + abbreviate(resp.body()));
        assertEquals("Bearer", resp.headers().firstValue("WWW-Authenticate").orElse(""),
                "401 必须带 WWW-Authenticate: Bearer");
        JsonNode err = M.readTree(resp.body());
        assertEquals("UNAUTHORIZED", err.get("code").asText(), "统一错误码：" + resp.body());
        assertFalse(err.get("message").asText().isBlank(), "message 非空");
        // 401 不得打挂豁免面：actuator 与契约发现端点匿名可达
        assertEquals(200, sendWithoutToken("GET", "/actuator/health", null, null).statusCode());
        assertEquals(200, sendWithoutToken("GET", "/v3/api-docs", null, null).statusCode());
    }

    // ==================== T4.7 批量提交（场景 G） ====================

    private static final String BOUNDARY = "mn-e2e-batch-7f3a9c";

    /** multipart/form-data 请求体：同名 part（files）重复携带多个文件。 */
    private static byte[] multipartBody(List<String> filenames, List<byte[]> contents) {
        return multipartBody(filenames, contents, "files");
    }

    /** multipart/form-data 请求体：part 名可指定（batch 用 files，单文件上传用 file，T4.30b）。 */
    private static byte[] multipartBody(List<String> filenames, List<byte[]> contents, String partName) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < filenames.size(); i++) {
                out.writeBytes(("--" + BOUNDARY + "\r\n"
                        + "Content-Disposition: form-data; name=\"" + partName + "\"; filename=\"" + filenames.get(i) + "\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.writeBytes(contents.get(i));
                out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("构造 multipart 失败", e);
        }
    }

    private static HttpResponse<String> sendBatch(List<String> filenames, List<byte[]> contents) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + "/api/v1/tasks/batch"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(filenames, contents)));
        String t = token();
        if (!t.isEmpty()) {
            builder.header("Authorization", "Bearer " + t);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** T4.30b：multipart 单文件上传（part 名 file + 可选 template，与前端 index.html 录音提交同写法）。 */
    private static HttpResponse<String> sendUpload(String filename, byte[] content, String template) throws Exception {
        byte[] head = (("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] afterFile = "\r\n".getBytes(StandardCharsets.UTF_8); /* multipart：文件内容后必须换行再接下一 part/结尾 */
        byte[] templatePart = (template == null || template.isBlank()) ? new byte[0]
                : ("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"template\"\r\n\r\n"
                + template + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] closing = ("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + "/api/v1/tasks"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        /* 顺序：file 头 → 文件内容 → 换行 → 可选 template part → 结尾 boundary */
                        concat(concat(head, content),
                                concat(afterFile, concat(templatePart, closing)))));
        String t = token();
        if (!t.isEmpty()) {
            builder.header("Authorization", "Bearer " + t);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] all = new byte[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    /**
     * 1s 静音 WAV（44 字节头 + 16KB 零数据，8kHz/16bit/mono），合法头过 MediaMagic；
     * 数据段烧入唯一标记（uuid 各异）保证每次运行都是新内容、不命中旧任务的幂等。
     */
    private static byte[] silenceWav(UUID marker) {
        int sampleRate = 8000;
        int dataLen = sampleRate * 2; // 1s * 16bit * mono
        byte[] wav = new byte[44 + dataLen];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(36 + dataLen);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);
        buf.putShort((short) 1);            // PCM
        buf.putShort((short) 1);            // mono
        buf.putInt(sampleRate);
        buf.putInt(sampleRate * 2);         // byte rate
        buf.putShort((short) 2);            // block align
        buf.putShort((short) 16);           // bits per sample
        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(dataLen);
        long msb = marker.getMostSignificantBits();
        for (int i = 0; i < 8; i++) {
            wav[44 + i] = (byte) (msb >>> (8 * i)); // 静音数据里烧 8 字节标记（听不见，内容各异）
        }
        return wav;
    }

    /** 轮询任务到终态（复用场景 A 的等待口径，60s 上限）。 */
    private JsonNode waitTerminal(long id) throws Exception {
        JsonNode detail = null;
        String last = "";
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            detail = M.readTree(send("GET", "/api/v1/tasks/" + id, null, null).body());
            last = detail.toString();
            String status = detail.get("status").asText();
            if ("DONE".equals(status) || "DEAD".equals(status)) {
                break;
            }
            Thread.sleep(500);
        }
        assertEquals("DONE", detail.get("status").asText(), "60s 未 DONE，最后状态：" + abbreviate(last));
        return detail;
    }

    /**
     * T4.7 场景 G：批量混合提交——2 个 1s 静音 WAV（合法头、内容各异）+ 1 个 .mp3 后缀纯文本
     * （魔数拒绝 → 逐项 400 INVALID_MEDIA，不影响其它）。断言逐项结果、summary、
     * 2 个 accepted 最终 DONE（空 transcript 按 D-048 仍 DONE）、列表可查、
     * 原样重发全量命中幂等（reused=true，不新建任务）。
     */
    @Test
    @Order(7)
    void g_batchMixedFilesIsolateFailureAndReachDone() throws Exception {
        byte[] wav1 = silenceWav(UUID.randomUUID());
        byte[] wav2 = silenceWav(UUID.randomUUID());
        byte[] badMp3 = "this is plain text pretending to be an mp3 — batch e2e".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> resp = sendBatch(
                List.of("batch-e2e-a.wav", "bad-e2e.mp3", "batch-e2e-b.wav"),
                List.of(wav1, badMp3, wav2));
        assertEquals(200, resp.statusCode(), "请求合法（含部分失败）即 200：" + abbreviate(resp.body()));
        JsonNode out = M.readTree(resp.body());
        assertEquals(3, out.get("items").size(), "逐项结果与文件一一对应");
        JsonNode itemA = out.get("items").get(0);
        JsonNode itemBad = out.get("items").get(1);
        JsonNode itemB = out.get("items").get(2);
        assertEquals("batch-e2e-a.wav", itemA.get("file_name").asText());
        assertTrue(itemA.get("task_id").asLong() > 0, "成功项有 task_id：" + itemA);
        assertEquals("PENDING", itemA.get("status").asText());
        assertFalse(itemA.get("reused").asBoolean());
        assertEquals("bad-e2e.mp3", itemBad.get("file_name").asText());
        assertEquals("INVALID_MEDIA", itemBad.get("code").asText(), "坏文件逐项 400 码：" + itemBad);
        assertTrue(itemBad.get("task_id") == null || itemBad.get("task_id").isNull(),
                "失败项没有 task_id");
        assertFalse(itemBad.get("message").asText().isBlank());
        assertEquals("batch-e2e-b.wav", itemB.get("file_name").asText());
        assertTrue(itemB.get("task_id").asLong() > 0, "坏文件不影响其它项：" + itemB);

        JsonNode summary = out.get("summary");
        assertEquals(2, summary.get("accepted").asInt(), "accepted 含 reused（本例 0）：" + summary);
        assertEquals(0, summary.get("reused").asInt());
        assertEquals(1, summary.get("failed").asInt());
        assertEquals(2, summary.get("total_duration_sec").asInt(),
                "两个 1s WAV 时长之和（ffprobe 实测）：" + summary);
        long accepted = summary.get("accepted").asInt();
        assertEquals(Math.round(2 * 0.15 + accepted * 2.0), summary.get("estimated_sec").asInt(),
                "预估公式 round(total*0.15 + accepted*2)（默认系数）：" + summary);

        long idA = itemA.get("task_id").asLong();
        long idB = itemB.get("task_id").asLong();
        JsonNode doneA = waitTerminal(idA);
        JsonNode doneB = waitTerminal(idB);
        assertEquals("", doneA.get("transcript").asText(), "1s 静音 → 空转录仍 DONE（D-048）");
        assertEquals("", doneB.get("transcript").asText());

        // 列表可查到这一批全部 id（验收证据项）
        JsonNode list = M.readTree(send("GET", "/api/v1/tasks?limit=50", null, null).body());
        List<Long> listed = new ArrayList<>();
        list.get("items").forEach(n -> listed.add(n.get("id").asLong()));
        assertTrue(listed.contains(idA) && listed.contains(idB), "列表必须含批量任务 id：" + idA + "/" + idB);

        // 原样重发：同内容幂等 reused=true，返回同一 task_id，不新建
        HttpResponse<String> again = sendBatch(
                List.of("batch-e2e-a.wav", "bad-e2e.mp3", "batch-e2e-b.wav"),
                List.of(wav1, badMp3, wav2));
        assertEquals(200, again.statusCode());
        JsonNode out2 = M.readTree(again.body());
        assertEquals(idA, out2.get("items").get(0).get("task_id").asLong(), "幂等命中同一任务");
        assertEquals(idB, out2.get("items").get(2).get("task_id").asLong());
        assertTrue(out2.get("items").get(0).get("reused").asBoolean(), "同内容 reused=true");
        assertTrue(out2.get("items").get(2).get("reused").asBoolean());
        assertEquals(2, out2.get("summary").get("reused").asInt(), "summary.reused 命中数");
        assertEquals("INVALID_MEDIA", out2.get("items").get(1).get("code").asText(), "坏文件依然逐项拒绝");
    }

    /** T4.7 场景 G（超限）：21 个文件 > BATCH_MAX_FILES(20) → 400 INVALID_PARAM；契约可发现。 */
    @Test
    @Order(8)
    void g_batchOverLimitRejectedAndContractDiscoverable() throws Exception {
        List<String> names = new ArrayList<>();
        List<byte[]> contents = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            names.add(String.format("tiny-%02d.dat", i));
            contents.add(new byte[]{'x'}); // 1 字节：体积远不超限，只超个数
        }
        HttpResponse<String> resp = sendBatch(names, contents);
        assertEquals(400, resp.statusCode(), "21 > 20 必须 400：" + abbreviate(resp.body()));
        JsonNode err = M.readTree(resp.body());
        assertEquals("INVALID_PARAM", err.get("code").asText());
        assertTrue(err.get("message").asText().contains("20"), "错误信息写明上限：" + err.get("message"));

        // 契约可发现（T4.6 springdoc）：/api/v1/tasks/batch 出现在 /v3/api-docs
        JsonNode docs = M.readTree(sendWithoutToken("GET", "/v3/api-docs", null, null).body());
        assertTrue(docs.get("paths").has("/api/v1/tasks/batch"),
                "/api/v1/tasks/batch 必须出现在 /v3/api-docs");
    }

    /** T4.24 场景 H：列表 q 服务端搜索（纯数字兼 id / 转义 / 超长 400）+ counts 键集。零新建任务（复用 A）。 */
    @Test
    @Order(9)
    void h_listSearchQAndCountsContract() throws Exception {
        // 纯数字 q 精确命中 A 场景任务（不依赖 source_uri 内容）
        HttpResponse<String> byId = send("GET", "/api/v1/tasks?q=" + taskId, null, null);
        assertEquals(200, byId.statusCode());
        // 契约（接入指南 §3.3）：文件名模糊 + 纯数字额外精确匹配 id——结果必含目标任务，
        // 且每条满足 id==taskId 或文件名含该数字串；条数不写死（历史文件名可能撞数字，K-80 同源教训）
        JsonNode idList = M.readTree(byId.body());
        boolean containsTarget = false;
        for (JsonNode item : idList.get("items")) {
            long id = item.get("id").asLong();
            String name = item.has("original_name") ? item.get("original_name").asText("") : "";
            assertTrue(id == taskId || name.contains(String.valueOf(taskId)),
                    "每条必须 id 精确或文件名含数字串：" + id + " " + name);
            if (id == taskId) containsTarget = true;
        }
        assertTrue(containsTarget, "q=任务id 的结果必含目标任务：" + abbreviate(byId.body()));

        // 随机 UUID → 0 条（模糊匹配走 ILIKE，不炸）
        HttpResponse<String> none = send("GET", "/api/v1/tasks?q=" + UUID.randomUUID(), null, null);
        assertEquals(200, none.statusCode());
        assertEquals(0, M.readTree(none.body()).get("items").size(), "不存在的关键词必须 0 条");

        // % _ \ 字面化：不抛 500、返回合法 JSON（行数 ≥ 0）
        HttpResponse<String> wildcard = send("GET", "/api/v1/tasks?q=%25_%5C", null, null);
        assertEquals(200, wildcard.statusCode(), "通配符按字面匹配不炸：" + abbreviate(wildcard.body()));
        assertTrue(M.readTree(wildcard.body()).get("items").isArray());

        // q 超过 100 字符 → 400 INVALID_PARAM
        HttpResponse<String> over = send("GET", "/api/v1/tasks?q=" + "x".repeat(101), null, null);
        assertEquals(400, over.statusCode());
        assertEquals("INVALID_PARAM", M.readTree(over.body()).get("code").asText());

        // T4.24b：30 位数字超 long 范围 → 按普通关键词 200 + 合法数组（不再 500）
        HttpResponse<String> huge = send("GET", "/api/v1/tasks?q=" + "9".repeat(30), null, null);
        assertEquals(200, huge.statusCode(), "超 64 位数字必须 200（只走 ILIKE）：" + abbreviate(huge.body()));
        assertTrue(M.readTree(huge.body()).get("items").isArray(), "超长数字 q 响应结构不变");

        // counts：键集固定四键、total ≥ 1（A 场景至少建了 1 条）
        HttpResponse<String> counts = send("GET", "/api/v1/tasks/counts", null, null);
        assertEquals(200, counts.statusCode(), "counts 端点：" + abbreviate(counts.body()));
        JsonNode c = M.readTree(counts.body());
        List<String> keys = new ArrayList<>();
        c.fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("total", "active", "done", "dead"), keys,
                "counts 键集固定 {total, active, done, dead}（只增端点），实际：" + keys);
        assertTrue(c.get("total").asLong() >= 1, "total 至少含 A 场景任务");
        assertTrue(c.get("active").asLong() + c.get("done").asLong() + c.get("dead").asLong()
                <= c.get("total").asLong(), "分类合计不得超过 total");
    }

    /** T4.25 场景 I：GET /stats 键集固定、tasks 与 /tasks/counts 同口径。零新建任务（只读）。 */
    @Test
    @Order(10)
    void i_statsKeysAndConsistencyWithCounts() throws Exception {
        HttpResponse<String> stats = send("GET", "/api/v1/stats", null, null);
        assertEquals(200, stats.statusCode(), "stats 端点：" + abbreviate(stats.body()));
        JsonNode s = M.readTree(stats.body());
        List<String> keys = new ArrayList<>();
        s.fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("tasks", "duration_sec_total", "success_rate", "avg_processing_ratio", "tiers"),
                keys, "stats 键集固定五键（null 值不省略），实际：" + keys);
        List<String> taskKeys = new ArrayList<>();
        s.get("tasks").fieldNames().forEachRemaining(taskKeys::add);
        assertEquals(List.of("total", "active", "done", "dead"), taskKeys, "tasks 子键集固定四键");

        // 与 /tasks/counts 同口径（同一个 GROUP BY 语义）
        JsonNode counts = M.readTree(send("GET", "/api/v1/tasks/counts", null, null).body());
        assertEquals(counts.get("total").asLong(), s.get("tasks").get("total").asLong(), "stats.tasks.total == counts.total");
        assertEquals(counts.get("done").asLong(), s.get("tasks").get("done").asLong(), "stats.tasks.done == counts.done");

        assertTrue(s.get("duration_sec_total").asLong() >= 0);
        // success_rate / avg_processing_ratio 允许 null（空库），非 null 必须在 [0,1] / >0
        if (!s.get("success_rate").isNull()) {
            double sr = s.get("success_rate").asDouble();
            assertTrue(sr >= 0.0 && sr <= 1.0, "success_rate 必须在 [0,1]：" + sr);
        }
        if (!s.get("avg_processing_ratio").isNull()) {
            assertTrue(s.get("avg_processing_ratio").asDouble() > 0.0, "处理倍速必须 >0");
        }
        assertTrue(s.get("tiers").isObject(), "tiers 是对象");
    }

    /** T4.27 场景 J：批量导出 zip——zip 内 md 与单条导出逐字节一致 + 跳过语义 + 400 矩阵。零新建任务（复用 A）。 */
    @Test
    @Order(11)
    void j_zipExportMatchesSingleExportAndSkipsMissing() throws Exception {
        // 单任务 zip（二进制 → ofByteArray；单条 md 仍走 ofString，UTF-8 由 Content-Type 声明）
        java.net.http.HttpRequest zipReq = java.net.http.HttpRequest
                .newBuilder(URI.create(base() + "/api/v1/tasks/export?ids=" + taskId))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token())
                .GET().build();
        HttpResponse<byte[]> zipResp = HTTP.send(zipReq, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, zipResp.statusCode());
        assertTrue(zipResp.headers().firstValue("Content-Type").orElse("").startsWith("application/zip"),
                "Content-Type 必须是 application/zip");

        HttpResponse<String> single = send("GET", "/api/v1/tasks/" + taskId + "/export?format=md", null, null);
        assertEquals(200, single.statusCode());

        List<String> names = new ArrayList<>();
        String mdFromZip = null;
        try (ZipInputStream zin = new ZipInputStream(
                new java.io.ByteArrayInputStream(zipResp.body()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                names.add(entry.getName());
                if (entry.getName().endsWith(".md")) {
                    mdFromZip = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertEquals(1, names.size(), "只请求一个任务 → 仅 1 个条目、无 _errors.txt：" + names);
        assertTrue(names.get(0).startsWith(taskId + "-") && names.get(0).endsWith(".md"),
                "条目名 <id>-<文件名>.md：" + names.get(0));
        assertEquals(single.body(), mdFromZip, "zip 内 md 必须与单条导出逐字节一致");

        // 跳过语义：真实 id + 不存在的 id → _errors.txt
        java.net.http.HttpRequest zipReq2 = java.net.http.HttpRequest
                .newBuilder(URI.create(base() + "/api/v1/tasks/export?ids=" + taskId + ",999999"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token())
                .GET().build();
        HttpResponse<byte[]> zip2 = HTTP.send(zipReq2, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, zip2.statusCode());
        List<String> names2 = new ArrayList<>();
        String errors2 = null;
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(zip2.body()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                names2.add(entry.getName());
                if ("_errors.txt".equals(entry.getName())) {
                    errors2 = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertEquals(2, names2.size(), "成功条 + _errors.txt：" + names2);
        assertTrue(names2.contains("_errors.txt"));
        assertEquals("999999: NOT_FOUND", errors2, "跳过原因逐条写入");

        // 400 矩阵：非数字 / 缺失 / 超 20 / 全部不可导
        assertEquals(400, send("GET", "/api/v1/tasks/export?ids=abc", null, null).statusCode());
        assertEquals(400, send("GET", "/api/v1/tasks/export", null, null).statusCode());
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 21; i++) {
            if (i > 1) many.append(',');
            many.append(i);
        }
        assertEquals(400, send("GET", "/api/v1/tasks/export?ids=" + many, null, null).statusCode(),
                "21 个 id → 400");
        assertEquals(400, send("GET", "/api/v1/tasks/export?ids=999999", null, null).statusCode(),
                "全部不可导 → 400（不是空 zip）");
    }

    /** T4.28 场景 K：只读分享——匿名可读 12 键子集、分享 token 不得越权（列表仍 401）、撤销/伪造 404。零新建任务。 */
    @Test
    @Order(12)
    void k_shareAnonymousReadonlyContract() throws Exception {
        // 创建分享（需 API token；body 空对象即可）
        HttpResponse<String> createResp = send("POST", "/api/v1/tasks/" + taskId + "/share", "{}", "application/json");
        assertEquals(200, createResp.statusCode(), "创建分享：" + abbreviate(createResp.body()));
        JsonNode created = M.readTree(createResp.body());
        List<String> createKeys = new ArrayList<>();
        created.fieldNames().forEachRemaining(createKeys::add);
        assertEquals(List.of("task_id", "token", "url", "expires_at"), createKeys, "创建响应键集固定四键");
        String shareToken = created.get("token").asText();
        assertTrue(created.get("url").asText().startsWith("/share.html?token="), "url 指向只读页");
        assertEquals(43, shareToken.length(), "32B base64url 无填充 = 43 字符");

        // 匿名读（无 Authorization）→ 200，12 键只读子集
        HttpResponse<String> anon = sendWithoutToken("GET", "/api/v1/share/" + shareToken, null, null);
        assertEquals(200, anon.statusCode(), "匿名必须可读：" + abbreviate(anon.body()));
        JsonNode view = M.readTree(anon.body());
        List<String> viewKeys = new ArrayList<>();
        view.fieldNames().forEachRemaining(viewKeys::add);
        assertEquals(List.of("task_id", "original_name", "duration_sec", "created_at", "summary",
                "keywords", "keypoints", "todos", "transcript", "transcript_punctuated", "ai_tier", "template"),
                viewKeys, "匿名视图键集固定 12 键（不多不少）");
        assertEquals(taskId, view.get("task_id").asLong());

        // 分享 token 不是 API token：拿它访问列表 → 401（认证面未放大）
        java.net.http.HttpRequest smuggledReq = java.net.http.HttpRequest
                .newBuilder(URI.create(base() + "/api/v1/tasks?limit=1"))
                .header("Authorization", "Bearer " + shareToken)
                .GET().build();
        HttpResponse<String> smuggled = HTTP.send(smuggledReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, smuggled.statusCode(), "分享 token 不得当 API token 用");

        // 伪造 token → 404 SHARE_NOT_FOUND（不区分原因，防探测）
        HttpResponse<String> forged = sendWithoutToken("GET", "/api/v1/share/totally-forged-token", null, null);
        assertEquals(404, forged.statusCode());
        assertEquals("SHARE_NOT_FOUND", M.readTree(forged.body()).get("code").asText());

        // 撤销 → 匿名再读 → 404 SHARE_NOT_FOUND
        HttpResponse<String> revoke = send("DELETE", "/api/v1/tasks/" + taskId + "/share", null, null);
        assertEquals(200, revoke.statusCode(), "撤销：" + abbreviate(revoke.body()));
        assertTrue(M.readTree(revoke.body()).get("revoked").asInt() >= 1, "至少撤销本场景创建的那条");
        HttpResponse<String> afterRevoke = sendWithoutToken("GET", "/api/v1/share/" + shareToken, null, null);
        assertEquals(404, afterRevoke.statusCode());
        assertEquals("SHARE_NOT_FOUND", M.readTree(afterRevoke.body()).get("code").asText());
    }

    /** T4.29 场景 L：标签/收藏——PATCH 校验矩阵、列表只增字段与筛选、GET meta 读回。零新建任务（复用 A）。 */
    @Test
    @Order(13)
    void l_labelsFavoriteMetaContract() throws Exception {
        // PATCH 正常：trim + 去重保序 + favorite
        HttpResponse<String> patch = send("PATCH", "/api/v1/tasks/" + taskId + "/meta",
                "{\"labels\":[\" e2e \",\"重点\",\"e2e\"],\"favorite\":true}", "application/json");
        assertEquals(200, patch.statusCode(), "PATCH meta：" + abbreviate(patch.body()));
        JsonNode meta = M.readTree(patch.body());
        assertEquals("[\"e2e\",\"重点\"]", meta.get("labels").toString(), "trim+去重保序");
        assertTrue(meta.get("favorite").asBoolean());
        List<String> metaKeys = new ArrayList<>();
        meta.fieldNames().forEachRemaining(metaKeys::add);
        assertEquals(List.of("task_id", "labels", "favorite"), metaKeys);

        // GET meta 读回
        HttpResponse<String> read = send("GET", "/api/v1/tasks/" + taskId + "/meta", null, null);
        assertEquals(200, read.statusCode());
        List<String> readKeys = new ArrayList<>();
        M.readTree(read.body()).fieldNames().forEachRemaining(readKeys::add);
        assertEquals(List.of("task_id", "labels", "favorite"), readKeys);

        // 列表只增字段 + 筛选
        HttpResponse<String> favList = send("GET", "/api/v1/tasks?favorite=true&limit=50", null, null);
        assertEquals(200, favList.statusCode());
        JsonNode favItems = M.readTree(favList.body()).get("items");
        boolean found = false;
        for (JsonNode item : favItems) {
            assertTrue(item.has("labels") && item.has("favorite"), "列表键集只增 labels/favorite");
            if (item.get("id").asLong() == taskId) {
                found = true;
                assertTrue(item.get("favorite").asBoolean());
                assertEquals("[\"e2e\",\"重点\"]", item.get("labels").toString());
            }
        }
        assertTrue(found, "favorite=true 必须命中打星任务");

        HttpResponse<String> labelList = send("GET", "/api/v1/tasks?label=e2e&limit=50", null, null);
        assertTrue(M.readTree(labelList.body()).toString().contains("\"id\":" + taskId),
                "label 筛选命中");
        HttpResponse<String> notFav = send("GET", "/api/v1/tasks?favorite=false&limit=50", null, null);
        assertFalse(M.readTree(notFav.body()).toString().contains("\"id\":" + taskId),
                "favorite=false 不得命中已打星任务");
        assertEquals(400, send("GET", "/api/v1/tasks?favorite=yes", null, null).statusCode());

        // 校验矩阵：全 400 INVALID_PARAM
        String[][] bad = {
                {"{\"labels\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\",\"k\"]}", "11 个标签"},
                {"{\"labels\":[\"" + "字".repeat(21) + "\"]}", "21 字"},
                {"{\"labels\":\"课程\"}", "非数组"},
                {"{\"labels\":[42]}", "非字符串元素"},
                {"{\"favorite\":\"yes\"}", "favorite 非 bool"},
                {"{}", "两键全缺"},
        };
        for (String[] b : bad) {
            HttpResponse<String> resp = send("PATCH", "/api/v1/tasks/" + taskId + "/meta", b[0], "application/json");
            assertEquals(400, resp.statusCode(), b[1] + " 必须 400：" + abbreviate(resp.body()));
            assertEquals("INVALID_PARAM", M.readTree(resp.body()).get("code").asText());
        }
        HttpResponse<String> missing = send("PATCH", "/api/v1/tasks/999999/meta",
                "{\"favorite\":true}", "application/json");
        assertEquals(404, missing.statusCode());
        assertEquals("TASK_NOT_FOUND", M.readTree(missing.body()).get("code").asText());

        // 清理：恢复默认（本场景不留收藏/标签）
        HttpResponse<String> cleanup = send("PATCH", "/api/v1/tasks/" + taskId + "/meta",
                "{\"labels\":[],\"favorite\":false}", "application/json");
        assertEquals(200, cleanup.statusCode());
    }

    /**
     * T4.30b 场景 M：浏览器录音 webm 上传回归（K-82）。MediaRecorder 产出的流式 webm
     * EBML 头部无 duration，ffprobe format=duration 输出 N/A 且 exit 0——修复前
     * AudioDurationProbe 裸 parseDouble 未捕获 → 500；修复后兜底取末音频包
     * pts_time(+duration_time) → 201 → DONE、duration_sec 合理。
     *
     * <p>fixture {@code /media/recording.webm}：验收侧 fake 麦克风真录 8s（recorded.webm，
     * 128,751 B）按字节截断的 1.5s 片段（24,576 B）。不用 {@code ffmpeg -c copy} 裁：实测
     * remux 会把 duration 回填（fixture 失去「头部无 duration」特性，走不到兜底）；字节截断
     * 保留该特性，且容器实测 worker 式解码 exit 0、末包 1.44+0.06=1.50。内容各次运行相同
     * → 重跑命中幂等（reused），仍 201/DONE，断言不受影响。
     */
    @Test
    @Order(14)
    void m_browserRecordingWebmUploadsAndCompletes() throws Exception {
        byte[] webm;
        try (var in = getClass().getResourceAsStream("/media/recording.webm")) {
            assertTrue(in != null, "fixture /media/recording.webm 必须存在于 test resources");
            webm = in.readAllBytes();
        }
        HttpResponse<String> resp = sendUpload("recording.webm", webm, "meeting");
        assertEquals(201, resp.statusCode(), "webm 上传必须 201（修复前 500 INTERNAL_ERROR）："
                + abbreviate(resp.body()));
        JsonNode created = M.readTree(resp.body());
        assertTrue(created.get("task_id").asLong() > 0, "响应含 task_id：" + resp.body());
        JsonNode detail = waitTerminal(created.get("task_id").asLong());
        long dur = detail.get("duration_sec").asLong();
        assertTrue(dur >= 1 && dur <= 3, "兜底时长 ceil(1.50)=2，应在 1–3 秒（不是 0/null/异常）："
                + detail.get("duration_sec"));
        assertTrue(detail.has("transcript") && !detail.get("transcript").isNull(),
                "详情含 transcript 键（内容长短不做断言，1.5s 样本可能识别为空，D-048 仍 DONE）");
        assertEquals("meeting", detail.get("template").asText(), "显式 template=meeting 生效");
    }

    /* ---- T4.35a：真实语音上传 → DONE → 详情 segments 句子级时间戳（非空、有序、末段 end ≤ duration+2） ---- */

    @Test
    @Order(15)
    void n_realVoiceUploadCarriesSentenceSegments() throws Exception {
        byte[] wav;
        try (var in = getClass().getResourceAsStream("/media/t435a-sample.wav")) {
            assertTrue(in != null, "fixture /media/t435a-sample.wav 必须存在于 test resources");
            wav = in.readAllBytes();
        }
        // 内容唯一（尾部追加标记）：不撞 (user_id, content_hash) 幂等，否则会 reused 回旧任务
        // （旧任务建在 T4.35a 之前，segments 恒 null，E2E 会假红——K-64）
        byte[] unique = new byte[wav.length + 16];
        System.arraycopy(wav, 0, unique, 0, wav.length);
        System.arraycopy(UUID.randomUUID().toString().substring(0, 8).getBytes(StandardCharsets.US_ASCII),
                0, unique, wav.length, 8);
        HttpResponse<String> resp = sendUpload("t435a-" + UUID.randomUUID() + ".wav", unique, "meeting");
        assertEquals(201, resp.statusCode(), "真实语音上传：" + abbreviate(resp.body()));
        assertFalse(M.readTree(resp.body()).get("reused").asBoolean(), "唯一内容不命中幂等");
        long segTaskId = M.readTree(resp.body()).get("task_id").asLong();
        JsonNode detail = waitTerminal(segTaskId);

        assertTrue(detail.has("segments") && detail.get("segments").isArray()
                        && !detail.get("segments").isEmpty(),
                "DONE 详情 segments 必须是非空数组：" + abbreviate(String.valueOf(detail.get("segments"))));
        long dur = detail.get("duration_sec").asLong();
        double lastEnd = 0;
        for (JsonNode seg : detail.get("segments")) {
            double start = seg.get("start").asDouble();
            double end = seg.get("end").asDouble();
            assertTrue(start >= 0 && start < end, "每项 0 ≤ start < end：" + seg);
            assertFalse(seg.get("text").asText().isBlank(), "每项 text 非空：" + seg);
            lastEnd = Math.max(lastEnd, end);
        }
        assertTrue(lastEnd <= dur + 2, "末段 end ≤ duration_sec+2（宽松断言，别写死秒数）：lastEnd="
                + lastEnd + " dur=" + dur);
    }

    /* ---- T4.35a：改造前的历史任务 segments==null（迁移不回填），且 500 不出现 ---- */

    @Test
    @Order(16)
    void o_legacyDoneTaskSegmentsIsNullNotArray() throws Exception {
        // K-81 教训：默认列表只回最近 50 条 DONE（全是 500+ 的新任务），拿不到历史任务——
        // 必须用 keyset 游标 before_id=300 去老区间取"改造前"（T4.35a 提交前创建）的 DONE 任务
        HttpResponse<String> listResp = send("GET", "/api/v1/tasks?status=DONE&limit=20&before_id=300", null, null);
        assertEquals(200, listResp.statusCode());
        JsonNode items = M.readTree(listResp.body()).get("items");
        assertTrue(items.size() > 0 && items.get(0).get("id").asLong() < 300,
                "id<300 区间应存在历史 DONE 任务（before_id keyset）");
        long legacyId = items.get(0).get("id").asLong();
        HttpResponse<String> resp = send("GET", "/api/v1/tasks/" + legacyId, null, null);
        assertEquals(200, resp.statusCode(), "旧任务详情可读：");
        JsonNode detail = M.readTree(resp.body());
        assertTrue(detail.has("segments"), "详情含 segments 键（只增）");
        assertTrue(detail.get("segments").isNull(), "改造前的历史任务 segments==null（不是 []，不回填）");
    }
}
