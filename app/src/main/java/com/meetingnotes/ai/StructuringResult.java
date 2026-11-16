package com.meetingnotes.ai;

import java.util.List;

/**
 * 结构化结果：三档**同构**输出（T3.3 ④，D-020；T4.17 增 keypoints）。
 *
 * <p>上层（worker / 落库）只认这一个 DTO，不写"云档这么读、本地档那么读"的三套分支。
 * {@code summary} 非空；{@code keywords}/{@code todos} 恒非 null（可为空列表）；
 * {@code keypoints} 可为 null（未生成：LLM 输出缺失/形状不合法，或 EXTRACTIVE 档不产要点——
 * 与历史任务同语义，**不参与降级判定**，见 D-109）；{@code aiTier} 是实际产出该结果的档位，落 {@code task_result.ai_tier}。
 */
public record StructuringResult(
        String summary,
        List<String> keywords,
        List<TodoService.TodoItem> todos,
        List<KeyPoint> keypoints,
        AiTier aiTier) {

    /** T4.17 要点条目：title ≤20 字 / detail 40~120 字的口径由提示词约束，解析层只做非空与条数截断。 */
    public record KeyPoint(String title, String detail) {
    }
}
