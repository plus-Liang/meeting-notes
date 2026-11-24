package com.meetingnotes.web;

import com.meetingnotes.common.UserContext;
import com.meetingnotes.task.QuotaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T4.87 C5：配额只读端点（登录用户即可，ApiTokenFilter 已覆盖 /api/v1/**）。
 * 与 {@code GET /api/v1/settings} 的配额口径同源（{@link QuotaService}），但额外显式给出策略 policy，
 * 供前端/接入方机器可读；**不改 /settings 响应结构**。
 */
@RestController
@RequestMapping("/api/v1")
public class QuotaController {

    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @GetMapping("/quota")
    public Map<String, Object> quota() {
        QuotaService.Usage usage = quotaService.currentUsage(UserContext.current());
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("delete_refunds", false);
        policy.put("failure_refunds", "minutes_only");
        policy.put("retry_consumes", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("minutes_per_day", quotaService.minutesPerDay());
        body.put("tasks_per_day", quotaService.tasksPerDay());
        body.put("used_minutes", usage.usedMinutes());
        body.put("used_tasks", usage.usedTasks());
        body.put("remaining_minutes", Math.max(0, quotaService.minutesPerDay() - usage.usedMinutes()));
        body.put("remaining_tasks", Math.max(0, quotaService.tasksPerDay() - usage.usedTasks()));
        body.put("policy", policy);
        return body;
    }
}
