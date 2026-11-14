package com.meetingnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 待办抽取（T3.2 / D-069）：转写全文 → {todos:[{action,owner,deadline}]}。
 *
 * <p>同步调用（任务置 DONE 前执行），但**任何失败都不阻塞任务**：无 ChatModel（未配 key）、
 * 调用异常/超时、JSON 解析失败 → WARN + todos 保持 null，任务仍 DONE（转写成果不能因待办丢失）。
 * 解析失败不重试；WARN 带模型原始输出截 200 字（不进响应体）。
 * 宁缺毋滥：owner/deadline 未知必须 null 严禁编造；action 为空的条目直接丢弃。
 */
@Service
public class TodoService {

    private static final Logger log = LoggerFactory.getLogger(TodoService.class);

    private final ObjectProvider<ChatModel> chatModel;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public TodoService(ObjectProvider<ChatModel> chatModel, JdbcTemplate jdbc) {
        this.chatModel = chatModel;
        this.jdbc = jdbc;
    }

    /** 对外契约：元素字段 snake_case（action/owner/deadline），owner/deadline 未知为 null。 */
    public record TodoItem(String action, String owner, String deadline) {
    }

    /** 提示词（package-private 供单测断言约束完整）。 */
    static String buildPrompt(String transcript) {
        return """
                你是会议纪要助手。请从下面的转写全文中抽取明确的待办事项（行动项），输出严格的 JSON（不要输出任何其它文字或代码块标记）：
                {"todos":[{"action":"要做的具体事项","owner":"负责人","deadline":"截止时间"}]}
                要求：最多抽取 5 条最明确的待办（按重要性排序），没有就输出 {"todos":[]}；
                action 必须来自 transcript 中明确提到的待办，用不超过 40 字的短语，不得编造或引申；
                owner/deadline 只有 transcript 明确说出时才填，否则必须为 null，严禁猜测。

                转写全文：
                """ + transcript;
    }

    /**
     * @return true = todos 已写入 task_result；false = 跳过或失败（todos 保持 null，任务不受影响）
     */
    public boolean extract(long taskId, String transcript) {
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            log.warn("未配置 LLM（无 ChatModel bean），跳过待办抽取（任务仍 DONE）");
            return false;
        }
        if (transcript == null || transcript.isBlank()) {
            log.info("transcript 为空（D-048 无语音内容），跳过待办抽取");
            return false;
        }
        String raw;
        try {
            raw = model.call(buildPrompt(transcript));
        } catch (RuntimeException e) {
            log.warn("LLM 调用失败/超时，待办留空（任务仍 DONE）：{}", e.getMessage());
            return false;
        }
        List<TodoItem> todos;
        try {
            todos = parseTodos(raw);
        } catch (Exception e) {
            // 解析失败：不重试（D-068），WARN 带原始输出截 200 字便于排查格式漂移，todos 保持 null
            log.warn("LLM 输出不是合法 JSON，待办留空；原始输出截 200 字：{}",
                    raw == null ? "null" : raw.substring(0, Math.min(200, raw.length())));
            return false;
        }
        jdbc.update("UPDATE task_result SET todos = ?::jsonb WHERE task_id = ?", toJson(todos), taskId);
        log.info("待办已落库（{} 条）", todos.size());
        return true;
    }

    /** 只收 action 非空的条目（宁缺毋滥）；owner/deadline 空白归一化为 null。非数组/非法 JSON 直接抛给上层降级。 */
    private List<TodoItem> parseTodos(String raw) throws Exception {
        JsonNode node = mapper.readTree(raw);
        if (!node.path("todos").isArray()) {
            throw new IllegalArgumentException("缺少 todos 数组");
        }
        List<TodoItem> todos = new ArrayList<>();
        for (JsonNode item : node.path("todos")) {
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String toJson(List<TodoItem> todos) {
        try {
            return mapper.writeValueAsString(todos);
        } catch (Exception e) {
            return "[]";
        }
    }
}
