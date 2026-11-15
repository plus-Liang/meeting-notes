package com.meetingnotes.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

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
            "error_code", "summary", "keywords", "ai_tier", "todos", "template");

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
        assertEquals(DETAIL_KEYS, keys, "详情键集 = 原 11 键 + template（不多不少、键序不变）");
        assertEquals(textA.trim(), detail.get("transcript").asText(), "PASTE transcript 与输入一致（trim）");
        assertFalse(detail.get("summary").asText().isBlank(), "summary 非空");
        assertTrue(detail.get("keywords").isArray(), "keywords 是数组");
        assertTrue(detail.get("todos").isArray(), "todos 是数组");
        assertTrue(List.of("CLOUD", "LOCAL", "EXTRACTIVE").contains(detail.get("ai_tier").asText()),
                "ai_tier 三选一：" + detail.get("ai_tier"));
        assertEquals("meeting", detail.get("template").asText(), "未指定模板默认 meeting");
        assertTrue(detail.get("error_code").isNull());
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
    void c_exportHasThreeSectionsInFixedOrder() throws Exception {
        HttpResponse<String> resp = send("GET", "/api/v1/tasks/" + taskId + "/export?format=md", null, null);
        assertEquals(200, resp.statusCode(), "导出：" + abbreviate(resp.body()));
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").startsWith("text/markdown"),
                "Content-Type 必须是 text/markdown");
        String body = resp.body();
        int summary = body.indexOf("## 摘要");
        int keywords = body.indexOf("## 关键词");
        int todos = body.indexOf("## 待办");
        assertTrue(summary >= 0 && keywords > summary && todos > keywords,
                "三节固定顺序 摘要→关键词→待办，实际：" + abbreviate(body));
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
}
