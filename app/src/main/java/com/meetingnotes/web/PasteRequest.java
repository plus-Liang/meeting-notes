package com.meetingnotes.web;

/**
 * POST /api/v1/tasks（application/json）请求体（T3.7）：PASTE 文本。
 * 校验（trim 后 1..100_000 字符）在 TextTaskService 做，controller 保持薄。
 */
public record PasteRequest(String text) {
}
