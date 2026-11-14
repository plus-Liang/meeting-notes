package com.meetingnotes.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.7：导出 Markdown 三节固定顺序、done 勾选、owner/deadline 只在非 null 时追加、空值写（无）。
 */
class TaskMarkdownExporterTest {

    @Test
    void threeSectionsInFixedOrder() {
        String md = TaskMarkdownExporter.export("摘要正文", List.of("关键词A", "关键词B"),
                List.of(new TodoItemView("整理材料", "张三", "下周三", true)));

        assertTrue(md.startsWith("## 摘要\n"), "第一节必须是摘要：" + md);
        assertTrue(md.contains("## 关键词\n"), "第二节关键词");
        assertTrue(md.contains("## 待办\n"), "第三节待办");
        assertTrue(md.indexOf("## 摘要") < md.indexOf("## 关键词")
                && md.indexOf("## 关键词") < md.indexOf("## 待办"), "节顺序固定");
    }

    @Test
    void todoShowsDoneCheckboxAndOptionalOwnerDeadline() {
        String md = TaskMarkdownExporter.export("s", List.of("k"), List.of(
                new TodoItemView("已完成的事", "张三", "下周三", true),
                new TodoItemView("未完成且无主", null, null, false)));

        assertTrue(md.contains("- [x] 已完成的事 （owner: 张三 / deadline: 下周三）"), md);
        assertTrue(md.contains("- [ ] 未完成且无主"), "owner/deadline 全 null 时只留 action，实际：" + md);
        assertFalse(md.contains("- [ ] 未完成且无主 （"), "null 字段不许输出空括号");
    }

    @Test
    void nullFieldsRenderAsNone() {
        String md = TaskMarkdownExporter.export(null, null, null);

        assertTrue(md.contains("## 摘要\n（无）"));
        assertTrue(md.contains("## 关键词\n（无）"));
        assertTrue(md.contains("## 待办\n（无）"));
    }

    @Test
    void emptyKeywordsJoinWithComma() {
        String md = TaskMarkdownExporter.export("s", List.of("a", "b", "c"), List.of());

        assertTrue(md.contains("## 关键词\na, b, c"), md);
        assertTrue(md.contains("## 待办\n（无）"));
    }
}
