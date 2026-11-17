package com.meetingnotes.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * ASR 响应解析纯函数（T4.35a）：POST /v1/audio/transcriptions 的 JSON 体 → text + segments。
 *
 * <p>**纯函数、不许抛**（卡判据矩阵：正常 segments / 无 segments 键 → null / 空数组 → [] /
 * 负数或乱序（end&lt;start）不采信 / 空 text 保留（strip）/ 非 JSON → text=null（由调用方按
 * ASR_ERROR 处理）/ 老响应只有三键 → segments=null / 元素缺字段不采信），任何输入都不 500。
 *
 * <p>segments 元素口径与 ASR 服务契约一致：start/end 秒（3 位小数）、text 已去首尾空白。
 */
public final class AsrResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单句时间戳：start/end 秒（double，3 位小数），text 去首尾空白。 */
    public record Segment(double start, double end, String text) {
    }

    /** 解析结果：text=null 表示响应不可解析（非 JSON/缺 text 键），由调用方按 ASR_ERROR 处理。 */
    public record AsrResult(String text, List<Segment> segments) {
    }

    private AsrResponseParser() {
    }

    public static AsrResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new AsrResult(null, null);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(raw);
        } catch (Exception e) {
            return new AsrResult(null, null); // 非 JSON：不抛，交调用方按失败语义处理
        }
        JsonNode textNode = root.get("text");
        String text = textNode == null || !textNode.isTextual() ? null : textNode.asText();
        JsonNode segs = root.get("segments");
        List<Segment> out = null;
        if (segs != null && segs.isArray()) {
            out = new ArrayList<>();
            for (JsonNode item : segs) {
                double start = item.path("start").asDouble(Double.NaN);
                double end = item.path("end").asDouble(Double.NaN);
                if (!Double.isFinite(start) || !Double.isFinite(end)) {
                    continue; // 缺字段/非数值不采信
                }
                if (start < 0 || end < 0 || end < start) {
                    continue; // 负数或乱序（end<start）不采信（卡判据逐字）
                }
                String segText = item.path("text").asText("").strip();
                out.add(new Segment(start, end, segText));
            }
        }
        // 无 segments 键（老响应只有三键）或非数组 → null：旧任务/旧响应语义，不是 []
        return new AsrResult(text, out);
    }

    /** List&lt;Segment&gt; → jsonb 文本（worker 落库用）；null → SQL NULL（旧任务/无音频是 null 不是 []）。序列化失败按 null 降级，不抛。 */
    public static String toJson(java.util.List<Segment> segments) {
        if (segments == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(segments);
        } catch (Exception e) {
            return null;
        }
    }
}
