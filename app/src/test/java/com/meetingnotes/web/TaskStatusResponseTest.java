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
 * （T4.18 第 14）+ segments（T4.35a 第 15）+ speaker_map（T4.36 第 16）+ display_name（T4.43 第 17）
 * （不多不少、键序不变、历史/未生成 null——只增不改）。
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
        return sample(template, null, 0);
    }

    /** T4.95：可指定末尾两新键（error_message / attempts）的构造。 */
    private static TaskStatusResponse sample(String template, String errorMessage, Integer attempts) {
        return new TaskStatusResponse(124L, "DONE", 12L, 148L,
                OffsetDateTime.parse("2026-11-14T20:00:00+08:00"), "文本内容", null,
                "摘要", List.of("关键词"), "CLOUD",
                List.of(new TodoItemView("准备扩容方案", "运维组", null, false)),
                List.of(new KeypointView("扩容计划", "与会人员讨论了数据库扩容的时间表。")),
                "与会人员讨论了，数据库扩容的时间表。", template,
                java.util.List.of(new TaskStatusResponse.SegmentView(0.12, 3.45, "大家好，我们开始。")),
                java.util.Map.of("0", "说话人 1"),
                "第三章 讲座", errorMessage, attempts);
    }

    @Test
    void detailJsonHasExactlySixteenKeysEndingWithSpeakerMap() throws Exception {
        ObjectMapper mapper = productionMapper();
        JsonNode node = mapper.readTree(mapper.writeValueAsString(sample("course", "文件读取失败", 3)));

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        List<String> keys = new java.util.ArrayList<>();
        while (fields.hasNext()) {
            keys.add(fields.next().getKey());
        }
        assertEquals(List.of("task_id", "status", "duration_sec", "size_bytes", "created_at",
                        "transcript", "error_code", "summary", "keywords", "ai_tier", "todos",
                        "keypoints", "transcript_punctuated", "template", "segments", "speaker_map",
                        "display_name", "error_message", "attempts"),
                keys, "键集 = 17 键（T4.43 止）+ T4.95 末尾只增 error_message/attempts，不许插在中间或改名");
        assertEquals("course", node.get("template").asText(), "新任务 template 恒非空");
        assertEquals("文件读取失败", node.get("error_message").asText(), "T4.95：error_message 第 18 键（人话原因）");
        assertEquals(3, node.get("attempts").asInt(), "T4.95：attempts 第 19 键（已尝试次数）");
        JsonNode segments = node.get("segments");
        assertTrue(segments.isArray() && segments.size() == 1, "segments 数组元素序列化为 start/end/text");
        assertEquals(List.of("start", "end", "text"), toKeys(segments.get(0)), "segments 元素键 = start/end/text");
        assertEquals(0.12, segments.get(0).get("start").asDouble(), 1e-9);
        assertEquals("大家好，我们开始。", segments.get(0).get("text").asText());
        assertEquals("说话人 1", node.get("speaker_map").get("0").asText(),
                "speaker_map 对象键=句子下标字符串、值=标签");
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
        assertEquals(19, node.size(), "T4.95 起 = 19 键（error_message/attempts 只增在末尾，null/0 也保留键）");
    }

    /** T4.43：display_name 第 17 键——未命名/清除为 null（不是空串）；命名后为展示名。 */
    @Test
    void displayNameSeventeenthKeyNullOrValue() throws Exception {
        ObjectMapper mapper = productionMapper();
        JsonNode named = mapper.readTree(mapper.writeValueAsString(sample("course")));
        assertEquals("第三章 讲座", named.get("display_name").asText(), "命名任务返回展示名");
        // 末尾键序（键名断言已覆盖），这里补 null 场景：用 null displayName/errorMessage 构造
        TaskStatusResponse unnamed = new TaskStatusResponse(124L, "DONE", 12L, 148L,
                OffsetDateTime.parse("2026-11-14T20:00:00+08:00"), "文本内容", null,
                "摘要", List.of("关键词"), "CLOUD", List.of(), List.of(),
                null, "meeting", List.of(), java.util.Map.of(), null, null, 0);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(unnamed));
        assertTrue(node.get("display_name").isNull(), "未命名/旧任务 display_name = null（不是空串）");
        assertTrue(node.get("error_message").isNull(), "无失败历史的任务 error_message = null");
        assertEquals(0, node.get("attempts").asInt(), "正常任务 attempts = 0");
        assertEquals(19, node.size(), "null 也保留键（契约键不省略）");
    }

    /** T4.95：error_message / attempts 第 18/19 键——只增在末尾，语义见 C3。 */
    @Test
    void failureFieldsAreEighteenthAndNineteenthKeys() throws Exception {
        ObjectMapper mapper = productionMapper();
        JsonNode node = mapper.readTree(mapper.writeValueAsString(sample("course", "ASR 连接超时", 2)));
        assertEquals("ASR 连接超时", node.get("error_message").asText());
        assertEquals(2, node.get("attempts").asInt());
        assertEquals(19, node.size(), "键总数 19，前 17 键一个不少（只增不改）");
    }
}
