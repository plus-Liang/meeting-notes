package com.meetingnotes.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import com.meetingnotes.task.TaskMetaService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务标签/收藏端点（T4.29 / D-137，从 TaskController 独立成类——原类已超 M3 300 行上限）：
 * <ul>
 *   <li>{@code PATCH /api/v1/tasks/{id}/meta}：body 可只带一个键——{@code labels}（字符串数组，
 *       ≤10 个、每个 trim 后 1–20 字、去重保序；非数组/超限/非字符串 → 400 {@code INVALID_PARAM}）、
 *       {@code favorite}（布尔；非布尔 → 400）；两键都缺 → 400。返回 {task_id, labels, favorite}；
 *       404 口径同删除端点；</li>
 *   <li>{@code GET /api/v1/tasks/{id}/meta}：读回当前 meta（详情页标签编辑初值；详情 14 键不动，
 *       这是一条新的只增端点）。</li>
 * </ul>
 * 列表筛选（label=/favorite=）在 {@link TaskController#list}。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskMetaController {


    /** 卡口径：最多 10 个标签。 */
    static final int MAX_LABELS = 10;

    /** 卡口径：每个标签 trim 后 1–20 字。 */
    static final int MAX_LABEL_LEN = 20;

    private final TaskMetaService taskMetaService;

    public TaskMetaController(TaskMetaService taskMetaService) {
        this.taskMetaService = taskMetaService;
    }

    @PatchMapping(value = "/tasks/{id}/meta", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TaskMetaService.TaskMeta update(@PathVariable("id") long id,
                                           @RequestBody(required = false) JsonNode body) {
        if (body == null || body.isNull() || (!body.has("labels") && !body.has("favorite"))) {
            throw new ApiException(400, "INVALID_PARAM", "至少提供 labels 或 favorite 之一");
        }
        List<String> labels = null;
        Boolean favorite = null;
        if (body.has("labels")) {
            labels = parseLabels(body.get("labels"));
        }
        if (body.has("favorite")) {
            JsonNode node = body.get("favorite");
            if (!node.isBoolean()) {
                throw new ApiException(400, "INVALID_PARAM", "favorite 必须是布尔值 true/false");
            }
            favorite = node.asBoolean();
        }
        return taskMetaService.update(UserContext.current(), id, labels, favorite);
    }

    @GetMapping("/tasks/{id}/meta")
    public TaskMetaService.TaskMeta read(@PathVariable("id") long id) {
        return taskMetaService.read(UserContext.current(), id);
    }

    /** labels 校验：数组、≤10、元素为字符串、trim 后 1–20 字、去重保序。 */
    static List<String> parseLabels(JsonNode node) {
        if (!node.isArray()) {
            throw new ApiException(400, "INVALID_PARAM", "labels 必须是字符串数组");
        }
        if (node.size() > MAX_LABELS) {
            throw new ApiException(400, "INVALID_PARAM", "labels 最多 " + MAX_LABELS + " 个");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new ApiException(400, "INVALID_PARAM", "labels 元素必须是字符串");
            }
            String label = item.asText().trim();
            if (label.isEmpty() || label.length() > MAX_LABEL_LEN) {
                throw new ApiException(400, "INVALID_PARAM",
                        "每个标签 trim 后须 1–" + MAX_LABEL_LEN + " 字");
            }
            if (!out.contains(label)) {
                out.add(label); /* 去重保序 */
            }
        }
        return out;
    }
}
