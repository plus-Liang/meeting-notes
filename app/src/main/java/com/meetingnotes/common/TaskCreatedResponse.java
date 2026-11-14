package com.meetingnotes.common;

/**
 * 201 响应体：任务受理成功（含幂等命中场景）。
 *
 * <p>对外契约字段一律 snake_case（D-041，全局 Jackson 策略统一转换）：
 * {@code task_id / status / content_hash / duration_sec / size_bytes / reused}。
 */
public record TaskCreatedResponse(
        long taskId,
        String status,
        String contentHash,
        long durationSec,
        long sizeBytes,
        boolean reused) {
}
