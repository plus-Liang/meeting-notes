package com.meetingnotes.web;

import java.time.OffsetDateTime;

/**
 * GET /api/v1/tasks/{id} 响应体（T1.5）。
 *
 * <p>对外契约字段 snake_case（D-041）：{@code task_id / status / duration_sec / size_bytes /
 * created_at / transcript}。任务未到 DONE 时 transcript 恒为 null（接入方以 status 为准轮询）。
 */
public record TaskStatusResponse(
        long taskId,
        String status,
        Long durationSec,
        Long sizeBytes,
        OffsetDateTime createdAt,
        String transcript) {
}
