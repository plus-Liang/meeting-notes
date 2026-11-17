package com.meetingnotes.web;

import com.meetingnotes.common.TaskCreatedResponse;
import com.meetingnotes.task.LinkTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 链接导入入口（T4.32a）：POST /api/v1/tasks/link —— 贴 http(s) 音/视频直链建任务。
 *
 * <p>认证由 ApiTokenFilter 横切（/api/v1/** 全 Bearer，K-46）；请求体 {@code {"url":"https://..."}}；
 * 响应与上传同形 {@link TaskCreatedResponse}（201）。错误语义（固定码，接入指南 §3.9）：
 * 400 URL_BLOCKED / 400 NOT_DIRECT_MEDIA / 413 SIZE_EXCEEDED / 502 FETCH_FAILED / 400 INVALID_PARAM（url 空）。
 */
@RestController
@RequestMapping("/api/v1")
public class LinkTaskController {

    private final LinkTaskService linkTaskService;

    public LinkTaskController(LinkTaskService linkTaskService) {
        this.linkTaskService = linkTaskService;
    }

    @PostMapping(value = "/tasks/link", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TaskCreatedResponse> link(@RequestBody LinkRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(linkTaskService.createLink(body == null ? null : body.url()));
    }
}
