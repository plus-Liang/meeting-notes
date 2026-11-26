package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import com.meetingnotes.task.TaskDateWindow;
import com.meetingnotes.task.TaskListSupport;
import com.meetingnotes.task.TaskQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务列表端点（T2.10 / D-053；T4.29 起从 {@link TaskController} 独立成类——原类顶 M3 300 行上限，
 * 先例同 TaskCountsController / TaskExportController）：
 * {@code GET /api/v1/tasks}——keyset 分页（before_id 游标禁 OFFSET）、user_id 服务端过滤（D-040）、
 * limit 1..50、status 白名单、T4.24 可选 q（去空格/超长 400/溢出按关键词）、
 * T4.29 可选 label（jsonb 包含匹配）/favorite（true|false），参数化并与 status/q 可组合，分页语义不变。
 * T4.42：label 支持多值（重复参数 → OR 并集，同维度并集/跨维度交集，D-162）、增可选 created_after/created_before
 * （{@code YYYY-MM-DD}，非法/颠倒 → 400，解析见 {@link TaskDateWindow#resolve}），
 * 响应末尾增 total（同筛选 COUNT）。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskListController {


    private final TaskQueryService taskQueryService;

    public TaskListController(TaskQueryService taskQueryService) {
        this.taskQueryService = taskQueryService;
    }

    /** 服务端任务列表（T2.10 / D-053）：keyset 分页（before_id 游标禁 OFFSET）、user_id 服务端过滤（D-040）；
     *  limit 1..50、status 白名单、T4.24 只增可选 q（去空格/超长 400，见 TaskListSupport.normalizeQ）；非法 → 400。
     *  T4.42：label 多值（重复参数，trim 后去空）；created_after/created_before 校验在
     *  TaskDateWindow.resolve（格式/顺序 → 400 INVALID_PARAM）。
     *  T4.96/C5：只增可选 trash——true = 回收站列表（deleted_at IS NOT NULL），false/空 = 默认列表
     *  （回收站任务不可见，D-245）；其它值 → 400 INVALID_PARAM（口径同 favorite）。 */
    @GetMapping("/tasks")
    public TaskQueryService.TaskListResponse list(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String status,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String limit,
            @org.springframework.web.bind.annotation.RequestParam(name = "before_id", required = false) String beforeId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String q,
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.util.List<String> label,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String favorite,
            @org.springframework.web.bind.annotation.RequestParam(name = "created_after", required = false) String createdAfter,
            @org.springframework.web.bind.annotation.RequestParam(name = "created_before", required = false) String createdBefore,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String trash) {
        int lim = TaskQueryService.DEFAULT_LIMIT;
        if (limit != null) {
            try {
                lim = Integer.parseInt(limit.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(400, "INVALID_PARAM", "limit 必须是数字");
            }
            if (lim < 1 || lim > TaskQueryService.MAX_LIMIT) {
                throw new ApiException(400, "INVALID_PARAM",
                        "limit 取值范围 1.." + TaskQueryService.MAX_LIMIT);
            }
        }
        Long before = null;
        if (beforeId != null && !beforeId.isBlank()) {
            try {
                before = Long.parseLong(beforeId.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(400, "INVALID_PARAM", "before_id 必须是数字");
            }
        }
        String statusFilter = null;
        if (status != null && !status.isBlank()) {
            statusFilter = status.trim();
            if (!TaskController.ALLOWED_STATUS_FILTER.contains(statusFilter)) {
                throw new ApiException(400, "INVALID_PARAM",
                        "status 只允许 PENDING/RUNNING/RETRYING/DONE/DEAD");
            }
        }
        /* T4.29：favorite 只允许 true/false（其它值 400，不放给 boolean 转换器避免非 JSON 错误格式） */
        Boolean favoriteFilter = null;
        if (favorite != null && !favorite.isBlank()) {
            String value = favorite.trim();
            if (!"true".equals(value) && !"false".equals(value)) {
                throw new ApiException(400, "INVALID_PARAM", "favorite 只允许 true/false");
            }
            favoriteFilter = Boolean.parseBoolean(value);
        }
        /* T4.42：多标签——重复 label 参数收集为列表（trim 去空去 null；OR 并集语义，D-162，单值行为不变） */
        java.util.List<String> labelFilters = label == null ? java.util.List.of()
                : label.stream().filter(java.util.Objects::nonNull).map(String::trim)
                        .filter(s -> !s.isEmpty()).toList();
        /* T4.96/C5：trash 只允许 true/false/空（其它 400，口径同 favorite） */
        boolean trashFilter = false;
        if (trash != null && !trash.isBlank()) {
            String value = trash.trim();
            if (!"true".equals(value) && !"false".equals(value)) {
                throw new ApiException(400, "INVALID_PARAM", "trash 只允许 true/false");
            }
            trashFilter = Boolean.parseBoolean(value);
        }
        return taskQueryService.list(UserContext.current(), statusFilter, lim, before,
                TaskListSupport.normalizeQ(q), labelFilters, favoriteFilter,
                TaskDateWindow.resolve(createdAfter, createdBefore), trashFilter);
    }
}
