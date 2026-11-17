package com.meetingnotes.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 详情契约键序：原 11 键 + template（T3.9 第 12）+ keypoints（T4.17 第 13）+ transcript_punctuated
 * （T4.18 第 14）+ segments（T4.35a 第 15）（不多不少、键序不变、历史/未生成 null——只增不改）。
 * 列表/POST/导出不在本记录，键集不受影响。
 */
class TaskStatusResponseTest {

    /** 生产同款：SNAKE_CASE + JavaTimeModule（OffsetDateTime 序列化），键序即 record 组件序。 */
    private static ObjectMapper productionMapper() {
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .registerModule(new JavaTimeModule());
    }

    private static TaskStatusResponse sample(String template) {
        return new TaskStatusResponse(124L, "DONE", 12L, 148L,
                OffsetDateTime.parse("2026-11-14T20:00:00+08:00"), "文本内容", null,
                "摘要", List.of("关键词"), "CLOUD",
                List.of(new TodoItemView("准备扩容方案", "运维组", null, false)),
                List.of(new KeypointView("扩容计划", "与会人员讨论了数据库扩容的时间表。")),
                "与会人员讨论了，数据库扩容的时间表。", template,
                java.util.List.of(new TaskStatusResponse.SegmentView(0.12, 3.45, "大家好，我们开始。")));
    }

    @Test
    void detailJsonHasExactlyFifteenKeysEndingWithSegments() throws Exception {
        ObjectMapper mapper = productionMapper();
        JsonNode node = mapper.readTree(mapper.writeValueAsString(sample("course")));

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        List<String> keys = new java.util.ArrayList<>();
        while (fields.hasNext()) {
            keys.add(fields.next().getKey());
        }
        assertEquals(List.of("task_id", "status", "duration_sec", "size_bytes", "created_at",
                        "transcript", "error_code", "summary", "keywords", "ai_tier", "todos",
                        "keypoints", "transcript_punctuated", "template", "segments"),
                keys, "键集 = 14 键 + segments（T4.35a 第 15，只增不改），不许插在中间或改名");
        assertEquals("course", node.get("template").asText(), "新任务 template 恒非空");
        JsonNode segments = node.get("segments");
        assertTrue(segments.isArray() && segments.size() == 1, "segments 数组元素序列化为 start/end/text");
        assertEquals(List.of("start", "end", "text"), toKeys(segments.get(0)), "segments 元素键 = start/end/text");
        assertEquals(0.12, segments.get(0).get("start").asDouble(), 1e-9);
        assertEquals("大家好，我们开始。", segments.get(0).get("text").asText());
    }

    private static List<String> toKeys(JsonNode node) {
        java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = node.fields();
        java.util.List<String> keys = new java.util.ArrayList<>();
        while (it.hasNext()) {
            keys.add(it.next().getKey());
        }
        return keys;
    }

    /** T4.17：keypoints 元素 snake_case（title/detail 原样即小写）。 */
    @Test
    void keypointsSerializeAsTitleDetailElements() throws Exception {
        ObjectMapper mapper = productionMapper();
        JsonNode node = mapper.readTree(mapper.writeValueAsString(sample("course")));
        assertTrue(node.get("keypoints").isArray(), "keypoints 必须是数组");
        JsonNode first = node.get("keypoints").get(0);
        Iterator<Map.Entry<String, JsonNode>> kpFields = first.fields();
        List<String> kpKeys = new java.util.ArrayList<>();
        while (kpFields.hasNext()) {
            kpKeys.add(kpFields.next().getKey());
        }
        assertEquals(List.of("title", "detail"), kpKeys, "要点元素键 = title/detail");
    }

    @Test
    void templateIsNullForLegacyTasks() throws Exception {
        ObjectMapper mapper = productionMapper();
        JsonNode node = mapper.readTree(mapper.writeValueAsString(sample(null)));
        assertTrue(node.get("template").isNull(), "T3.9 之前的旧任务 template 必须是 null");
        assertEquals(15, node.size(), "T4.35a 起 = 15 键（segments 只增在第 15）");
    }
}
