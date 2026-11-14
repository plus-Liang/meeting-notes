package com.meetingnotes.ai;

import java.util.Map;
import java.util.Set;

/**
 * 摘要模板提示词（T3.9 / US-12）：**5 个固定模板**，文案集中一处（一处改文案，可维护性细则）。
 * 值固定为 {@code meeting}（默认）/{@code course}/{@code interview}/{@code podcast}/{@code shortvideo}；
 * 不许为场景新增管线（AGENT.md 硬约束 9）。
 *
 * <p>三字段 JSON 结构不变（{@code summary/keywords/todos}，预算沿用 T3.3/T3.6：
 * summary ≤200 字、keywords ≤10、todos ≤20）；只改 summary 的**结构与关注点**。
 * 注入顺序不变（由 {@link StructuringPrompt} 组装）：模板主体 → glossary 注入段（为空时逐字节不变）
 * → 共用要求（{@code TodoService.promptSection()} + 严禁编造）→ transcript。
 *
 * <p>meeting 的两段文案与 T3.9 之前的生产提示词**逐字节一致**（回归不变量，
 * {@code TemplatePromptsTest} 用硬拷贝的旧文案对照校验，改坏即红）。
 */
public final class TemplatePrompts {

    /** 默认模板：未指定 / 文件名未命中 / 旧任务 / 结构化查不到行。 */
    public static final String DEFAULT_TEMPLATE = "meeting";

    /** 5 个合法模板名（封闭集合；新增须先改 spec/任务卡，不许静默扩）。 */
    public static final Set<String> SUPPORTED =
            Set.of("meeting", "course", "interview", "podcast", "shortvideo");

    /** 非法 template 的对外错误提示（契约逐字固定，D-085；测试断言原文）。 */
    public static final String INVALID_MESSAGE =
            "template 只支持 meeting/course/interview/podcast/shortvideo";

    /**
     * 模板主体（三字段 JSON 结构约束逐字共用，只换角色行 / JSON 示例里的摘要描述 / summary 关注点行）：
     * roleLine —— 第一行（角色 + 只输出一个 JSON 的硬约束）；
     * shapeLine —— JSON 示例行（三字段结构）；
     * summaryRule —— “要求”里第 1 条（summary 的结构与关注点）。
     */
    public record Spec(String roleLine, String shapeLine, String summaryRule) {
    }

    private static final Spec MEETING = new Spec(
            "你是会议纪要助手。请阅读下面的转写全文，只输出**一个**严格的 JSON 对象（不要输出任何其它文字或代码块标记）：",
            "{\"summary\": \"不超过 200 字的会议摘要\", \"keywords\": [\"关键词1\", \"关键词2\"],"
                    + " \"todos\": [{\"action\": \"要做的具体事项\", \"owner\": \"负责人\", \"deadline\": \"截止时间\"}]}",
            "1. summary 覆盖主要议题与结论，不超过 200 字；");

    private static final Spec COURSE = new Spec(
            "你是课程笔记助手。请阅读下面的转写全文，只输出**一个**严格的 JSON 对象（不要输出任何其它文字或代码块标记）：",
            "{\"summary\": \"不超过 200 字的课程笔记摘要\", \"keywords\": [\"关键词1\", \"关键词2\"],"
                    + " \"todos\": [{\"action\": \"要做的具体事项\", \"owner\": \"负责人\", \"deadline\": \"截止时间\"}]}",
            "1. summary 按课堂知识点分点（含定义/公式/例题结论），并写明讲师强调的重点与考点、课后作业/复习要求，不超过 200 字；");

    private static final Spec INTERVIEW = new Spec(
            "你是访谈整理助手。请阅读下面的转写全文，只输出**一个**严格的 JSON 对象（不要输出任何其它文字或代码块标记）：",
            "{\"summary\": \"不超过 200 字的访谈纪要\", \"keywords\": [\"关键词1\", \"关键词2\"],"
                    + " \"todos\": [{\"action\": \"要做的具体事项\", \"owner\": \"负责人\", \"deadline\": \"截止时间\"}]}",
            "1. summary 以问答结构组织（问题 → 回答要点），提炼受访者核心观点与结论/金句，不超过 200 字；");

    private static final Spec PODCAST = new Spec(
            "你是播客 show notes 助手。请阅读下面的转写全文，只输出**一个**严格的 JSON 对象（不要输出任何其它文字或代码块标记）：",
            "{\"summary\": \"不超过 200 字的 show notes\", \"keywords\": [\"关键词1\", \"关键词2\"],"
                    + " \"todos\": [{\"action\": \"要做的具体事项\", \"owner\": \"负责人\", \"deadline\": \"截止时间\"}]}",
            "1. summary 写 show notes：主题、章节要点、提到的资源/人名/作品与可执行启发，不超过 200 字；");

    private static final Spec SHORTVIDEO = new Spec(
            "你是短视频拆解助手。请阅读下面的转写全文，只输出**一个**严格的 JSON 对象（不要输出任何其它文字或代码块标记）：",
            "{\"summary\": \"不超过 200 字的短视频拆解\", \"keywords\": [\"关键词1\", \"关键词2\"],"
                    + " \"todos\": [{\"action\": \"要做的具体事项\", \"owner\": \"负责人\", \"deadline\": \"截止时间\"}]}",
            "1. summary 提炼核心观点、论据/例子与可执行动作，并明确给出是否值得收藏的结论，不超过 200 字；");

    private static final Map<String, Spec> SPECS = Map.of(
            "meeting", MEETING,
            "course", COURSE,
            "interview", INTERVIEW,
            "podcast", PODCAST,
            "shortvideo", SHORTVIDEO);

    private TemplatePrompts() {
    }

    /**
     * 按模板名取主体文案。null/空白/未知值一律回落 meeting（防御：库里的值来自提交时已校验的
     * {@code TemplateResolver}，这里兜 COALESCE 之外的意外，不让结构化失败）。
     */
    static Spec spec(String template) {
        if (template == null || template.isBlank()) {
            return MEETING;
        }
        return SPECS.getOrDefault(template, MEETING);
    }
}
