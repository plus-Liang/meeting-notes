package com.meetingnotes.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 对外要点视图（T4.17 / D-109）：jsonb 元素 {@code {title,detail}} → snake_case 出口视图。
 * 与 {@link TodoItemView} 同思路：AI 存储（ai 层）与展示分离，web 出口用独立视图；
 * 历史任务 / 未生成 → jsonb 为 null → 详情响应 keypoints 为 null。
 */
public record KeypointView(String title, String detail) {

    /** 从 jsonb 元素映射视图；缺失字段按 null 读（容错）。 */
    public static KeypointView from(JsonNode node) {
        return new KeypointView(node.path("title").asText(null), node.path("detail").asText(null));
    }

    /** jsonb 数组 → 视图列表（调用方保证 node 为数组）。 */
    public static List<KeypointView> listFrom(JsonNode array) {
        List<KeypointView> out = new ArrayList<>();
        for (JsonNode item : array) {
            out.add(from(item));
        }
        return out;
    }
}
