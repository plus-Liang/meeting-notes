package com.meetingnotes.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.3 ⑨ / D-076：待办依据校验（三档统一）——编造的 owner/deadline 必须被置 null；
 * 具体日期原文没有该串 → null；相对时间词保留原词（不许换算成绝对日期）；action 无依据整条丢弃。
 *
 * <p>实测背景：本地 qwen2.5:1.5b 把原文"下周三"输出成 2023-04-15、"下个月初"输出成 2023-06-01——
 * 提示词的"严禁猜测"保证不了，只能靠代码层兜。
 */
class TodoGroundingTest {

    @Test
    void fabricatedAbsoluteDeadlineBecomesNull() {
        String transcript = "我们下周三再讨论这个问题。";

        List<TodoService.TodoItem> kept = TodoGrounding.validate(List.of(
                new TodoService.TodoItem("再讨论这个问题", null, "2023-04-15")), transcript);

        assertEquals(1, kept.size());
        assertNull(kept.get(0).deadline(), "原文没有 2023-04-15 这个串 → 必须置 null（模型会瞎算）");
    }

    @Test
    void fabricatedOwnerBecomesNull() {
        String transcript = "这次整理一下材料就行。";

        List<TodoService.TodoItem> kept = TodoGrounding.validate(List.of(
                new TodoService.TodoItem("整理材料", "李四", null)), transcript);

        assertEquals(1, kept.size());
        assertNull(kept.get(0).owner(), "原文没出现'李四' → owner 必须置 null");
    }

    @Test
    void groundedOwnerAndDeadlineAreKept() {
        String transcript = "由张三负责整理材料，下周三截止。";

        List<TodoService.TodoItem> kept = TodoGrounding.validate(List.of(
                new TodoService.TodoItem("整理材料", "张三", "下周三")), transcript);

        assertEquals(1, kept.size());
        assertEquals("张三", kept.get(0).owner());
        assertEquals("下周三", kept.get(0).deadline(), "D-076：相对时间词保留原词，绝不换算成绝对日期");
    }

    @Test
    void relativeDeadlineIsNeverConvertedToAbsoluteDate() {
        String transcript = "下个月初做一次复盘。";

        List<TodoService.TodoItem> kept = TodoGrounding.validate(List.of(
                new TodoService.TodoItem("做一次复盘", null, "下个月初")), transcript);

        assertEquals("下个月初", kept.get(0).deadline(), "原文说'下个月初'，答案也只能是'下个月初'");
    }

    @Test
    void ungroundedActionDropsWholeTodo() {
        String transcript = "今天讨论了排期问题。";

        List<TodoService.TodoItem> kept = TodoGrounding.validate(List.of(
                new TodoService.TodoItem("采购服务器并扩容数据库", "张三", null)), transcript);

        assertTrue(kept.isEmpty(), "action 核心词在原文里命中率 < 0.5 → 整条丢弃（不许凭空造待办）");
    }

    @Test
    void paraphrasedActionWithHalfBigramHitsIsKept() {
        // "再讨论纪要" 与原文"我们下周三再讨论"的二元组命中 2/4 = 0.5（允许概括的边界）
        String transcript = "我们下周三再讨论";

        List<TodoService.TodoItem> kept = TodoGrounding.validate(List.of(
                new TodoService.TodoItem("再讨论纪要", null, null)), transcript);

        assertEquals(1, kept.size(), "核心词有依据（≥0.5）时允许概括，不许过度杀");
    }

    @Test
    void nullAndEmptyInputsAreSafe() {
        assertTrue(TodoGrounding.validate(null, "任意文本").isEmpty());
        assertTrue(TodoGrounding.validate(List.of(), "任意文本").isEmpty());
        assertEquals("", TodoGrounding.normalize(null));
    }
}
