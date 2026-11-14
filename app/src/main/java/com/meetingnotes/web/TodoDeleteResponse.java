package com.meetingnotes.web;

/**
 * DELETE /api/v1/tasks/{id}/todos/{index} 响应体（T3.5）。
 * 非幂等（契约明示）：删除会使后续 index 移位，重复删同一 index 第二次按越界 400。
 */
public record TodoDeleteResponse(long taskId, int index, int removedCount, int remainingCount) {
}
