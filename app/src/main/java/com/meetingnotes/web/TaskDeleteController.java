package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.task.TaskDeleteService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务删除端点（T4.21 / D-115，从 {@link TaskController} 独立成类，先例同 TaskExportController）：
 * <ul>
 *   <li>{@code DELETE /api/v1/tasks/{id}}：仅 DONE/DEAD 可删；不存在 → 404 TASK_NOT_FOUND；
 *       PENDING/RUNNING/RETRYING → 409 TASK_BUSY（新错误码，只增）；成功 → 200 {"deleted":1}；</li>
 *   <li>{@code POST /api/v1/tasks/delete}：body {"ids":[…]}，1..100 条、去重、逐条独立事务
 *       **不整体失败**（口径同 T4.1 批量重试）→ 200 {"results":[{"id","deleted","reason"}]}；</li>
 *   <li>{@code POST /api/v1/tasks/delete-failed}：删当前用户全部 DEAD（不限已加载行；
 *       RETRYING 不算失败不删）→ 200 {"deleted":N}。</li>
 * </ul>
 * 事务删 task/task_result/dead_letter/webhook_delivery（FK 顺序）；MinIO best-effort（失败只 WARN 不回滚）；
 * **删除后不可恢复，已用配额不退还**（D-115）。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskDeleteController {

    /** 批量删除响应元素（snake_case：id/deleted/reason；成功 reason 为 null，只取 NOT_FOUND / TASK_BUSY）。 */
    public record DeleteItemResult(long id, boolean deleted, String reason) {
    }

    /** 批量删除响应（部分成功口径：逐条结果，不整体失败）。 */
    public record BatchDeleteResponse(List<DeleteItemResult> results) {
    }

    /** 删除计数响应（单条成功 / 清空失败任务共用）。 */
    public record DeleteCountResponse(int deleted) {
    }

    private final TaskDeleteService taskDeleteService;

    public TaskDeleteController(TaskDeleteService taskDeleteService) {
        this.taskDeleteService = taskDeleteService;
    }

    @DeleteMapping("/tasks/{id}")
    public DeleteCountResponse deleteTask(@PathVariable("id") long id) {
        TaskDeleteService.DeleteOutcome outcome = taskDeleteService.deleteOne(id);
        if (!outcome.deleted()) {
            if ("TASK_BUSY".equals(outcome.reason())) {
                throw new ApiException(409, "TASK_BUSY",
                        "任务正在排队或转写中，不能删除（删除后不可恢复，已用配额不退还）");
            }
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + id + " 不存在");
        }
        taskDeleteService.purgeObjectBestEffort(id, outcome.sourceUri());
        return new DeleteCountResponse(1);
    }

    /** 批量删除：ids 复用 T4.1 RetryRequest 形状（{"ids":[…]}）。 */
    @PostMapping(value = "/tasks/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BatchDeleteResponse deleteBatch(@RequestBody RetryRequest body) {
        List<Long> ids = body == null ? null : body.ids();
        List<TaskDeleteService.DeleteOutcome> outcomes = taskDeleteService.deleteBatch(ids);
        List<DeleteItemResult> results = new ArrayList<>();
        for (TaskDeleteService.DeleteOutcome o : outcomes) {
            if (o.deleted()) {
                taskDeleteService.purgeObjectBestEffort(o.id(), o.sourceUri());
            }
            results.add(new DeleteItemResult(o.id(), o.deleted(), o.reason()));
        }
        return new BatchDeleteResponse(results);
    }

    @PostMapping(value = "/tasks/delete-failed", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DeleteCountResponse deleteFailed() {
        return new DeleteCountResponse(taskDeleteService.deleteAllFailed());
    }
}
