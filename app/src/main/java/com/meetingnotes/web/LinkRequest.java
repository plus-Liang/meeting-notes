package com.meetingnotes.web;

/**
 * 链接导入请求体（T4.32a）：{"url":"https://..."}——可直接下载的 http(s) 音/视频直链。
 * 网页链接（Content-Type: text/html）→ 400 NOT_DIRECT_MEDIA；内网/回环等 → 400 URL_BLOCKED。
 */
public record LinkRequest(String url) {
}
