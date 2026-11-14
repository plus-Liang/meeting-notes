package com.meetingnotes.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型原始输出中抽取 JSON 对象文本（T3.4 ④，云/本地档共用的容错工具）。
 *
 * <p>动机：T3.3 实机证据行 98/101 显示，模型输出常带散文包裹或 ```json 围栏，
 * 直接 {@code readTree} 必失败 → 无谓降级。本类只负责"把 JSON 找出来"，
 * 解析仍由 {@link StructuringService} 做；抽不到返回 null，走既有
 * "解析失败 → WARN 截 200 + 三字段一起留空 + 降级"路径，不放宽提示词硬约束。
 *
 * <p>抽取规则：
 * <ol>
 *   <li>优先取 ```json 围栏内的内容（围栏可残缺——残缺时自然落回全文扫描）；</li>
 *   <li>从第一个 {@code {} 起**括号配对扫描**：深度计数；字符串字面量内的
 *       {@code { } " } 一律不算结构符，转义（{@code \"}、{@code \\}）跳过；
 *       找到与第一个 {@code {} **匹配**的 {@code }} 为止；</li>
 *   <li>抽不到（全文无 {@code {}、{@code {} 不闭合）→ null → 失败降级。</li>
 * </ol>
 */
public final class JsonExtractor {

    /** ```json 围栏（含无语言标记的 ```）；残缺（无闭合 ```）时匹配不到，落回全文扫描。 */
    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*(.*?)```", Pattern.DOTALL);

    private JsonExtractor() {
    }

    /**
     * @return 抽出的 JSON 对象文本（从 {@code {} 到匹配 {@code }}）；抽不到返回 null。
     */
    static String extract(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher fence = FENCE.matcher(raw);
        while (fence.find()) {
            String candidate = balancedObject(fence.group(1));
            if (candidate != null) {
                return candidate;
            }
        }
        return balancedObject(raw);
    }

    /** 括号配对扫描：返回第一个 {@code {} 到其匹配 {@code }} 的子串；不闭合返回 null。 */
    static String balancedObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        boolean inString = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // 转义（\" \\ \n…）：下一个字符不参与任何判定
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
