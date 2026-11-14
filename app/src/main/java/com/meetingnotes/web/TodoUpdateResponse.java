package com.meetingnotes.web;

/**
 * PATCH /api/v1/tasks/{id}/todos/{index} 响应体（T3.5）：返回更新后的单条待办视图（done 恒出现）。
 * 组件名即对外键名（全局 SNAKE_CASE 策略，D-041）；M9 词扫描已改为只扫注释文本（2026-11-14）。
 */
public record TodoUpdateResponse(long taskId, int index, TodoItemView todo) {
}
