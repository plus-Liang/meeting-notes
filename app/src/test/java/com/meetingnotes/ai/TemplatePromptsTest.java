package com.meetingnotes.ai;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.9 模板提示词：meeting 与 T3.9 之前的生产提示词**逐字节一致**（回归不变量——用硬拷贝的旧文案
 * 对照，改坏即红）；5 模板互不相同且三字段结构/共用要求/transcript 注入顺序保持。
 */
class TemplatePromptsTest {

    private static final String TRANSCRIPT = "张三：会议开始。李四：材料下周三前整理完。";

    /** T3.9 之前 StructuringPrompt.build(transcript, glossaryText) 的逐字节硬拷贝（空词表路径）。 */
    private static String legacyMeetingPrompt(String transcript) {
        return """
                你是会议纪要助手。请阅读下面的转写全文，只输出**一个**严格的 JSON 对象（不要输出任何其它文字或代码块标记）：
                {"summary": "不超过 200 字的会议摘要", "keywords": ["关键词1", "关键词2"], "todos": [{"action": "要做的具体事项", "owner": "负责人", "deadline": "截止时间"}]}
                """ + """
                要求：
                1. summary 覆盖主要议题与结论，不超过 200 字；
                2. keywords 抽取 3~8 个关键名词/术语（最多 10 个）；
                """ + TodoService.promptSection() + """
                4. 严禁编造 transcript 里没有的人名、数字、日期与结论；不确定的内容不要写。

                转写全文：
                """ + transcript;
    }

    @Test
    void meetingPromptIsByteIdenticalToLegacy() {
        // 回归不变量：默认模板下任何已有任务的提示词必须一个字节都不变
        assertEquals(legacyMeetingPrompt(TRANSCRIPT),
                StructuringPrompt.build(TRANSCRIPT, "", "meeting"));
        // null 词表同路径
        assertEquals(legacyMeetingPrompt(TRANSCRIPT),
                StructuringPrompt.build(TRANSCRIPT, null, "meeting"));
    }

    @Test
    void fiveTemplatesAreMutuallyDifferent() {
        Set<String> prompts = new HashSet<>();
        for (String template : TemplatePrompts.SUPPORTED) {
            prompts.add(StructuringPrompt.build(TRANSCRIPT, "", template));
        }
        assertEquals(5, prompts.size(), "5 模板提示词必须互不相同，实际 " + prompts.size());
    }

    @Test
    void everyTemplateKeepsJsonShapeSharedSectionsAndTranscript() {
        for (String template : TemplatePrompts.SUPPORTED) {
            String prompt = StructuringPrompt.build(TRANSCRIPT, "", template);
            assertTrue(prompt.contains("\"summary\"") && prompt.contains("\"keywords\"")
                            && prompt.contains("\"todos\""),
                    template + " 三字段 JSON 结构约束必须保留");
            assertTrue(prompt.contains("不超过 200 字"), template + " summary 预算口径必须保留");
            assertTrue(prompt.contains("最多 10 个"), template + " keywords 预算口径必须保留");
            assertTrue(prompt.contains("最多 20 条"), template + " todos 预算口径必须保留（F-5）");
            assertTrue(prompt.contains("严禁编造"), template + " 共用反编造要求必须保留");
            assertTrue(prompt.endsWith(TRANSCRIPT), template + " transcript 必须原样在末尾注入");
        }
    }

    @Test
    void glossaryInjectionSitsBetweenTemplateBodyAndSharedRequirements() {
        // 注入顺序不变（卡 1）：模板主体 → glossary 注入段 → 共用要求 → transcript
        String prompt = StructuringPrompt.build(TRANSCRIPT, "【术语表（原文说法 → 正确写法）】章总→张总", "course");
        assertTrue(prompt.contains("课堂知识点"), "course 模板关注点必须出现");
        assertTrue(prompt.indexOf("术语表") < prompt.indexOf("要求："),
                "注入段必须在共用要求之前");
        assertTrue(prompt.indexOf("一律改用正确写法") < prompt.indexOf("严禁编造"),
                "注入段（含改写指令）必须在共用反编造要求之前");
        assertTrue(prompt.contains("\"summary\""), "JSON 结构约束原样保留（T3.6 口径不变）");
    }

    @Test
    void nullOrUnknownTemplateFallsBackToMeetingSpec() {
        // 防御口径：库里的值来自提交时已校验的 TemplateResolver；这里兜意外，不让结构化失败
        assertEquals(TemplatePrompts.spec("meeting"), TemplatePrompts.spec(null));
        assertEquals(TemplatePrompts.spec("meeting"), TemplatePrompts.spec("  "));
        assertEquals(TemplatePrompts.spec("meeting"), TemplatePrompts.spec("weekly"));
        assertNotEquals(TemplatePrompts.spec("course"), TemplatePrompts.spec("meeting"));
    }
}
