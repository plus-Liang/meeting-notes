package com.meetingnotes.web;

/**
 * PATCH /api/v1/tasks/{id}/todos/{index} 请求体（T3.5）。
 * {@code done} 缺失（null）→ 400；非布尔 → 400（HttpMessageNotReadableException 分层）。
 */
public record TodoPatchRequest(Boolean done) {
}
