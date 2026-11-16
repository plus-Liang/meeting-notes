package com.meetingnotes.ai;

/**
 * 合并提示词（T3.3 F-7）：**一次**调用同时产出 {@code summary/keywords/todos/keypoints}。
 *
 * <p>合并的理由（卡 F-7）：① DONE 前最坏等待 2×120s → 120s；② transcript 只交一次、token 减半
 * （推理模型的思考量随 prompt 涨）；③ 降级语义一致——不会出现"摘要成功、待办失败"的混合态。
 *
 * <p>⑦ 的 JSON 体积预算写进提示词口径：summary ≤200 字、keywords ≤10、todos ≤20 条、keypoints 3~15 条
 * （正文约 1500 tok，8192 里还留 ≈2900 余量给推理链）。
 *
 * <p>T3.6：glossary 术语注入段拼在"要求"之前（{@code glossaryText} 为空串/null 时**逐字节不变**——
 * 空词表不改变任何行为）；JSON 结构约束、字段定义、预算公式一个字不改。
 * <p>T3.9：按 {@code template} 从 {@link TemplatePrompts} 取模板主体（角色行/JSON 示例/summary 关注点），
 * 注入顺序不变：模板主体 → glossary 注入段 → 共用要求 → transcript。
 * <p>T4.17（D-109）：共用要求新增第 4 条要点规则（条数自适应/title/detail 长度/禁编造），
 * 严禁编造顺延为第 5 条；要点口径与 {@link Keypoints} 的解析预算一致。
 */
final class StructuringPrompt {

    private StructuringPrompt() {
    }

    static String build(String transcript, String glossaryText, String template) {
        TemplatePrompts.Spec spec = TemplatePrompts.spec(template);
        String injection = glossaryText == null || glossaryText.isBlank() ? ""
                : glossaryText + "\n输出中遇到上述原文说法，一律改用正确写法。\n";
        return spec.roleLine() + "\n"
                + spec.shapeLine() + "\n"
                + injection
                + """
                要求：
                """
                + spec.summaryRule() + "\n"
                + """
                2. keywords 抽取 3~8 个关键名词/术语（最多 10 个）；
                """
                + TodoService.promptSection() + keypointsSection() + """
                5. 严禁编造 transcript 里没有的人名、数字、日期与结论；不确定的内容不要写。

                转写全文：
                """ + transcript;
    }

    /** 合并提示词里 keypoints 一节的约束（T4.17/D-109，package-private 供单测断言口径）。 */
    static String keypointsSection() {
        return """
                4. keypoints 输出内容要点：3~15 条，条数按内容长度自适应（短内容 3~6 条、长内容最多 15 条），
                   内容短就少写、宁缺毋滥不要硬凑；每条形如 {"title":"小标题","detail":"说明"}，
                   title 不超过 20 字，detail 40~120 字；要点必须来自 transcript，优先覆盖议题/结论/关键数据/行动建议，
                   不得编造；确实提炼不出就输出 {"keypoints":[]}。""";
    }
}
