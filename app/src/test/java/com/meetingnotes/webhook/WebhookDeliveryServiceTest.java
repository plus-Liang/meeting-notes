package com.meetingnotes.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

/**
 * T4.6：Webhook 契约的纯函数面——事件映射、HMAC-SHA256 签名（与接收端 §4.3 伪代码互证）、
 * delivery_id 确定性（重试同键）、退避档位、载荷九键。
 */
class WebhookDeliveryServiceTest {

    @Test
    void eventMappingOnlyAcceptsTerminalStates() {
        assertEquals("task.done", WebhookDeliveryService.eventFor("DONE"));
        assertEquals("task.failed", WebhookDeliveryService.eventFor("DEAD"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> WebhookDeliveryService.eventFor("RUNNING"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> WebhookDeliveryService.eventFor("RETRYING"));
    }

    @Test
    void signatureMatchesReceiverPseudoCode() throws Exception {
        byte[] body = "{\"event\":\"task.done\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sig = WebhookHttpSender.sign("t46-secret", body);
        // 接收端 §4.3 的验证算法（独立实现，互证不是自证）；"sha256=" 前缀在 WebhookHttpSender.send 拼头时加
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("t46-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        assertEquals(HexFormat.of().formatHex(mac.doFinal(body)), sig);
        assertNotEquals(sig, WebhookHttpSender.sign("wrong-secret", body));
        assertNotEquals(sig, WebhookHttpSender.sign("t46-secret", "其它字节".getBytes()));
    }

    @Test
    void deliveryIdIsDeterministicPerRowAndUniqueAcrossRows() {
        UUID a = WebhookDeliveryService.deliveryIdFor(42L);
        assertEquals(a, WebhookDeliveryService.deliveryIdFor(42L), "同一投递行的重试必须同键（接入方去重）");
        assertNotEquals(a, WebhookDeliveryService.deliveryIdFor(43L));
    }

    @Test
    void backoffLadderIsOneTenSixtyThenFinal() {
        assertEquals(1L, WebhookDeliveryService.nextBackoffSec(1, 3));
        assertEquals(10L, WebhookDeliveryService.nextBackoffSec(2, 3));
        assertEquals(60L, WebhookDeliveryService.nextBackoffSec(3, 3));
        assertNull(WebhookDeliveryService.nextBackoffSec(4, 3), "第 4 次失败 = 初始 + 3 次重试耗尽，终局");
    }

    @Test
    void payloadHasNineContractKeysAndResultUrl() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WebhookDeliveryService service = new WebhookDeliveryService(
                org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mapper, "", "s", 3, "http://localhost:8080", true,
                org.mockito.Mockito.mock(WebhookHttpSender.class));
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("status", "DONE");
        row.put("ai_tier", "EXTRACTIVE");
        row.put("summary", "摘要");
        row.put("todos", "[{\"action\":\"a\"},{\"action\":\"b\"}]");
        String json = mapper.writeValueAsString(service.payloadFromRow("d-1", "task.done", 7L, row));
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);
        assertEquals(9, node.size(), "契约九键不多不少：" + json);
        var keys = new java.util.HashSet<String>();
        node.fieldNames().forEachRemaining(keys::add);
        assertEquals(java.util.Set.of("event", "delivery_id", "task_id", "status", "ai_tier",
                        "occurred_at", "result_url", "summary", "todos_count"),
                keys, "键名逐字对齐接入指南 §4.2");
        assertEquals("http://localhost:8080/api/v1/tasks/7", node.get("result_url").asText());
        assertEquals(2, node.get("todos_count").asInt(), "todos_count = JSONB 数组长度");
    }

    /** F-22（T4.12）：空 WEBHOOK_SECRET 时扫描不得把到期行交给投递器——不投递、不认领（attempts 不增长）、不空烧。 */
    @Test
    void scanDueSkipsDeliveryAndClaimWhenSecretEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(1L, 2L));
        WebhookDeliveryService service = new WebhookDeliveryService(
                jdbc, new ObjectMapper(), "", "", 3, "http://localhost:8080", false,
                mock(WebhookHttpSender.class));
        service.scanDue();
        service.scanDue();
        // attempt() 的认领走 jdbc.query(UPDATE ... RETURNING)（ResultSetExtractor 重载）：空 secret 必须一次都不发生
        verify(jdbc, never()).query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(Object[].class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    /** K-40 正向对照：secret 已配置时，同一份到期行必须正常进入认领流程（防止守卫写过头）。 */
    @Test
    void scanDueHandsDueRowsToDispatcherWhenSecretConfigured() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(1L));
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(null);
        WebhookDeliveryService service = new WebhookDeliveryService(
                jdbc, new ObjectMapper(), "", "t46-secret", 3, "http://localhost:8080", false,
                mock(WebhookHttpSender.class));
        service.scanDue();
        verify(jdbc, timeout(2000).atLeastOnce())
                .query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(Object[].class));
    }

    /* ---- T4.83/P-33：目标地址被 SSRF 防护拒绝 → 直接终局（不重试，防内网探测被放大 4 次） ---- */

    /** blocked（ApiException URL_BLOCKED）→ last_status=-1、next_retry=NULL 终局写法，无退避重试写、日志 WARN。 */
    @Test
    void urlBlockedAttemptIsTerminalWithoutRetry() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WebhookHttpSender sender = mock(WebhookHttpSender.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(Map.of("attempts", 1, "task_id", 7L,
                        "url", "http://169.254.169.254/latest/meta-data/", "event", "task.done"));
        when(sender.send(anyString(), anyString(), anyString(), anyString(), any(byte[].class)))
                .thenThrow(new com.meetingnotes.common.ApiException(400, "URL_BLOCKED", "目标地址不允许"));

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(WebhookDeliveryService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service(1L, jdbc, sender).attempt(1L);
        } finally {
            logger.detachAppender(appender);
        }

        // 终局写法：last_status = -1（STATUS_URL_BLOCKED）且 next_retry = NULL（不重试）
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("next_retry = NULL"),
                eq(WebhookDeliveryService.STATUS_URL_BLOCKED), eq(1L));
        // 没有第二次 attempts 自增/退避写（make_interval = 可重试路径，终局不得出现）
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.contains("make_interval"), any(Object[].class));
        assertEquals(1, appender.list.size(), "恰好一条 WARN 日志");
        assertEquals(ch.qos.logback.classic.Level.WARN, appender.list.get(0).getLevel());
        assertTrue(appender.list.get(0).getFormattedMessage()
                .contains("Webhook 目标地址被拒（URL_BLOCKED）"), "日志含固定文案");
        assertTrue(appender.list.get(0).getFormattedMessage().contains("169.254.169.254"), "日志含被拒 url");
    }


    /* ---- T4.96/C7-6（D-245）：回收站任务不投递 ---- */

    /** onTerminal：deleted_at 非空 → 不投递、不记账，日志 reason=TRASHED。 */
    @Test
    void onTerminalSkipsTrashedTaskWithReasonLogged() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(contains("deleted_at IS NOT NULL"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Integer>>any(), eq(7L)))
                .thenReturn(1); // 任务在回收站
        WebhookDeliveryService service = new WebhookDeliveryService(
                jdbc, new ObjectMapper(), "cb-x", "t496-secret", 3, "http://localhost:8080", false,
                mock(WebhookHttpSender.class));
        service.onTerminal(7L, "DONE");
        verify(jdbc, never()).query(contains("INSERT INTO webhook_delivery"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Long>>any(),
                any(Object[].class)); // 不记账 = 不投递
    }

    /** onTerminal：任务未删除（deleted_at NULL → 查不到行）→ 照常走投递记账（正向对照，防守卫写过头）。 */
    @Test
    void onTerminalNormalTaskStillDelivers() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(contains("deleted_at IS NOT NULL"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Integer>>any(), eq(8L)))
                .thenReturn(null); // 不在回收站
        when(jdbc.query(contains("INSERT INTO webhook_delivery"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Long>>any(),
                any(Object[].class))).thenReturn(99L);
        WebhookDeliveryService service = new WebhookDeliveryService(
                jdbc, new ObjectMapper(), "cb-x", "t496-secret", 3, "http://localhost:8080", false,
                mock(WebhookHttpSender.class));
        service.onTerminal(8L, "DONE");
        verify(jdbc).query(contains("INSERT INTO webhook_delivery"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Long>>any(),
                any(Object[].class));
    }

    /** F-22 同口径：重试扫描 SQL 必须 JOIN task 且排除回收站（任务进回收站 → 遗留到期行不投）。 */
    @Test
    void scanDueSqlExcludesTrashedTasks() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbc.queryForList(sql.capture(), eq(Long.class))).thenReturn(List.of());
        WebhookDeliveryService service = new WebhookDeliveryService(
                jdbc, new ObjectMapper(), "", "t496-secret", 3, "http://localhost:8080", false,
                mock(WebhookHttpSender.class));
        service.scanDue();
        assertTrue(sql.getValue().contains("JOIN task") && sql.getValue().contains("t.deleted_at IS NULL"),
                "重试扫描必须排除回收站任务（实测 SQL：" + sql.getValue().replace('\n', ' ') + "）");
    }

    private WebhookDeliveryService service(long deliveryId, JdbcTemplate jdbc, WebhookHttpSender sender) {
        return new WebhookDeliveryService(jdbc, new ObjectMapper(), "", "t483-secret", 3,
                "http://localhost:8080", false, sender);
    }
}
