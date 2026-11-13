package com.meetingnotes.web;

/** 201 响应体：任务受理成功（含幂等命中场景）。 */
public record TaskCreatedResponse(
        long taskId,
        String status,
        String contentHash,
        long durationSec,
        long sizeBytes,
        boolean duplicate) {
}
