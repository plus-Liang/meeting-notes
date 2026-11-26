package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import com.meetingnotes.task.TaskTrashService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 回收站端点（T4.96/C6，v0.2 D-245）：恢复 / 批量恢复 / 彻底删除 / 清空回收站。
 * 全部走既有 Bearer 鉴权（{@code ApiTokenFilter} 已覆盖 /api/v1，**不新增豁免**）；
 * 权限 = 任务归属本人（service 层 user_id 条件）。配额全程不退不扣（D-115/D-245）。
 * 错误口径：不存在 / 非本人 / 不在回收站 → 404 {@code TASK_NOT_FOUND}（与详情一致）。
 * 不放 {@link TaskController}（已顶 M-3 300 行）——先例同 TaskListController / TaskDeleteController。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskTrashController {

    /** 批量恢复逐条结果（口径同批量删除：reason 只取固定值 NOT_FOUND，成功为 null）。 */
    public record RestoreItemResult(long id, boolean restored, String reason) {
    }

    private final TaskTrashService taskTrashService;

    public TaskTrashController(TaskTrashService taskTrashService) {
        this.taskTrashService = taskTrashService;
    }

    /** 恢复单条（软删 → 正常）：清空 deleted_at，内容与对象原样保留。 */
    @PostMapping("/tasks/{id}/restore")
    public Map<String, Object> restore(@PathVariable("id") long id) {
        taskTrashService.restore(id);
        return Map.of("restored", true);
    }

    /** 批量恢复（1..100、去重保序、逐条部分成功，口径同批量删除）。 */
    @PostMapping(value = "/tasks/batch-restore", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> restoreBatch(@RequestBody(required = false) RetryRequest body) {
        List<Long> ids = body == null ? List.of() : body.ids();
        List<Long> restored;
        try {
            restored = taskTrashService.restoreBatch(ids);
        } catch (ApiException e) {
            throw e; // 400 INVALID_PARAM 原样透出（口径同批量删除）
        }
        Set<Long> ok = new HashSet<>(restored);
        List<RestoreItemResult> results = new ArrayList<>();
        List<Long> distinct = ids == null ? List.of() : new ArrayList<>(new java.util.LinkedHashSet<>(ids));
        for (Long id : distinct) {
            boolean success = id != null && id > 0 && ok.contains(id);
            results.add(new RestoreItemResult(id == null ? -1L : id, success, success ? null : "NOT_FOUND"));
        }
        return Map.of("results", results);
    }

    /** 彻底删除单条（物理删 4 表 + best-effort 删对象）；不在回收站 → 404。 */
    @DeleteMapping("/tasks/{id}/purge")
    public Map<String, Object> purge(@PathVariable("id") long id) {
        if (!taskTrashService.purge(id)) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务 " + id + " 不存在或不在回收站");
        }
        return Map.of("purged", true);
    }

    /** 清空当前用户全部回收站（逐条彻底删除）。@return 彻底删除条数。 */
    @PostMapping("/tasks/purge-all")
    public Map<String, Object> purgeAll() {
        UserContext.current(); // 与其它端点一致：未认证 401 由 ApiTokenFilter 横切，这里显式取当前用户
        return Map.of("purged", taskTrashService.purgeAll());
    }
}
