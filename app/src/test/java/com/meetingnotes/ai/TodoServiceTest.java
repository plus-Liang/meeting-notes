package com.meetingnotes.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.3 / F-5：待办解析口径——提示词"最多 20 条"（模型不遵守，实测 21 条）、
 * 代码层截断 20 条、空 action 丢弃、owner/deadline 缺失归一化为 null、缺 todos 数组按解析失败处理。
 *
 * <p>T3.3 起 {@link TodoService} 不再自己发起 LLM 调用（摘要+关键词+待办已合并成一次调用，F-7），
 * 只负责"解析一次 JSON 里的 todos"；"不重试/只调用一次"的可证伪断言在
 * {@link StructuringServiceTest}（{@code verify(model, times(1)).call(...)}）。
 */
class TodoServiceTest {

    private final TodoService service = new TodoService();
    private final ObjectMapper mapper = new ObjectMapper();

    private List<TodoService.TodoItem> parse(String json) throws Exception {
        return service.parse(mapper.readTree(json));
    }

    @Test
    void promptSectionRequiresStrictJsonNoGuessAndAtMost20() {
        String prompt = TodoService.promptSection();

        assertTrue(prompt.contains("todos"), "输出形状必须是 todos 数组");
        assertTrue(prompt.contains("最多 20 条"), "F-5：口径必须是 20 条（旧文案'最多 5 条'与实现不符）");
        assertTrue(prompt.contains("严禁猜测"), "owner/deadline 未知必须为 null，不许猜");
        assertTrue(prompt.contains("不得编造或引申"), "action 必须有 transcript 依据");
        assertTrue(prompt.contains("不要换算成具体日期"), "D-076：相对时间词保留原词");
        /* T4.16b/D-107：待办语义收紧——讲者演示动作/举例/知识点罗列/流程讲解不算待办 */
        assertTrue(prompt.contains("明确要求听众/参会者去做的行动项"), "必须只列听众的行动项");
        assertTrue(prompt.contains("演示动作"), "演示动作不得算待办（s2 实测误判：登录账号给观众看）");
        assertTrue(prompt.contains("宁少不滥"), "教程类 0-8 条，宁少不滥");
    }

    @Test
    void validTodosJsonParsesAllFields() throws Exception {
        List<TodoService.TodoItem> todos = parse(
                "{\"todos\":[{\"action\":\"整理会议纪要\",\"owner\":\"张三\",\"deadline\":\"周五\"}]}");

        assertEquals(1, todos.size());
        assertEquals(new TodoService.TodoItem("整理会议纪要", "张三", "周五"), todos.get(0));
    }

    @Test
    void missingOwnerDeadlineToleratedAsNull() throws Exception {
        List<TodoService.TodoItem> todos = parse("{\"todos\":[{\"action\":\"发周报\"}]}");

        assertEquals(new TodoService.TodoItem("发周报", null, null), todos.get(0));
    }

    @Test
    void blankActionEntriesDroppedRatherThanGuessed() throws Exception {
        List<TodoService.TodoItem> todos = parse(
                "{\"todos\":[{\"action\":\"\",\"owner\":\"张三\"},{\"action\":\"发周报\"}]}");

        assertEquals(1, todos.size(), "空 action 的条目丢弃");
        assertEquals("发周报", todos.get(0).action());
    }

    @Test
    void emptyTodosArrayIsValidBusinessResult() throws Exception {
        assertTrue(parse("{\"todos\":[]}").isEmpty(), "合法的空 todos 是正常结果（[] 而不是解析失败）");
    }

    @Test
    void todosTruncatedTo20InCode() throws Exception {
        StringBuilder json = new StringBuilder("{\"todos\":[");
        for (int i = 1; i <= 25; i++) {
            json.append(i == 1 ? "" : ",").append("{\"action\":\"事项").append(i).append("\"}");
        }
        json.append("]}");

        List<TodoService.TodoItem> todos = parse(json.toString());

        assertEquals(TodoService.MAX_TODOS, todos.size(), "F-5：代码层必须截断到 20 条（模型会输出 21+ 条）");
        assertEquals("事项1", todos.get(0).action());
        assertEquals("事项20", todos.get(19).action());
    }

    @Test
    void missingTodosArrayTreatedAsParseFailure() {
        assertThrows(IllegalArgumentException.class, () -> service.parse(mapper.readTree("{\"summary\":\"形状不对\"}")),
                "缺 todos 数组 = 解析失败（上层三字段一起留空并降级）");
    }
}
