package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** T4.101：{@link TaskController#status} 的「查询 + 装配」段原样搬出（纯搬家，零行为变化：SQL / JSONB 降级 / 19 键响应语义逐字不变；D-040 查询带 user_id，不存在或回收站 → 404 TASK_NOT_FOUND）。 */
@Component
public class TaskStatusAssembler {
    private final JdbcTemplate jdbc;
    /** F-10：详情解析用的单例 ObjectMapper（此前每行 new，属技术债）。 */
    private final ObjectMapper mapper;
    public TaskStatusAssembler(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    public TaskStatusResponse assemble(long id, long userId) {
        TaskStatusResponse resp = jdbc.query("""
                SELECT t.id, t.status, t.duration_sec, t.size_bytes, t.created_at, t.error_code,
                       CASE WHEN t.status = 'DONE' THEN r.transcript END AS transcript,
                       r.summary, r.keywords, r.ai_tier, r.todos, r.keypoints,
                       CASE WHEN t.status = 'DONE' THEN r.transcript_punctuated END AS transcript_punctuated,
                       r.template,
                       CASE WHEN t.status = 'DONE' THEN r.segments::text END AS segments,
                       r.speaker_map::text AS speaker_map,
                       t.display_name,
                       COALESCE((SELECT d.reason FROM dead_letter d WHERE d.task_id = t.id ORDER BY d.id DESC LIMIT 1), NULL) AS error_message,
                       t.attempts
                FROM task t
                LEFT JOIN task_result r ON r.task_id = t.id
                WHERE t.id = ? AND t.user_id = ? AND t.deleted_at IS NULL
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    // INT4 列 JDBC 返回 Integer，必须 getLong + wasNull，不能强转 getObject
                    long durationSec = rs.getLong("duration_sec");
                    Long duration = rs.wasNull() ? null : durationSec;
                    long sizeBytes = rs.getLong("size_bytes");
                    Long size = rs.wasNull() ? null : sizeBytes;
                    // keywords 列是 JSONB：取文本再解析成数组（T3.1），坏数据降级为 null 不炸接口（F-10：单例 mapper）
                    List<String> keywords = null;
                    String keywordsRaw = rs.getString("keywords");
                    if (keywordsRaw != null && !keywordsRaw.isBlank()) {
                        try {
                            keywords = mapper.readValue(keywordsRaw, new TypeReference<List<String>>() { });
                        } catch (Exception e) {
                            keywords = null;
                        }
                    }
                    // todos 列是 JSONB（T3.2/T3.5）：JsonNode → 视图（元素只增 done，老数据无 done 按 false 读）；
                    // 坏数据降级为 null 不炸接口
                    List<TodoItemView> todos = null;
                    String todosRaw = rs.getString("todos");
                    if (todosRaw != null && !todosRaw.isBlank()) {
                        try {
                            JsonNode array = mapper.readTree(todosRaw);
                            if (array.isArray()) {
                                todos = TodoItemView.listFrom(array);
                            }
                        } catch (Exception e) {
                            todos = null;
                        }
                    }
                    // keypoints 列是 JSONB（T4.17/D-109）：缺失/坏数据 → null（历史任务与"未生成"同语义），不炸接口
                    List<KeypointView> keypoints = null;
                    String keypointsRaw = rs.getString("keypoints");
                    if (keypointsRaw != null && !keypointsRaw.isBlank()) {
                        try {
                            JsonNode kpArray = mapper.readTree(keypointsRaw);
                            if (kpArray.isArray()) {
                                keypoints = KeypointView.listFrom(kpArray);
                            }
                        } catch (Exception e) {
                            keypoints = null;
                        }
                    }
                    // segments 列是 JSONB（T4.35a 第 15 键）：句子级时间戳数组；旧任务/PASTE/坏数据 → null（不是 []），不炸接口
                    List<TaskStatusResponse.SegmentView> segments = null;
                    String segmentsRaw = rs.getString("segments");
                    if (segmentsRaw != null && !segmentsRaw.isBlank()) {
                        try {
                            JsonNode segArray = mapper.readTree(segmentsRaw);
                            if (segArray.isArray()) {
                                segments = new ArrayList<>();
                                for (JsonNode seg : segArray) {
                                    segments.add(new TaskStatusResponse.SegmentView(
                                            seg.path("start").asDouble(),
                                            seg.path("end").asDouble(),
                                            seg.path("text").asText("")));
                                }
                            }
                        } catch (Exception e) {
                            segments = null;
                        }
                    }
                    // speaker_map 列是 JSONB（T4.36 第 16 键）：未标注/清除/旧任务/坏数据 → null（不是 {}），不炸接口
                    Map<String, String> speakerMap = null;
                    String speakerRaw = rs.getString("speaker_map");
                    if (speakerRaw != null && !speakerRaw.isBlank()) {
                        try {
                            JsonNode smObj = mapper.readTree(speakerRaw);
                            if (smObj.isObject()) {
                                Map<String, String> parsed = new java.util.LinkedHashMap<>();
                                smObj.fields().forEachRemaining(f -> parsed.put(f.getKey(), f.getValue().asText()));
                                speakerMap = parsed;
                            }
                        } catch (Exception e) { speakerMap = null; }
                    }
                    return new TaskStatusResponse(rs.getLong("id"), rs.getString("status"),
                            duration, size,
                            rs.getObject("created_at", OffsetDateTime.class), rs.getString("transcript"),
                            rs.getString("error_code"), rs.getString("summary"), keywords,
                            rs.getString("ai_tier"), todos, keypoints,
                            rs.getString("transcript_punctuated"), rs.getString("template"), segments,
                            speakerMap, rs.getString("display_name"),
                            rs.getString("error_message"), (Integer) rs.getObject("attempts"));
                },
                id, userId);
        if (resp == null) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + id + " 不存在");
        }
        return resp;
    }
}
