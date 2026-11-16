package com.meetingnotes.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.7：导出 Markdown 四节固定顺序、done 勾选、owner/deadline 只在非 null 时追加、空值写（无）。
 * T4.17：新增「## 要点」节（在关键词之后、待办之前）；keypoints 为 null/空 → **整节省略**（口径稳定）。
 */
class TaskMarkdownExporterTest {

    @Test
    void fourSectionsInFixedOrder() {
        String md = TaskMarkdownExporter.export("摘要正文", List.of("关键词A", "关键词B"),
                List.of(new KeypointView("市场分化", "传统岗位收缩，基础开发编制缩减。")),
                List.of(new TodoItemView("整理材料", "张三", "下周三", true)));

        assertTrue(md.startsWith("## 摘要\n"), "第一节必须是摘要：" + md);
        assertTrue(md.contains("## 关键词\n"), "第二节关键词");
        assertTrue(md.contains("## 要点\n"), "第三节要点（T4.17）");
        assertTrue(md.contains("## 待办\n"), "第四节待办");
        assertTrue(md.indexOf("## 摘要") < md.indexOf("## 关键词")
                && md.indexOf("## 关键词") < md.indexOf("## 要点")
                && md.indexOf("## 要点") < md.indexOf("## 待办"), "节顺序固定");
    }

    /** T4.17：要点行格式 = 编号 + 加粗小标题 + ：+ 说明（D-109 示例格式）。 */
    @Test
    void keypointsRenderAsNumberedBoldTitleLines() {
        String md = TaskMarkdownExporter.export("s", List.of("k"),
                List.of(new KeypointView("市场分化", "传统 Java 岗收缩，大厂基础开发编制缩减。"),
                        new KeypointView("行动建议", "与会人员下周三前整理完材料。")),
                List.of());

        assertTrue(md.contains("## 要点\n1. **市场分化**：传统 Java 岗收缩，大厂基础开发编制缩减。\n"
                + "2. **行动建议**：与会人员下周三前整理完材料。\n"), md);
    }

    /** T4.17 口径（保持稳定）：keypoints 为 null 或空 → 整节省略，不输出「（无）」占位。 */
    @Test
    void nullOrEmptyKeypointsOmitTheWholeSection() {
        String withNull = TaskMarkdownExporter.export("s", List.of("k"), null,
                List.of(new TodoItemView("做点事", null, null, false)));
        String withEmpty = TaskMarkdownExporter.export("s", List.of("k"), List.of(), List.of());

        assertFalse(withNull.contains("## 要点"), "null → 省略要点节：" + withNull);
        assertFalse(withEmpty.contains("## 要点"), "空列表 → 省略要点节：" + withEmpty);
        assertTrue(withNull.contains("## 关键词") && withNull.contains("## 待办"),
                "其余两节不受影响");
    }

    @Test
    void todoShowsDoneCheckboxAndOptionalOwnerDeadline() {
        String md = TaskMarkdownExporter.export("s", List.of("k"), null, List.of(
                new TodoItemView("已完成的事", "张三", "下周三", true),
                new TodoItemView("未完成且无主", null, null, false)));

        assertTrue(md.contains("- [x] 已完成的事 （owner: 张三 / deadline: 下周三）"), md);
        assertTrue(md.contains("- [ ] 未完成且无主"), "owner/deadline 全 null 时只留 action，实际：" + md);
        assertFalse(md.contains("- [ ] 未完成且无主 （"), "null 字段不许输出空括号");
    }

    @Test
    void nullFieldsRenderAsNone() {
        String md = TaskMarkdownExporter.export(null, null, null, null);

        assertTrue(md.contains("## 摘要\n（无）"));
        assertTrue(md.contains("## 关键词\n（无）"));
        assertTrue(md.contains("## 待办\n（无）"));
        assertFalse(md.contains("## 要点"), "keypoints null → 整节省略（T4.17）");
    }

    @Test
    void emptyKeywordsJoinWithComma() {
        String md = TaskMarkdownExporter.export("s", List.of("a", "b", "c"), null, List.of());

        assertTrue(md.contains("## 关键词\na, b, c"), md);
        assertTrue(md.contains("## 待办\n（无）"));
    }
}
