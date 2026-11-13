package com.meetingnotes.web;

import java.time.OffsetDateTime;

/**
 * GET /api/v1/tasks/{id} 响应体（T1.5 / T1.6 追加 error_code，S-3）。
 *
 * <p>对外契约字段 snake_case（D-041）：{@code task_id / status / duration_sec / size_bytes /
 * created_at / transcript / error_code}。任务未到 DONE 时 transcript 恒为 null（接入方以 status 为准轮询）；
 * {@code error_code} 仅 RETRYING/DEAD 有值（稳定错误码，如 ASR_ERROR），其余为 null（D-047，只增不改）。
 */
public record TaskStatusResponse(
        long taskId,
        String status,
        Long durationSec,
        Long sizeBytes,
        OffsetDateTime createdAt,
        String transcript,
        String errorCode) {
}
