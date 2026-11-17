package com.meetingnotes.web;

import com.meetingnotes.fetch.LinkPreviewService;
import com.meetingnotes.fetch.LinkPreviewService.LinkPreviewResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 链接预览入口（T4.32b）：POST /api/v1/link/preview —— 贴链接先看确认卡（"是这个视频吗？"），
 * 不直接建任务。认证由 ApiTokenFilter 横切覆盖（/api/v1/** 全 Bearer，K-46）。
 *
 * <p>请求体 {@code {"url":"https://..."}}；响应 {@code kind=direct|page|unsupported} +
 * final_url/content_type/title/image/duration_sec/byof_available/reason（契约见接入指南 §3.9）。
 * 错误语义与 /tasks/link 同源（同一道 UrlGuard）：400 URL_BLOCKED / 502 FETCH_FAILED / 400 INVALID_PARAM。
 */
@RestController
@RequestMapping("/api/v1")
public class LinkPreviewController {

    private final LinkPreviewService linkPreviewService;

    public LinkPreviewController(LinkPreviewService linkPreviewService) {
        this.linkPreviewService = linkPreviewService;
    }

    @PostMapping(value = "/link/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public LinkPreviewResponse preview(@RequestBody LinkRequest body) {
        return linkPreviewService.preview(body == null ? null : body.url());
    }
}
