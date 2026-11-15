package com.meetingnotes.web;

import java.util.List;

/**
 * POST /api/v1/tasks/retry（T4.1）请求体：批量任务 id（1..20 个，去重在服务层做）。
 * 非数字元素由 Jackson 拒绝（HttpMessageNotReadableException → 400 INVALID_PARAM，既有映射）。
 */
public record RetryRequest(List<Long> ids) {
}
