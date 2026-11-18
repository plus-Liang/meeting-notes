package com.meetingnotes.web;

import com.meetingnotes.task.TaskQueryService;
import com.meetingnotes.common.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务状态计数端点（T4.24，从 {@link TaskController} 独立成类，先例同 TaskDeleteController /
 * TaskExportController；TaskController 已顶 M3 300 行上限）：{@code GET /api/v1/tasks/counts}。
 * 当前用户 GROUP BY 真计数（无上限，取代前端"逐页累加上限 200"近似）；active = PENDING+RUNNING+RETRYING。
 * 只增端点，响应键集固定 {total, active, done, dead}。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskCountsController {


    private final TaskQueryService taskQueryService;

    public TaskCountsController(TaskQueryService taskQueryService) {
        this.taskQueryService = taskQueryService;
    }

    @GetMapping("/tasks/counts")
    public TaskQueryService.TaskCounts counts() {
        return taskQueryService.counts(UserContext.current());
    }
}
