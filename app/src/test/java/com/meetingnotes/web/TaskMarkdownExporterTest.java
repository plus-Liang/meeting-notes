package com.meetingnotes.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.7 / T4.17 / T4.18：导出 Markdown 节顺序固定（摘要/关键词/要点/转写/待办）、include 选择集
 * **只输出被选中的节**、done 勾选、owner/deadline 只在非 null 时追加、空值写（无）。
 * 要点节口径（T4.17）：keypoints null/空 → 整节省略。
 */
class TaskMarkdownExporterTest {

    private static final Set<String> ALL = Set.of("summary", "keywords", "keypoints", "transcript", "todos");

    @Test
    void allSectionsInFixedOrder() {
        String md = TaskMarkdownExporter.export(ALL, "摘要正文", List.of("关键词A", "关键词B"),
                List.of(new KeypointView("市场分化", "传统岗位收缩，基础开发编制缩减。")),
                "第一句。第二句！\n第三句？",
                List.of(new TodoItemView("整理材料", "张三", "下周三", true)));

        assertTrue(md.startsWith("## 摘要\n"), "第一节必须是摘要：" + md);
        assertTrue(md.contains("## 关键词\n"), "第二节关键词");
        assertTrue(md.contains("## 要点\n"), "第三节要点（T4.17）");
        assertTrue(md.contains("## 转写\n"), "第四节转写（T4.18）");
        assertTrue(md.contains("## 待办\n"), "第五节待办");
        assertTrue(md.indexOf("## 摘要") < md.indexOf("## 关键词")
                && md.indexOf("## 关键词") < md.indexOf("## 要点")
                && md.indexOf("## 要点") < md.indexOf("## 转写")
                && md.indexOf("## 转写") < md.indexOf("## 待办"), "节顺序固定");
    }

    /** T4.18：include 只输出被选中的节——只选 transcript → 整个 md 只有转写一节。 */
    @Test
    void includeSelectionOutputsOnlySelectedSections() {
        String md = TaskMarkdownExporter.export(Set.of("transcript"), null, null, null,
                "一句一行。\n两句话。", null);

        assertEquals("## 转写\n一句一行。\n两句话。\n", md);
        assertFalse(md.contains("## 摘要") || md.contains("## 关键词") || md.contains("## 待办"),
                "未选中的节不得出现：" + md);
    }

    /** T4.18：include 选择集只输出被选中的节——显式四节（不含待办）→ 无待办节，其余照常。
     * T4.102：默认集已含待办（观察期结束），「不含待办」只发生在使用方显式选择时。 */
    @Test
    void explicitSelectionWithoutTodosOmitsTodosSection() {
        String md = TaskMarkdownExporter.export(Set.of("summary", "keywords", "keypoints", "transcript"),
                "s", List.of("k"), List.of(new KeypointView("标题", "说明。")),
                "转写内容。", List.of(new TodoItemView("做点事", null, null, false)));

        assertFalse(md.contains("## 待办"), "未选待办不得输出该节：" + md);
        assertTrue(md.contains("## 摘要") && md.contains("## 关键词")
                && md.contains("## 要点") && md.contains("## 转写"), md);
    }

    /** T4.102/C7-2：显式 include=summary → 只有摘要一节，没有待办节。 */
    @Test
    void includeSummaryOnlyHasNoTodosSection() {
        String md = TaskMarkdownExporter.export(Set.of("summary"),
                "摘要正文", null, null, "转写。", List.of(new TodoItemView("做点事", null, null, false)));

        assertTrue(md.startsWith("## 摘要\n"), md);
        assertFalse(md.contains("## 待办"), "include=summary 不得输出待办节：" + md);
        assertFalse(md.contains("## 关键词") || md.contains("## 转写"), "其余节不得出现：" + md);
    }

    /** T4.18：转写节内容原样注入（一句一行由调用方 transcriptForExport 完成）。 */
    @Test
    void transcriptSectionWritesGivenTextVerbatim() {
        String md = TaskMarkdownExporter.export(Set.of("transcript"), null, null, null,
                "第一句。\n第二句？", null);
        assertTrue(md.contains("第一句。\n第二句？"), md);
    }

    /** T4.17：要点行格式 = 编号 + 加粗小标题 + ：+ 说明。 */
    @Test
    void keypointsRenderAsNumberedBoldTitleLines() {
        String md = TaskMarkdownExporter.export(Set.of("keypoints"), null, null,
                List.of(new KeypointView("市场分化", "传统 Java 岗收缩，大厂基础开发编制缩减。"),
                        new KeypointView("行动建议", "与会人员下周三前整理完材料。")),
                null, null);

        assertTrue(md.contains("## 要点\n1. **市场分化**：传统 Java 岗收缩，大厂基础开发编制缩减。\n"
                + "2. **行动建议**：与会人员下周三前整理完材料。\n"), md);
    }

    /** T4.17 口径（保持稳定）：keypoints 为 null 或空 → 整节省略，不输出「（无）」占位。 */
    @Test
    void nullOrEmptyKeypointsOmitTheWholeSection() {
        String withNull = TaskMarkdownExporter.export(ALL, "s", List.of("k"), null,
                "转写。", List.of(new TodoItemView("做点事", null, null, false)));
        String withEmpty = TaskMarkdownExporter.export(ALL, "s", List.of("k"), List.of(),
                "转写。", List.of());

        assertFalse(withNull.contains("## 要点"), "null → 省略要点节：" + withNull);
        assertFalse(withEmpty.contains("## 要点"), "空列表 → 省略要点节：" + withEmpty);
        assertTrue(withNull.contains("## 关键词") && withNull.contains("## 待办"), "其余节不受影响");
    }

    @Test
    void todoShowsDoneCheckboxAndOptionalOwnerDeadline() {
        String md = TaskMarkdownExporter.export(Set.of("todos"), null, null, null, null, List.of(
                new TodoItemView("已完成的事", "张三", "下周三", true),
                new TodoItemView("未完成且无主", null, null, false)));

        assertTrue(md.contains("- [x] 已完成的事 （owner: 张三 / deadline: 下周三）"), md);
        assertTrue(md.contains("- [ ] 未完成且无主"), "owner/deadline 全 null 时只留 action，实际：" + md);
        assertFalse(md.contains("- [ ] 未完成且无主 （"), "null 字段不许输出空括号");
    }

    @Test
    void nullFieldsRenderAsNone() {
        String md = TaskMarkdownExporter.export(ALL, null, null, null, null, null);

        assertTrue(md.contains("## 摘要\n（无）"));
        assertTrue(md.contains("## 关键词\n（无）"));
        assertTrue(md.contains("## 转写\n（无）"));
        assertTrue(md.contains("## 待办\n（无）"));
        assertFalse(md.contains("## 要点"), "keypoints null → 整节省略（T4.17）");
    }

    @Test
    void emptyKeywordsJoinWithComma() {
        String md = TaskMarkdownExporter.export(Set.of("keywords"), null, List.of("a", "b", "c"), null, null, null);

        assertTrue(md.contains("## 关键词\na, b, c"), md);
    }
}
