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
 * 第三档：**零依赖**抽取式结构化（D-020；T3.4 再提质，本卡只要求"能产出且不是乱码"）。
 *
 * <p>三条最小实现：
 * <ul>
 *   <li>summary：按 {@code 。！？；\n} 分句 → 词频 + 位置 + 长度惩罚打分取前 5 句（保持原序）；
 *       ASR 输出常没有句末标点，此时退回按逗号/空格分句，否则"前 200 字截断"会冒充摘要；</li>
 *   <li>keywords：字符二元组 + 停用词表 + 标点切分（**不引入分词库**，T3.4 再选型）；</li>
 *   <li>todos：命中"明天/截止/负责/需要/要"等触发词的句子 → action，owner/deadline 用规则抽，抽不到 null。</li>
 * </ul>
 * 输出与其他两档同构（{@link StructuringResult}）；依据校验另由 {@link TodoGrounding} 统一做。
 */
public final class ExtractiveStructurer {

    static final int MAX_SUMMARY_SENTENCES = 5;
    static final int MAX_SUMMARY_CHARS = 200;
    static final int MAX_KEYWORDS = 10;
    static final int MAX_ACTION_CHARS = 40;

    /** 分句符（卡指定：{@code 。！？；\n}；英文标点一并处理）。 */
    private static final String SENTENCE_DELIMITERS = "。！？；\n!?;";

    /** 无句末标点时的兜底分句符（ASR 文本常用空格/逗号断句）。 */
    private static final String FALLBACK_DELIMITERS = "，, \t";

    /** 停用词（二元组粒度）：只清高频虚词，避免把"我们""然后"当关键词。 */
    private static final Set<String> STOPWORDS = Set.of(
            "我们", "你们", "他们", "这个", "那个", "然后", "所以", "但是", "因为", "如果",
            "可以", "就是", "一个", "没有", "现在", "今天", "大家", "比较", "这样", "那样",
            "什么", "怎么", "还有", "已经", "应该", "知道", "觉得", "其实", "东西", "时候",
            "一下", "一些", "这些", "那些", "自己", "不是", "可能", "而且", "或者", "的话",
            "第一", "第二", "进行", "关于", "通过", "其中", "方面", "情况", "问题", "时间");

    /** 待办触发词（规则版）：命中即认为该句含行动项。
     * 单独的"要"要排除"纪要/摘要/重要/主要/需要/只要/必要"这些词——否则"纪要"两个字会把整段会议都判成待办。 */
    private static final Pattern ACTION_HINT = Pattern.compile(
            "明天|后天|下周|下个月|截止|负责|需要|安排|跟进|完成|(?<![纪要摘重主只必需])要");

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
        List<String> sentences = sentences(transcript == null ? "" : transcript);
        if (sentences.isEmpty()) {
            return new StructuringResult("", List.of(), List.of(), AiTier.EXTRACTIVE);
        }
        Map<String, Integer> termFrequency = termFrequency(sentences);
        return new StructuringResult(
                summaryOf(sentences, termFrequency),
                keywordsOf(termFrequency),
                todosOf(sentences),
                AiTier.EXTRACTIVE);
    }

    /** 按 {@code 。！？；\n} 分句（分隔符保留在句尾，摘要读起来才不是断句）。 */
    static List<String> sentences(String text) {
        List<String> primary = split(text, SENTENCE_DELIMITERS, true);
        if (primary.size() <= 1 && text.trim().length() > MAX_ACTION_CHARS) {
            List<String> fallback = split(text, FALLBACK_DELIMITERS, false);
            if (fallback.size() > 1) {
                return fallback;
            }
        }
        return primary;
    }

    private static List<String> split(String text, String delimiters, boolean keepDelimiter) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (delimiters.indexOf(c) >= 0) {
                if (keepDelimiter && c != '\n') {
                    current.append(c);
                }
                addSentence(out, current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        addSentence(out, current.toString());
        return out;
    }

    private static void addSentence(List<String> out, String sentence) {
        String trimmed = sentence.trim();
        if (!trimmed.isEmpty()) {
            out.add(trimmed);
        }
    }

    /** 词频 + 位置 + 长度惩罚 → 取前 5 句并按原序拼接（保证可读性）。 */
    static String summaryOf(List<String> sentences, Map<String, Integer> termFrequency) {
        List<Integer> indexes = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            indexes.add(i);
            scores.add(score(sentences.get(i), i, sentences.size(), termFrequency));
        }
        List<Integer> top = new ArrayList<>();
        for (int k = 0; k < Math.min(MAX_SUMMARY_SENTENCES, indexes.size()); k++) {
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
            sb.append(sentences.get(idx));
        }
        String summary = truncate(sb.toString(), MAX_SUMMARY_CHARS);
        return summary.isBlank() ? truncate(sentences.get(0), MAX_SUMMARY_CHARS) : summary;
    }

    private static double score(String sentence, int index, int total, Map<String, Integer> termFrequency) {
        double frequency = 0;
        for (String bigram : TodoGrounding.bigrams(TodoGrounding.normalize(sentence))) {
            frequency += termFrequency.getOrDefault(bigram, 0);
        }
        double position = index < Math.max(1, total / 3) ? 1.0 : 0.0;
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

    /** 高频二元组（≥3 次优先；实在不够 3 个就退化为按次数取），≤10 个。
     * 边缘是虚词/代词的二元组（"妙的""我的""这个"）一律丢弃——ASR 文本里这类组合频率最高、信息量最低。 */
    static List<String> keywordsOf(Map<String, Integer> termFrequency) {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(termFrequency.entrySet());
        sorted.removeIf(e -> e.getKey().length() < 2
                || STOPWORDS.contains(e.getKey())
                || isFunctionChar(e.getKey().charAt(0))
                || isFunctionChar(e.getKey().charAt(e.getKey().length() - 1)));
        sorted.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
        List<String> keywords = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sorted) {
            if (keywords.size() >= MAX_KEYWORDS) {
                break;
            }
            if (e.getValue() >= 3) {
                keywords.add(e.getKey());
            }
        }
        for (Map.Entry<String, Integer> e : sorted) {
            if (keywords.size() >= Math.min(3, MAX_KEYWORDS)) {
                break;
            }
            if (!keywords.contains(e.getKey())) {
                keywords.add(e.getKey());
            }
        }
        return keywords;
    }

    /** 虚词/代词/结构化字：出现在二元组边缘就说明这个词多半不是"关键名词"。 */
    private static boolean isFunctionChar(char c) {
        return "的了是在和也就都我你他她它这那们有会要很不上下中个把被给对与而但还再才只能可说做用去来么什".indexOf(c) >= 0;
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
            String action = truncate(sentence.trim(), MAX_ACTION_CHARS);
            if (action.isBlank() || !seen.add(action)) {
                continue;
            }
            todos.add(new TodoService.TodoItem(action, ownerOf(sentence), deadlineOf(sentence)));
        }
        return todos;
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
