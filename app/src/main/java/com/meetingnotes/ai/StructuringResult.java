package com.meetingnotes.ai;

import java.util.List;

/**
 * 结构化结果：三档**同构**输出（T3.3 ④，D-020）。
 *
 * <p>上层（worker / 落库）只认这一个 DTO，不写"云档这么读、本地档那么读"的三套分支。
 * {@code summary} 非空；{@code keywords}/{@code todos} 恒非 null（可为空列表）；
 * {@code aiTier} 是实际产出该结果的档位，落 {@code task_result.ai_tier}。
 */
public record StructuringResult(
        String summary,
        List<String> keywords,
        List<TodoService.TodoItem> todos,
        AiTier aiTier) {
}
