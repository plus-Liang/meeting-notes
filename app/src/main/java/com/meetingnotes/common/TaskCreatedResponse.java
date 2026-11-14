package com.meetingnotes.common;

/**
 * 201 响应体：任务受理成功（含幂等命中场景）。
 *
 * <p>对外契约字段一律 snake_case（D-041，全局 Jackson 策略统一转换）：
 * {@code task_id / status / content_hash / duration_sec / size_bytes / reused}。
 * {@code durationSec} 为包装类型（T3.7/D-084）：PASTE/字幕任务没有音频时长（null 照常输出），
 * 音频任务仍是非空数字——键与语义不变，只把类型放宽为 Long。
 */
public record TaskCreatedResponse(
        long taskId,
        String status,
        String contentHash,
        Long durationSec,
        long sizeBytes,
        boolean reused) {
}
