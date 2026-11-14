package com.meetingnotes.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.3 ③：第三档抽取式——零依赖、transcript 非空必定有产出（N15「零成本可跑」的最终兜底）。
 * 不引入分词库：字符 n-gram + 停用词 + 标点切分。
 */
class ExtractiveStructurerTest {

    private static final String TRANSCRIPT = """
            今天开会讨论录音纪要平台的上线计划。
            转写服务已经稳定，RTF 大概 0.11。
            上线计划由张三负责整理，下周三截止。
            摘要质量还需要再评估一次。
            关键词抽取先用字符二元组加停用词。
            """;

    @Test
    void summaryIsNonEmptyAndWithinBudget() {
        StructuringResult result = ExtractiveStructurer.extract(TRANSCRIPT);

        assertFalse(result.summary().isBlank(), "transcript 非空就必须有 summary（第三档的硬承诺）");
        assertTrue(result.summary().length() <= 200, "⑦ 预算：summary ≤200 字，实际 " + result.summary().length());
        assertEquals(AiTier.EXTRACTIVE, result.aiTier());
    }

    @Test
    void summaryKeepsTopSentencesInOriginalOrder() {
        String summary = ExtractiveStructurer.extract(TRANSCRIPT).summary();

        assertTrue(summary.contains("上线计划") || summary.contains("录音纪要"),
                "关键句应覆盖高频主题，实际：" + summary);
        assertTrue(summary.length() >= 10, "不能只留一两个字（长度惩罚要生效）");
    }

    @Test
    void keywordsAreFrequentTermsWithinTen() {
        List<String> keywords = ExtractiveStructurer.extract(TRANSCRIPT).keywords();

        assertFalse(keywords.isEmpty(), "关键词至少要有几个");
        assertTrue(keywords.size() <= 10, "⑦ 预算：keywords ≤10，实际 " + keywords.size());
    }

    @Test
    void todosComeFromTriggerSentences() {
        List<TodoService.TodoItem> todos = ExtractiveStructurer.extract(TRANSCRIPT).todos();

        assertFalse(todos.isEmpty(), "命中'负责/下周三/截止'的句子应产出待办");
        TodoService.TodoItem first = todos.get(0);
        assertTrue(first.action().contains("上线计划"), "action 必须来自原文，实际：" + first.action());
        assertEquals("张三", first.owner(), "规则版能抽到'由张三负责'的 owner");
        assertEquals("下周三", first.deadline(), "相对时间词保留原词，不换算");
    }

    @Test
    void sentencesSplitOnChineseAndEnglishPunctuation() {
        List<String> sentences = ExtractiveStructurer.sentences("第一句。第二句！第三句？第四句；\n第五句");

        assertEquals(5, sentences.size(), "按 。！？；\\n 分句，实际：" + sentences);
        assertEquals("第一句。", sentences.get(0));
    }

    @Test
    void asrTextWithoutSentencePunctuationStillSplitsAndSummarizes() {
        // 真实 ASR 输出常常没有句末标点（空格/逗号分隔），不能退化成"前 200 字截断"
        String asrLike = "今天给大家分享一下 我在公司当中是如何使用AI编程的 以及和团队是如何协作的 "
                + "中间遇到的问题是什么 最后如何解决的 这套工作方式包括状态 交接 上下文路由和评审机制 "
                + "后面还会把工作区搬到云端 让团队一起协作";

        List<String> sentences = ExtractiveStructurer.sentences(asrLike);
        String summary = ExtractiveStructurer.extract(asrLike).summary();

        assertTrue(sentences.size() > 1, "无句末标点时必须按逗号/空格兜底分句，实际：" + sentences.size());
        assertFalse(summary.isBlank());
        assertTrue(summary.length() <= 200);
    }

    @Test
    void singleSentenceWithoutPunctuationStillProducesSummary() {
        StructuringResult result = ExtractiveStructurer.extract("明天把材料交给李四");

        assertFalse(result.summary().isBlank(), "没有句末标点也要有 summary（不能返回空）");
        assertTrue(result.summary().length() <= 200);
    }

    @Test
    void blankTranscriptProducesEmptyResult() {
        StructuringResult result = ExtractiveStructurer.extract("");

        assertTrue(result.summary().isEmpty());
        assertTrue(result.todos().isEmpty());
        assertTrue(result.keywords().isEmpty());
    }
}
