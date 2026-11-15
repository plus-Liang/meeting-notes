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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
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
        String sig = WebhookDeliveryService.sign("t46-secret", body);
        // 接收端 §4.3 的验证算法（独立实现，互证不是自证）；"sha256=" 前缀在 post() 拼头时加
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("t46-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        assertEquals(HexFormat.of().formatHex(mac.doFinal(body)), sig);
        assertNotEquals(sig, WebhookDeliveryService.sign("wrong-secret", body));
        assertNotEquals(sig, WebhookDeliveryService.sign("t46-secret", "其它字节".getBytes()));
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
                mapper, "", "s", 3, 10, "http://localhost:8080", true);
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
                jdbc, new ObjectMapper(), "", "", 3, 10, "http://localhost:8080", false);
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
                jdbc, new ObjectMapper(), "", "t46-secret", 3, 10, "http://localhost:8080", false);
        service.scanDue();
        verify(jdbc, timeout(2000).atLeastOnce())
                .query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(Object[].class));
    }
}
