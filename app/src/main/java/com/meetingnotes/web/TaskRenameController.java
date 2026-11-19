package com.meetingnotes.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import com.meetingnotes.task.TaskRenameService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务重命名端点（T4.43 / D-160，从 TaskController 独立成类——原类已贴 M3 300 行上限，
 * 先例同 TaskMetaController）：{@code PATCH /api/v1/tasks/{id}}，body {@code {"display_name":"…"}}。
 *
 * <ul>
 *   <li>校验矩阵：缺字段/body 空 → 400；非字符串 → 400；trim 后 1..{@value TaskRenameService#MAX_DISPLAY_NAME_LEN} 字，
 *       超长 → 400 {@code INVALID_PARAM}；**trim 后空串 = 清除**（落 NULL，与「从未命名」同语义）；</li>
 *   <li>404 口径同删除/详情端点（不存在/**非本人**统一 404 TASK_NOT_FOUND，防探测，D-040）；</li>
 *   <li>只写 {@code display_name} + {@code updated_at}（不动原文件名与溯源）；</li>
 *   <li>返回 {@code {task_id, display_name}}（清除时 display_name 为 null）。</li>
 * </ul>
 * 列表/详情/分享/导出对 display_name 的只增返回见 TaskQueryService / TaskStatusResponse /
 * ShareController / TaskExportController。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskRenameController {

    private final TaskRenameService taskRenameService;

    public TaskRenameController(TaskRenameService taskRenameService) {
        this.taskRenameService = taskRenameService;
    }

    @PatchMapping(value = "/tasks/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TaskRenameService.RenameResult rename(@PathVariable("id") long id,
                                                 @RequestBody(required = false) JsonNode body) {
        if (body == null || body.isNull() || !body.has("display_name")) {
            throw new ApiException(400, "INVALID_PARAM", "请求体必须包含字符串字段 display_name");
        }
        JsonNode node = body.get("display_name");
        if (!node.isTextual()) {
            throw new ApiException(400, "INVALID_PARAM", "display_name 必须是字符串");
        }
        String trimmed = node.asText().trim();
        if (trimmed.length() > TaskRenameService.MAX_DISPLAY_NAME_LEN) {
            throw new ApiException(400, "INVALID_PARAM",
                    "display_name trim 后最长 " + TaskRenameService.MAX_DISPLAY_NAME_LEN + " 字");
        }
        return taskRenameService.rename(UserContext.current(), id, trimmed.isEmpty() ? null : trimmed);
    }
}
