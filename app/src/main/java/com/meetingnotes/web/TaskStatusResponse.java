package com.meetingnotes.web;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /api/v1/tasks/{id} 响应体（T1.5 / T1.6 追加 error_code，S-3 / T3.1 追加摘要三字段 /
 * T3.2 追加 todos，T3.9 追加 template，T4.17 追加 keypoints，T4.18 追加 transcript_punctuated——只增不改）。
 *
 * <p>对外契约字段 snake_case（D-041）：{@code task_id / status / duration_sec / size_bytes /
 * created_at / transcript / error_code / summary / keywords / ai_tier / todos / keypoints /
 * transcript_punctuated / template}。
 * 任务未到 DONE 时 transcript 恒为 null（接入方以 status 为准轮询）；{@code error_code} 仅 RETRYING/DEAD 有值
 * （稳定错误码，如 ASR_ERROR），其余为 null（D-047，只增不改）；{@code summary/keywords/ai_tier}
 * 为摘要结果（D-068）；{@code todos} 为待办数组（元素 snake_case：action/owner/deadline，
 * T3.5 起元素**只增** `done`（勾选状态，老数据无此字段按 false 读），owner/deadline 未知为 null），
 * AI 未启用或失败时为 null（T3.2/D-069）；{@code keypoints} 为要点数组（元素 title/detail，
 * T4.17/D-109 第 13 键）：LLM 输出缺失/形状不合法或历史任务为 null（与「未生成」同语义，
 * 不影响 summary/keywords/todos）；{@code transcript_punctuated} 为 AI 加标点转写全文（T4.18 第 14 键）：
 * 仅 CLOUD 档可用且未超长/未失败时非 null，否则 null（前端回退原始 transcript，任务不受影响）；
 * {@code template} 为摘要模板（T3.9）：新任务恒非空（meeting/course/interview/podcast/shortvideo 之一），
 * T3.9 之前的旧任务为 null。
 */
public record TaskStatusResponse(
        long taskId,
        String status,
        Long durationSec,
        Long sizeBytes,
        OffsetDateTime createdAt,
        String transcript,
        String errorCode,
        String summary,
        List<String> keywords,
        String aiTier,
        List<TodoItemView> todos,
        List<KeypointView> keypoints,
        String transcriptPunctuated,
        String template) {
}
