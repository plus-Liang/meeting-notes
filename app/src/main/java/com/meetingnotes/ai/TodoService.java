package com.meetingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 待办解析（T3.2 → T3.3 收编）：合并调用里 {@code todos} 一节的提示词口径、解析、截断。
 *
 * <p><b>F-7 后不再自己发起 LLM 调用</b>：摘要 + 关键词 + 待办已合并成**一次**调用（见 {@link StructuringService}），
 * 本类只负责"把一次 JSON 里的 todos 解析成 {@link TodoItem}"，避免两套提示词/解析口径漂移。
 *
 * <p>宁缺毋滥：owner/deadline 未知必须 null 严禁编造；action 为空的条目不进入结果。
 * F-5：提示词口径"最多 20 条"，并在解析后**代码层截断 20 条**（防 JSON 过长被 finish_reason=length 截断）。
 * 依据校验（D-076，owner/deadline/action 必须回原文）由 {@link TodoGrounding} 统一做。
 */
@Service
public class TodoService {

    /** F-5：代码层硬截断上限（与提示词口径一致）。 */
    static final int MAX_TODOS = 20;

    /** 对外契约：元素字段 snake_case（action/owner/deadline），owner/deadline 未知为 null。 */
    public record TodoItem(String action, String owner, String deadline) {
    }

    /** 合并提示词里 todos 一节的约束（package-private 供单测断言口径）。 */
    static String promptSection() {
        return """
                3. todos 是最多 20 条最明确的待办（按重要性排序），没有就输出 {"todos":[]}；
                   action 必须来自 transcript 中明确提到的待办，用不超过 40 字的短语，不得编造或引申；
                   owner/deadline 只有 transcript 明确说出时才填，否则必须为 null，严禁猜测；
                   deadline 保留原文说法（如"下周三"），不要换算成具体日期。
                   只列 transcript 里明确要求听众/参会者去做的行动项；讲者/主播自己的演示动作
                   （如"我登录一下账号给你看"）、举例、知识点罗列、流程讲解都不算待办；
                   教程/课程类通常 0-8 条，宁少不滥；确实没有就输出 {"todos":[]}。""";
    }

    /**
     * 解析合并 JSON 的 {@code todos} 数组（F-5：截断 20 条）。
     *
     * @throws IllegalArgumentException 缺 todos 字段或不是数组——由上层按"解析失败 → 三字段一起留空"处理
     */
    public List<TodoItem> parse(JsonNode root) {
        if (root == null || !root.path("todos").isArray()) {
            throw new IllegalArgumentException("缺少 todos 数组");
        }
        List<TodoItem> todos = new ArrayList<>();
        for (JsonNode item : root.path("todos")) {
            if (todos.size() >= MAX_TODOS) {
                break;
            }
            String action = item.path("action").asText(null);
            if (action == null || action.isBlank()) {
                continue;
            }
            todos.add(new TodoItem(action.trim(),
                    blankToNull(item.path("owner").asText(null)),
                    blankToNull(item.path("deadline").asText(null))));
        }
        return todos;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
