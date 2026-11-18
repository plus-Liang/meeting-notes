package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import com.meetingnotes.task.RestructureService;
import com.meetingnotes.task.TemplateResolver;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 换模板重跑端点（T4.26 / F-17，从 TaskController 独立成类，先例同 TaskCountsController）：
 * {@code POST /api/v1/tasks/{id}/restructure}，body {@code {"template":"course"}}。
 * 只重跑结构化：不重跑 ASR、不新建任务、不扣任务配额；成功覆盖 summary/keywords/todos/keypoints/ai_tier/template，
 * 失败保留旧值不写半截；并发第二个请求 409 {@code TASK_BUSY}（NOWAIT 行锁）。
 * 模板非法 → 400 {@code INVALID_PARAM}（message 沿用 D-085 固定文案，不静默回落）。
 */
@RestController
@RequestMapping("/api/v1")
public class RestructureController {


    private final RestructureService restructureService;

    public RestructureController(RestructureService restructureService) {
        this.restructureService = restructureService;
    }

    /** 重跑请求体（只带模板；显式必填——空白/缺失 → 400，不回落当前模板）。 */
    public record RestructureRequest(String template) {
    }

    @PostMapping(value = "/tasks/{id}/restructure", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RestructureService.RestructureResponse restructure(@PathVariable("id") long id,
                                                              @RequestBody(required = false) RestructureRequest body) {
        String template = TemplateResolver.validateExplicit(body == null ? null : body.template());
        if (template == null) {
            throw new ApiException(400, "INVALID_PARAM",
                    "template 必填（meeting/course/interview/podcast/shortvideo 5 选 1）");
        }
        return restructureService.restructure(UserContext.current(), id, template);
    }
}
