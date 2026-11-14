package com.meetingnotes.task;

import com.meetingnotes.ai.TemplatePrompts;
import com.meetingnotes.common.ApiException;

import java.util.Locale;

/**
 * 模板解析（T3.9）：显式指定优先 → 音频按原文件名**包含**关键字自动推荐 → 默认 meeting。
 *
 * <p>优先级顺序固定 {@code shortvideo → course → interview → podcast → meeting}（规则表即顺序，
 * 命中即短路）；未命中默认 meeting。文本 PASTE / 字幕**不做自动推荐**（没有有意义的文件名），
 * 显式值否则 meeting。非法显式值 → 400 {@code INVALID_PARAM}，message 逐字固定（D-085），
 * **不静默回落**（US-12 case 4）。
 *
 * <p>解析口径：{@code trim() + toLowerCase(Locale.ROOT)}（土耳其 locale 不坏，T3.7 遗留修复同口径）。
 * 来源（显式/文件名/默认）随结果返回，由调用方打成一行可追溯日志：
 * {@code 模板：course（来源：文件名）}。
 */
public final class TemplateResolver {

    /** 解析结果：template（5 选 1，已规范化）+ source（显式 / 文件名 / 默认，日志可追溯）。 */
    public record Resolution(String template, String source) {
    }

    public static final String SOURCE_EXPLICIT = "显式";
    public static final String SOURCE_FILENAME = "文件名";
    public static final String SOURCE_DEFAULT = "默认";

    /**
     * 文件名关键字表（顺序即优先级：shortvideo → course → interview → podcast → meeting；
     * 命中按**包含**匹配，调用方已转小写）。卡片逐字给定，不许改关键词集合。
     */
    private static final String[][] FILENAME_RULES = {
            {"shortvideo", "抖音", "快手", "小红书", "视频号", "bilibili", "b站", "短视频", "shortvideo"},
            {"course", "课程", "网课", "讲座", "课堂", "公开课", "lesson", "course", "lecture"},
            {"interview", "访谈", "面试", "采访", "interview"},
            {"podcast", "播客", "电台", "podcast"},
            {"meeting", "会议", "腾讯会议", "例会", "zoom", "飞书", "钉钉", "meeting", "teams"},
    };

    private TemplateResolver() {
    }

    /** 校验并规范化显式值（可空/空白 → null 表示未指定）；非法 → 400 INVALID_PARAM（不静默回落）。 */
    public static String validateExplicit(String explicit) {
        if (explicit == null || explicit.isBlank()) {
            return null;
        }
        String normalized = explicit.trim().toLowerCase(Locale.ROOT);
        if (!TemplatePrompts.SUPPORTED.contains(normalized)) {
            throw new ApiException(400, "INVALID_PARAM", TemplatePrompts.INVALID_MESSAGE);
        }
        return normalized;
    }

    /** 音频 UPLOAD：显式优先 → 文件名推荐（仅此路径）→ 默认 meeting。 */
    public static Resolution resolve(String explicit, String filename) {
        String normalized = validateExplicit(explicit);
        if (normalized != null) {
            return new Resolution(normalized, SOURCE_EXPLICIT);
        }
        if (filename != null) {
            String lower = filename.toLowerCase(Locale.ROOT);
            for (String[] rule : FILENAME_RULES) {
                for (int i = 1; i < rule.length; i++) {
                    if (lower.contains(rule[i])) {
                        return new Resolution(rule[0], SOURCE_FILENAME);
                    }
                }
            }
        }
        return new Resolution(TemplatePrompts.DEFAULT_TEMPLATE, SOURCE_DEFAULT);
    }

    /** 文本 PASTE / 字幕：显式值否则 meeting，**不做**文件名自动推荐。 */
    public static Resolution resolveText(String explicit) {
        String normalized = validateExplicit(explicit);
        if (normalized != null) {
            return new Resolution(normalized, SOURCE_EXPLICIT);
        }
        return new Resolution(TemplatePrompts.DEFAULT_TEMPLATE, SOURCE_DEFAULT);
    }
}
