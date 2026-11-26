package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手工说话人标注（T4.36）：句子下标 → 标签，落 task_result.speaker_map（JSONB，V1 建表已有，
 * **本卡零迁移**）。自动说话人分离不做（先 spike 再定）；这是纯人工标注的读写闸。
 *
 * <p>校验口径（卡面逐字）：任务不存在/非本人 → 404 TASK_NOT_FOUND；非 DONE / 无 segments
 * （segments 为 NULL 或空数组，即无句子可标注）→ 400 INVALID_PARAM；键非整数/负/越界
 * （≥ segments 长度）、值空或 &gt;20 字、条目 &gt;200 → 400 INVALID_PARAM。
 *
 * <p>清除语义（写进文档定死）：空 map {@code {}} = 清除 → 落 **NULL**（与"旧任务/从未标注"
 * 同语义，详情键回 null），响应 {"speaker_map":null}。时间戳列：task_result 只有 created_at
 * （无 updated_at），与 saveTranscript 同口径落 created_at = now()。
 */
@Service
public class SpeakerMapService {

    /** 标签长度上限（卡面 1–20 字符）。 */
    static final int LABEL_MAX_CHARS = 20;
    /** 条目上限（卡面 >200 拒）。 */
    static final int MAX_ENTRIES = 200;

    /** PATCH 响应（对外 snake_case）：speaker_map 为落库后的实际内容（清除后为 null）。 */
    public record SpeakerMapResponse(Map<String, String> speakerMap) {
    }

    private final JdbcTemplate jdbc;

    public SpeakerMapService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 结果：map=null 表示清除（落 NULL）；非 null 为规范化后的键值。 */
    public SpeakerMapResponse update(long taskId, long userId, Map<String, String> speakerMap) {
        Map<String, Object> row = jdbc.query(
                "SELECT t.status, COALESCE(jsonb_array_length(r.segments), -1) AS seg_len "
                        + "FROM task t LEFT JOIN task_result r ON r.task_id = t.id WHERE t.id = ? AND t.user_id = ? AND t.deleted_at IS NULL",
                rs -> rs.next()
                        ? Map.of("status", rs.getString(1), "seg_len", rs.getInt(2))
                        : null,
                taskId, userId);
        if (row == null) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + taskId + " 不存在");
        }
        if (!"DONE".equals(row.get("status"))) {
            throw new ApiException(400, "INVALID_PARAM", "任务未完成（仅 DONE 任务可标注说话人）");
        }
        int segLen = (Integer) row.get("seg_len");
        if (segLen <= 0) {
            throw new ApiException(400, "INVALID_PARAM",
                    "该任务没有句子级时间戳（segments 为空），无法标注说话人");
        }
        if (speakerMap == null) {
            speakerMap = Map.of(); // 请求体缺 speaker_map 视同清空
        }
        if (speakerMap.size() > MAX_ENTRIES) {
            throw new ApiException(400, "INVALID_PARAM",
                    "标注条目超过上限 " + MAX_ENTRIES + "（当前 " + speakerMap.size() + "）");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : speakerMap.entrySet()) {
            String key = e.getKey();
            if (key == null || !key.matches("\\d{1,9}")) {
                throw new ApiException(400, "INVALID_PARAM", "句子下标必须是整数：" + key);
            }
            int idx;
            try {
                idx = Integer.parseInt(key);
            } catch (NumberFormatException nfe) {
                throw new ApiException(400, "INVALID_PARAM", "句子下标必须是整数：" + key);
            }
            if (idx < 0 || idx >= segLen) {
                throw new ApiException(400, "INVALID_PARAM",
                        "句子下标越界（共 " + segLen + " 句）：" + idx);
            }
            String label = e.getValue() == null ? "" : e.getValue().trim();
            if (label.isEmpty() || label.length() > LABEL_MAX_CHARS) {
                throw new ApiException(400, "INVALID_PARAM",
                        "标签须为 1–" + LABEL_MAX_CHARS + " 字符：" + (label.isEmpty() ? "（空）" : label));
            }
            normalized.put(String.valueOf(idx), label);
        }
        if (normalized.isEmpty()) {
            // 空 map = 清除 → 落 NULL（与"从未标注"同语义，详情键回 null；文档已定死）
            jdbc.update("UPDATE task_result SET speaker_map = NULL, created_at = now() WHERE task_id = ?", taskId);
            return new SpeakerMapResponse(null);
        }
        String json = toJson(normalized);
        jdbc.update("""
                INSERT INTO task_result (task_id, speaker_map) VALUES (?, ?::jsonb)
                ON CONFLICT (task_id) DO UPDATE SET speaker_map = EXCLUDED.speaker_map, created_at = now()
                """, taskId, json);
        return new SpeakerMapResponse(normalized);
    }

    /** Map → jsonb 文本（LinkedHashMap 保序；异常降级为空 map 清除，不炸接口）。 */
    private static String toJson(Map<String, String> map) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
