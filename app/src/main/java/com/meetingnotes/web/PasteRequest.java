package com.meetingnotes.web;

/**
 * POST /api/v1/tasks（application/json）请求体（T3.7）：PASTE 文本 + 可选模板（T3.9）。
 * 校验（trim 后 1..100_000 字符、template 合法值）在 TextTaskService/TemplateResolver 做，controller 保持薄。
 */
public record PasteRequest(String text, String template, String language) { /* T4.99：末尾只增 */
}
