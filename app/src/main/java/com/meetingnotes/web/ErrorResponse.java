package com.meetingnotes.web;

/** 统一错误响应结构：400 INVALID_MEDIA / 413 TOO_LARGE / 400 TOO_LONG / 429 QUOTA_EXCEEDED。 */
public record ErrorResponse(String code, String message) {
}
