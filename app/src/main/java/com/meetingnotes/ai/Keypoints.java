package com.meetingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 音频要点（T4.17 / D-109）：与 summary/keywords/todos 同一次 LLM 调用产出的编号要点
 * （{@code "keypoints": [{"title": "...", "detail": "..."}]}）。
 *
 * <p>口径（提示词约束，代码层只做预算兜底）：条数自适应——短内容 3~6 条、长内容最多
 * {@value #MAX_KEYPOINTS} 条；title ≤20 字；detail 40~120 字；必须来自 transcript、严禁编造；
 * 内容短不要硬凑。**容错不整条降级**（D-109）：keypoints 缺失或形状不合法 → 置 null，
 * 不影响 summary/keywords/todos 落库。
 */
final class Keypoints {

    /** 代码层硬截断上限（与提示词口径一致：长内容最多 15 条）。 */
    static final int MAX_KEYPOINTS = 15;

    private Keypoints() {
    }

    /**
     * 容错解析：缺失 / null / 形状不是数组 → null（与历史任务「未生成」同语义）；
     * 数组 → 逐条取 {@code title/detail}，空 title 或空 detail 的条目丢弃（宁缺毋滥），
     * 超出 {@value #MAX_KEYPOINTS} 条截断。数组存在但零条有效 → 空列表（=确实没要点）。
     */
    static List<StructuringResult.KeyPoint> parse(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return null;
        }
        List<StructuringResult.KeyPoint> keypoints = new ArrayList<>();
        for (JsonNode item : node) {
            if (keypoints.size() >= MAX_KEYPOINTS) {
                break;
            }
            String title = item.path("title").asText(null);
            String detail = item.path("detail").asText(null);
            if (title != null && !title.isBlank() && detail != null && !detail.isBlank()) {
                keypoints.add(new StructuringResult.KeyPoint(title.trim(), detail.trim()));
            }
        }
        return keypoints;
    }
}
