package com.meetingnotes.web;

import com.meetingnotes.task.TaskMutationService;

import java.util.List;

/**
 * POST /api/v1/tasks/{id}/corrections 请求体（T3.5）。
 * 全局 SNAKE_CASE 命名策略下，{@code wrongText/rightText} 对外即 {@code wrong_text/right_text}；
 * 校验（非空/≤100 字/不相等/≤50 条）在 service 层做，controller 保持薄。
 */
public record CorrectionsRequest(List<TaskMutationService.Correction> corrections) {
}
