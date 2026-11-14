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
        List<String> sentences = ExtractiveSegmenter.sentences("第一句。第二句！第三句？第四句；\n第五句");

        assertEquals(5, sentences.size(), "按 。！？；\\n 分句，实际：" + sentences);
        assertEquals("第一句。", sentences.get(0));
    }

    @Test
    void asrTextWithoutSentencePunctuationStillSplitsAndSummarizes() {
        // 真实 ASR 输出常常没有句末标点（空格/逗号分隔），不能退化成"前 200 字截断"
        String asrLike = "今天给大家分享一下 我在公司当中是如何使用AI编程的 以及和团队是如何协作的 "
                + "中间遇到的问题是什么 最后如何解决的 这套工作方式包括状态 交接 上下文路由和评审机制 "
                + "后面还会把工作区搬到云端 让团队一起协作";

        List<String> sentences = ExtractiveSegmenter.sentences(asrLike);
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

    // ---- T3.4 提质用例（主任务 1~3） ----

    @Test
    void noPunctuationAtAllStillSplitsOnConnectives() {
        // ASR 最恶劣形态：无句末标点、无逗号、无空格 → 第三级连接词边界必须切开
        String asrLike = "本次讨论的结论是我们先把需求文档整理完成然后交给开发团队评估工时最后由产品经理确认排期";

        List<String> sentences = ExtractiveSegmenter.sentences(asrLike);

        assertTrue(sentences.size() >= 3, "连接词（然后/最后）前必须切开，实际：" + sentences);
    }

    @Test
    void deadTextWithoutAnyBoundaryStillSplitsByWindow() {
        // 连接词也没有的"死文本"→ 固定窗口切块，长文本不再整体成一段（"前 200 字截断"的病根）
        String deadText = "项目进展顺利".repeat(20); // 120 字，无任何标点/空格/连接词

        List<String> sentences = ExtractiveSegmenter.sentences(deadText);

        assertTrue(sentences.size() >= 2, "120 字死文本必须切成多块，实际：" + sentences.size());
        assertTrue(sentences.stream().allMatch(s -> s.length() <= 50),
                "窗口切块每块 ≤50 字，实际：" + sentences.stream().map(String::length).toList());
    }

    @Test
    void summaryCoversBothHeadAndTailTopics() {
        // 首尾覆盖（T3.4 硬要求）：开头议题"预算评审"、结尾议题"服务器扩容"都要进 summary
        String transcript = "今天我们讨论预算评审的事情然后张三说了排期然后李四补充了风险然后王五确认了资源最后我们处理服务器扩容的问题";

        String summary = ExtractiveStructurer.extract(transcript).summary();

        assertTrue(summary.contains("预算评审"), "summary 必须覆盖开头的要点，实际：" + summary);
        assertTrue(summary.contains("服务器扩容"), "summary 必须覆盖结尾的要点（不能只截开头），实际：" + summary);
    }

    @Test
    void ultraShortTranscriptStillProducesSummary() {
        // 极短文本保底（纪律 2）：≤10 字也要 summary 非空、不抛异常（第三档"必有产出"判据）
        for (String tiny : List.of("好的", "明天交", "下周三之前完成")) {
            StructuringResult result = ExtractiveStructurer.extract(tiny);
            assertFalse(result.summary().isBlank(), "极短文本 \"" + tiny + "\" 也必须有 summary");
            assertTrue(result.summary().length() <= 200);
        }
    }

    @Test
    void keywordsReachFiveOnColloquialCorpus() {
        // keywords ≥5（T3.4 目标）：T3.3 基线只有 3 个；频次阈值 ≥2 + fallback 前 5
        String corpus = """
                今天讨论部署方案。部署窗口定在周末。
                联调环境已就绪。联调覆盖三个服务。
                回归用例要补齐。回归下周跑完。
                排期已同步。排期还有余量。
                扩容申请已提交。扩容覆盖两台机器。
                """;

        List<String> keywords = ExtractiveStructurer.extract(corpus).keywords();

        assertTrue(keywords.size() >= 5, "口语语料上 keywords 必须 ≥5，实际 " + keywords.size() + "：" + keywords);
        assertTrue(keywords.containsAll(List.of("部署", "联调", "回归", "排期", "扩容")),
                "五个主题词都该入选，实际：" + keywords);
    }

    @Test
    void numericFragmentsAreNotKeywords() {
        // 纯数字碎片不是关键词（卡 2：非纯数字过滤）
        List<String> keywords = ExtractiveStructurer.keywordsOf(new java.util.HashMap<>(
                java.util.Map.of("12", 5, "部署", 2)));

        assertFalse(keywords.contains("12"), "纯数字二元组必须被过滤，实际：" + keywords);
        assertTrue(keywords.contains("部署"));
    }

    @Test
    void todoActionStripsVerbalPrefix() {
        // 口头前缀剥离（卡 3）：action 不以"然后/我们"开头，且不改变原文核心词
        String action = ExtractiveStructurer.stripVerbalPrefix("然后我们明天需要把部署文档提交");

        assertEquals("明天需要把部署文档提交", action, "口头前缀应剥到实词为止");

        List<TodoService.TodoItem> todos = ExtractiveStructurer
                .extract("然后我们需要把部署文档提交给运维团队。").todos();
        assertFalse(todos.isEmpty(), "'需要/提交'命中触发词应产出待办");
        assertFalse(todos.get(0).action().startsWith("然后"), "action 不能带口头前缀，实际：" + todos.get(0).action());
    }

    @Test
    void fewSentenceTextStillSelectsBeyondTheFirstTwo() {
        // 选取数必须在循环前锁定（T3.4 实机 104 发现的 T3.3 遗留缺陷：4 句只选 2 句，summary 退化成首句）
        String text = "今天我们确认了发布日期。然后运维组要准备扩容方案。市场部负责准备宣传材料。最后所有人下周三之前完成自测。";

        String summary = ExtractiveStructurer.extract(text).summary();

        assertTrue(summary.contains("发布日期"), "实际：" + summary);
        assertTrue(summary.contains("宣传材料"), "第 3 句也必须参与选取，实际：" + summary);
        assertTrue(summary.contains("自测"), "第 4 句（结尾）也必须参与选取，实际：" + summary);
    }

    @Test
    void expandedTriggerWordsProduceTodos() {
        // 触发词扩充（卡 3）："记得/确认/之前"也应命中（T3.3 基线 todos 只有 1 条的病根之一）
        List<TodoService.TodoItem> todos = ExtractiveStructurer
                .extract("记得把会议室预订取消。上线前要和运营确认发布时间。").todos();

        assertTrue(todos.size() >= 2, "两条触发句都该产出待办，实际：" + todos.size());
    }
}
