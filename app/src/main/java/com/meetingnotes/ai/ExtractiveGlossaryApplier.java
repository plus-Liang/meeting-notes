package com.meetingnotes.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * F-15（D-089）：EXTRACTIVE 档读词表——对产出做 wrong→right 的**确定性替换**
 * （summary / keywords / todos.action 三个字段）。
 *
 * <p>调用时序约束：必须在依据校验（{@code TodoGrounding}，对**原文**）**之后**执行——
 * 替换引入的 right_text 不在 transcript 里，先替换会被 grounding 误杀（action 整条丢弃）。
 * 词表为空 → 原样返回（**逐字节不变**，T3.6 同款回归判据）；
 * 日志只记替换处数，不泄露词条内容（T3.6 隐私口径）。
 */
final class ExtractiveGlossaryApplier {

    private static final Logger log = LoggerFactory.getLogger(ExtractiveGlossaryApplier.class);

    private ExtractiveGlossaryApplier() {
    }

    static StructuringResult apply(StructuringResult result, List<GlossaryTermSource.Term> terms) {
        if (terms.isEmpty()) {
            return result;
        }
        int replaced = 0;
        String summary = result.summary();
        List<String> keywords = new ArrayList<>(result.keywords());
        List<TodoService.TodoItem> todos = new ArrayList<>(result.todos());
        for (GlossaryTermSource.Term term : terms) {
            if (summary.contains(term.wrong())) {
                summary = summary.replace(term.wrong(), term.right());
                replaced++;
            }
            for (int i = 0; i < keywords.size(); i++) {
                if (keywords.get(i).contains(term.wrong())) {
                    keywords.set(i, keywords.get(i).replace(term.wrong(), term.right()));
                    replaced++;
                }
            }
            for (int i = 0; i < todos.size(); i++) {
                TodoService.TodoItem item = todos.get(i);
                if (item.action() != null && item.action().contains(term.wrong())) {
                    todos.set(i, new TodoService.TodoItem(
                            item.action().replace(term.wrong(), term.right()), item.owner(), item.deadline()));
                    replaced++;
                }
            }
        }
        if (replaced > 0) {
            log.info("术语替换：{} 处（EXTRACTIVE 读词表）", replaced);
        }
        return new StructuringResult(summary, keywords, todos, result.keypoints(), result.aiTier());
    }
}
