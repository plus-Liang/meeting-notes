package com.meetingnotes.web;

import java.util.List;

/**
 * POST /api/v1/tasks/retry（T4.1）响应体（snake_case，D-041）：
 * {@code retried} = 已复活重入队的 DEAD 任务 id；{@code skipped} = 未处理明细
 * （{@code reason} 只取 NOT_DEAD / NOT_FOUND 固定值，不整体失败）。
 */
public record TaskRetryResponse(List<Long> retried, List<SkippedView> skipped) {

    /** 逐条跳过明细：id + 固定 reason。 */
    public record SkippedView(Long id, String reason) {
    }
}
