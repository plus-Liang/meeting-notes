package com.meetingnotes.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第三档：**零依赖**抽取式结构化（D-020）。
 *
 * <p>T3.4 提质（实机证据 96/97：ASR 无标点时 summary 退化为"前 N 字截断"、kw 3、todos 1）：
 * <ul>
 *   <li>summary：分句升级为**四级 fallback**（见 {@link ExtractiveSegmenter}）；
 *       打分加**结尾加分**（治"只截开头"）；拼接**丢句不截句**（装不下的整句丢弃，末句永不腰斩）；</li>
 *   <li>keywords：字符二元组 + 停用词/口头禅表 + 标点切分（**不引入分词库**，真要选型另报），
 *       频次阈值降到 2、频次不足时 fallback 从前 3 提到前 5；</li>
 *   <li>todos：触发词扩充（需要/要/得/必须/记得/截止/之前/完成/提交/确认…），
 *       action 去掉"然后/就是/我们"这类口头前缀，owner/deadline 用规则抽，抽不到 null。</li>
 * </ul>
 * 输出与其他两档同构（{@link StructuringResult}）；依据校验另由 {@link TodoGrounding} 统一做。
 */
public final class ExtractiveStructurer {

    static final int MAX_SUMMARY_SENTENCES = 5;
    static final int MAX_SUMMARY_CHARS = 200;
    static final int MAX_KEYWORDS = 10;
    static final int MAX_ACTION_CHARS = 40;

    /** 停用词（二元组粒度）：高频虚词 + 口头禅（T3.4 扩充），避免把"我们""然后"当关键词。 */
    private static final Set<String> STOPWORDS = Set.of(
            "我们", "你们", "他们", "这个", "那个", "然后", "所以", "但是", "因为", "如果",
            "可以", "就是", "一个", "没有", "现在", "今天", "大家", "比较", "这样", "那样",
            "什么", "怎么", "还有", "已经", "应该", "知道", "觉得", "其实", "东西", "时候",
            "一下", "一些", "这些", "那些", "自己", "不是", "可能", "而且", "或者", "的话",
            "第一", "第二", "进行", "关于", "通过", "其中", "方面", "情况", "问题", "时间",
            "对吧", "好的", "反正", "那种", "有点", "咱们", "接着",
            "那么", "首先", "其次", "最后", "比如", "另外", "同时", "还是", "这块", "那块");

    /** 待办触发词（T3.4 扩充）：命中即认为该句含行动项。
     * 单独的"要"排除"纪要/摘要/重要/主要/需要/只要/必要"；单独的"得"排除"记得/觉得/取得/值得/获得/显得"——
     * 否则这些词的包含关系会把无待办句子整段误判。 */
    private static final Pattern ACTION_HINT = Pattern.compile(
            "明天|后天|下周|下个月|截止|负责|需要|安排|跟进|完成|提交|确认|记得|必须|之前"
                    + "|(?<![纪要摘重主只必需])要|(?<![记觉取值获显])得");

    /** 口头前缀（T3.4：action 去掉"然后我们"这类口头语，剥到第一个实词为止）。 */
    private static final Pattern VERBAL_PREFIX = Pattern.compile(
            "^(然后|接着|就是|那么|还有|另外|接下来|我们|大家|好的|首先|其次|最后)+[，, ]*");

    /** 时间词/日期串：**保留原文说法**，不做任何换算（D-076）。 */
    private static final Pattern DEADLINE = Pattern.compile(
            "下周[一二三四五六日天]?|这周[一二三四五六日天]?|本周[一二三四五六日天]?|下个?月[初中底]?|"
                    + "明天|后天|今天|月底|月初|年底|"
                    + "\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}月\\d{1,2}[日号]|\\d{1,2}[:：]\\d{2}");

    /** 责任词：取"负责/跟进"前 2~3 个汉字作为 owner 候选（抽不到就 null）。 */
    private static final Pattern OWNER = Pattern.compile("(?:由)?([\\u4e00-\\u9fa5]{2,3})(?:负责|跟进)");

    private ExtractiveStructurer() {
    }

    /** 抽取式入口：transcript 非空则必有非空 summary（第三档零依赖兜底的判据）。 */
    public static StructuringResult extract(String transcript) {
        List<String> sentences = ExtractiveSegmenter.sentences(transcript);
        if (sentences.isEmpty()) {
            return new StructuringResult("", List.of(), List.of(), null, AiTier.EXTRACTIVE);
        }
        Map<String, Integer> termFrequency = termFrequency(sentences);
        return new StructuringResult(
                summaryOf(sentences, termFrequency),
                keywordsOf(termFrequency),
                todosOf(sentences),
                null,
                AiTier.EXTRACTIVE);
    }

    /**
     * 词频 + 位置（开头**和结尾**都加分）+ 长度惩罚 → 取前 5 句，按原序**丢句不截句**拼接：
     * 装不进 200 字预算的整句直接丢弃（后面的短句仍可补位），末句永不腰斩（T3.4 治"前 N 字截断"）。
     */
    static String summaryOf(List<String> sentences, Map<String, Integer> termFrequency) {
        List<Integer> indexes = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            indexes.add(i);
            scores.add(score(sentences.get(i), i, sentences.size(), termFrequency));
        }
        // 先锁定选取数再循环：indexes 在循环内不断 remove，若直接用 indexes.size() 做
        // 循环上界，实际只会选出约一半的句子（T3.3 遗留缺陷，句数少时暴露——104 实证）
        int take = Math.min(MAX_SUMMARY_SENTENCES, indexes.size());
        List<Integer> top = new ArrayList<>();
        for (int k = 0; k < take; k++) {
            int best = 0;
            for (int j = 1; j < indexes.size(); j++) {
                if (scores.get(j) > scores.get(best)) {
                    best = j;
                }
            }
            top.add(indexes.remove(best));
            scores.remove(best);
        }
        top.sort(Comparator.naturalOrder());
        StringBuilder sb = new StringBuilder();
        for (int idx : top) {
            String sentence = sentences.get(idx);
            if (sb.length() > 0 && sb.length() + sentence.length() > MAX_SUMMARY_CHARS) {
                continue;
            }
            sb.append(sentence);
        }
        String summary = truncate(sb.toString(), MAX_SUMMARY_CHARS);
        return summary.isBlank() ? truncate(sentences.get(0), MAX_SUMMARY_CHARS) : summary;
    }

    private static double score(String sentence, int index, int total, Map<String, Integer> termFrequency) {
        double frequency = 0;
        for (String bigram : TodoGrounding.bigrams(TodoGrounding.normalize(sentence))) {
            frequency += termFrequency.getOrDefault(bigram, 0);
        }
        int head = Math.max(1, total / 3);
        double position;
        if (index < head) {
            position = 1.0;
        } else if (total > 1 && index >= total - head) {
            position = 0.8; // 结尾加分（T3.4：summary 必须覆盖开头和结尾的要点，不能只截开头）
        } else {
            position = 0.0;
        }
        double lengthPenalty = Math.abs(sentence.length() - 40) / 40.0;
        return frequency + position - lengthPenalty;
    }

    static Map<String, Integer> termFrequency(List<String> sentences) {
        Map<String, Integer> frequency = new HashMap<>();
        for (String sentence : sentences) {
            for (String bigram : TodoGrounding.bigrams(TodoGrounding.normalize(sentence))) {
                frequency.merge(bigram, 1, Integer::sum);
            }
        }
        return frequency;
    }

    /** 关键词频次阈值：ASR 口语语料重复率低，≥3 太苛刻（实测只出 3 个），T3.4 降到 ≥2。 */
    static final int KEYWORD_MIN_FREQUENCY = 2;

    /**
     * 高频二元组（≥2 次优先；实在不够就按次数取满 5 个兜底），≤10 个。
     * 边缘是虚词/代词的二元组（"妙的""我的""这个"）与纯数字碎片一律丢弃。
     */
    static List<String> keywordsOf(Map<String, Integer> termFrequency) {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(termFrequency.entrySet());
        sorted.removeIf(e -> e.getKey().length() < 2
                || STOPWORDS.contains(e.getKey())
                || isNumericChunk(e.getKey())
                || isFunctionChar(e.getKey().charAt(0))
                || isFunctionChar(e.getKey().charAt(e.getKey().length() - 1)));
        sorted.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
        List<String> keywords = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sorted) {
            if (keywords.size() >= MAX_KEYWORDS) {
                break;
            }
            if (e.getValue() >= KEYWORD_MIN_FREQUENCY) {
                keywords.add(e.getKey());
            }
        }
        for (Map.Entry<String, Integer> e : sorted) {
            if (keywords.size() >= 5) {
                break;
            }
            if (!keywords.contains(e.getKey())) {
                keywords.add(e.getKey());
            }
        }
        return keywords;
    }

    /** 纯数字碎片（"12""34"）不是关键词；英文单字母不会出现（二元组长度恒为 2）。 */
    private static boolean isNumericChunk(String chunk) {
        for (int i = 0; i < chunk.length(); i++) {
            if (!Character.isDigit(chunk.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 虚词/代词/语气字：出现在二元组边缘就说明这个词多半不是"关键名词"（T3.4 补口头语气字）。 */
    private static boolean isFunctionChar(char c) {
        return "的了是在和也就都我你他她它这那们有会要很不上下中个把被给对与而但还再才只能可说做用去来么什嗯啊呢嘛吧哦呀哈".indexOf(c) >= 0;
    }

    /** 规则版待办：命中触发词的句子 → action（≤40 字），owner/deadline 用规则抽，抽不到 null。 */
    static List<TodoService.TodoItem> todosOf(List<String> sentences) {
        List<TodoService.TodoItem> todos = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String sentence : sentences) {
            if (todos.size() >= TodoService.MAX_TODOS) {
                break;
            }
            if (!ACTION_HINT.matcher(sentence).find()) {
                continue;
            }
            String action = truncate(stripVerbalPrefix(sentence.trim()), MAX_ACTION_CHARS);
            if (action.isBlank() || !seen.add(action)) {
                continue;
            }
            todos.add(new TodoService.TodoItem(action, ownerOf(sentence), deadlineOf(sentence)));
        }
        return todos;
    }

    /** 口头前缀剥离：循环剥（"然后就是我们…"多层嵌套），剥到没有可剥的为止；剥完为空则保留原句。 */
    static String stripVerbalPrefix(String sentence) {
        String current = sentence;
        while (true) {
            Matcher matcher = VERBAL_PREFIX.matcher(current);
            if (!matcher.find() || matcher.end() >= current.length()) {
                return current;
            }
            current = current.substring(matcher.end()).trim();
        }
    }

    static String ownerOf(String sentence) {
        Matcher matcher = OWNER.matcher(sentence);
        if (!matcher.find()) {
            return null;
        }
        String candidate = matcher.group(1);
        if (candidate.startsWith("由")) {
            candidate = candidate.substring(1);
        }
        return candidate.isBlank() || STOPWORDS.contains(candidate) ? null : candidate;
    }

    static String deadlineOf(String sentence) {
        Matcher matcher = DEADLINE.matcher(sentence);
        return matcher.find() ? matcher.group() : null;
    }

    static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
