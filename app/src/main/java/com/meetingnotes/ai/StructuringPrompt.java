package com.meetingnotes.ai;

/**
 * 合并提示词（T3.3 F-7）：**一次**调用同时产出 {@code summary/keywords/todos}。
 *
 * <p>合并的理由（卡 F-7）：① DONE 前最坏等待 2×120s → 120s；② transcript 只交一次、token 减半
 * （推理模型的思考量随 prompt 涨）；③ 降级语义一致——不会出现"摘要成功、待办失败"的混合态。
 *
 * <p>⑦ 的 JSON 体积预算写进提示词口径：summary ≤200 字、keywords ≤10、todos ≤20 条
 * （正文约 1500 tok，8192 里还留 ≈2900 余量给推理链）。
 *
 * <p>T3.6：glossary 术语注入段拼在"要求"之前（{@code glossaryText} 为空串/null 时**逐字节不变**——
 * 空词表不改变任何行为）；JSON 结构约束、字段定义、预算公式一个字不改。
 */
final class StructuringPrompt {

    private StructuringPrompt() {
    }

    static String build(String transcript, String glossaryText) {
        String injection = glossaryText == null || glossaryText.isBlank() ? ""
                : glossaryText + "\n输出中遇到上述原文说法，一律改用正确写法。\n";
        return """
                你是会议纪要助手。请阅读下面的转写全文，只输出**一个**严格的 JSON 对象（不要输出任何其它文字或代码块标记）：
                {"summary": "不超过 200 字的会议摘要", "keywords": ["关键词1", "关键词2"], "todos": [{"action": "要做的具体事项", "owner": "负责人", "deadline": "截止时间"}]}
                """
                + injection + """
                要求：
                1. summary 覆盖主要议题与结论，不超过 200 字；
                2. keywords 抽取 3~8 个关键名词/术语（最多 10 个）；
                """
                + TodoService.promptSection() + """
                4. 严禁编造 transcript 里没有的人名、数字、日期与结论；不确定的内容不要写。

                转写全文：
                """ + transcript;
    }
}
