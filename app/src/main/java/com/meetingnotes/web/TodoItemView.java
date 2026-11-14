package com.meetingnotes.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 对外待办视图（T3.5）：在 AI 存储元素 {action,owner,deadline} 之上**只增** `done`（勾选状态）。
 *
 * <p>存储与展示分离：{@code ai/TodoService.TodoItem}（record，3 组件）**一个字不动**——AI 落库的
 * jsonb 形状不变，T3.3/T3.4 的落库断言零风险；本视图只在 web 出口把 `done` 恒定补齐
 * （老数据 jsonb 无 `done` 字段 → false）。todos 的读写一律走 JsonNode（读 → 映射视图；
 * 写 → 改 JsonNode 再序列化），不经过 TodoItem。
 */
public record TodoItemView(String action, String owner, String deadline, boolean done) {

    /** 从 jsonb 元素映射视图；缺失字段按 null / false 读（老数据兼容）。 */
    public static TodoItemView from(JsonNode node) {
        return new TodoItemView(
                node.path("action").asText(null),
                node.path("owner").asText(null),
                node.path("deadline").asText(null),
                node.path("done").asBoolean(false));
    }

    /** jsonb 数组 → 视图列表（调用方保证 node 为数组）。 */
    public static List<TodoItemView> listFrom(JsonNode array) {
        List<TodoItemView> out = new ArrayList<>();
        for (JsonNode item : array) {
            out.add(from(item));
        }
        return out;
    }
}
