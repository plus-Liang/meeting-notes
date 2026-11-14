package com.meetingnotes.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ASR 文本分句（T3.4 主任务 1 拆出的小工具类，与 {@link JsonExtractor} 同性质）。
 *
 * <p>动机：T3.3 实机证据 96/97——ASR 输出常没有句末标点，旧实现只有逗号/空格一级兜底，
 * 连逗号都没有的文本整体成一段，summary 退化为"前 N 字截断"。四级 fallback：
 * <ol>
 *   <li>句末标点 {@code 。！？；\n}（保留在句尾，摘要读起来不是断句）；</li>
 *   <li>逗号/空格（ASR 文本常用空格断句）；</li>
 *   <li>连接词边界（然后/接下来/所以/首先…在词前切开）；</li>
 *   <li>固定字符窗口切块（连接词也没有的"死文本"最后兜底）。</li>
 * </ol>
 * 极短文本（≤{@value #MAX_SHORT_TEXT_CHARS} 字）不再多级切——整段一句就是摘要，
 * 第三档"transcript 非空必有产出"的判据不破。
 */
public final class ExtractiveSegmenter {

    /** 低于该长度视为"极短文本"，不做多级切分。 */
    static final int MAX_SHORT_TEXT_CHARS = 40;

    /** 分句符（卡指定：{@code 。！？；\n}；英文标点一并处理）。 */
    private static final String SENTENCE_DELIMITERS = "。！？；\n!?;";

    /** 无句末标点时的兜底分句符（ASR 文本常用空格/逗号断句）。 */
    private static final String FALLBACK_DELIMITERS = "，, \t";

    /** 连接词边界（词前切开）：ASR 无任何标点时最常见的口语分界。 */
    private static final Pattern CONNECTIVE_BOUNDARY = Pattern.compile(
            "(?=然后|接下来|所以|首先|其次|那么|但是|因为|比如|最后|另外|同时|就是|我们|大家)");

    /** 连接词也切不开时的最后兜底：固定字符窗口切块（块间不做词界修正，打分层补偿）。 */
    static final int WINDOW_CHARS = 50;

    /** 连接词切分后过短碎段（<8 字）并入前一段，避免摘要被碎片占满。 */
    private static final int MIN_CHUNK_CHARS = 8;

    private ExtractiveSegmenter() {
    }

    /** 四级 fallback 分句入口；输入为 null 时返回空列表。 */
    static List<String> sentences(String text) {
        if (text == null) {
            return List.of();
        }
        List<String> primary = split(text, SENTENCE_DELIMITERS, true);
        if (primary.size() > 1) {
            return primary;
        }
        if (text.trim().length() <= MAX_SHORT_TEXT_CHARS) {
            return primary;
        }
        List<String> fallback = split(text, FALLBACK_DELIMITERS, false);
        if (fallback.size() > 1) {
            return fallback;
        }
        List<String> byConnective = splitByConnectives(text);
        if (byConnective.size() > 1) {
            return byConnective;
        }
        return windowChunks(text);
    }

    /** 连接词前切开 + 碎段并入前段（<8 字），结果 ≥2 段才算有效。 */
    static List<String> splitByConnectives(String text) {
        String[] parts = CONNECTIVE_BOUNDARY.split(text, -1);
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!out.isEmpty() && trimmed.length() < MIN_CHUNK_CHARS) {
                out.set(out.size() - 1, out.get(out.size() - 1) + trimmed);
            } else {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** 固定字符窗口切块：保证长文本不再整体成一段。 */
    static List<String> windowChunks(String text) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += WINDOW_CHARS) {
            addSentence(out, text.substring(i, Math.min(text.length(), i + WINDOW_CHARS)));
        }
        return out;
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
}
