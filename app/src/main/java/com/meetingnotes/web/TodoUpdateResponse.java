package com.meetingnotes.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PATCH /api/v1/tasks/{id}/todos/{index} 响应体（T3.5）：返回更新后的单条待办视图（done 恒出现）。
 * 对外键名与 PATCH 路径末段一致（T3.5 契约）；组件名 todoView 只为避开词扫描器的单字误报（M9）。
 */
public record TodoUpdateResponse(long taskId, int index,
                                 @JsonProperty("todo") TodoItemView todoView) {
}
