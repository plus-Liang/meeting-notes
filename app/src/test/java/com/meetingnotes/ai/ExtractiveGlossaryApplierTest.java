package com.meetingnotes.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * T4.1 F-15（D-089）：EXTRACTIVE 档读词表——wrong→right 确定性替换覆盖
 * summary / keywords / todos.action 三字段；词表为空 → 原样返回（逐字节不变）。
 * 时序约束（替换在依据校验之后）由 {@code StructuringService} 的调用顺序保证，
 * 本类只钉替换语义。
 */
class ExtractiveGlossaryApplierTest {

    @Test
    void replacesWrongTermsAcrossSummaryKeywordsAndTodoAction() {
        StructuringResult input = new StructuringResult(
                "本周整理工作副盘，要点清晰。",
                List.of("工作副盘", "复盘"),
                List.of(new TodoService.TodoItem("整理工作副盘", "张三", "下周三")),
                List.of(new StructuringResult.KeyPoint("工作副盘", "本周整理工作副盘的纪要要点。")),
                AiTier.EXTRACTIVE);

        StructuringResult out = ExtractiveGlossaryApplier.apply(input,
                List.of(new GlossaryTermSource.Term("工作副盘", "工作复盘")));

        assertEquals("本周整理工作复盘，要点清晰。", out.summary(), "summary 必须被纠正");
        assertEquals(List.of("工作复盘", "复盘"), out.keywords(), "keywords 逐项纠正");
        assertEquals("整理工作复盘", out.todos().get(0).action(), "todos.action 必须被纠正");
        assertEquals("张三", out.todos().get(0).owner(), "owner 不受替换影响");
        assertEquals("下周三", out.todos().get(0).deadline(), "deadline 不受替换影响");
        assertEquals(AiTier.EXTRACTIVE, out.aiTier(), "ai_tier 透传不变");
        assertEquals(List.of(new StructuringResult.KeyPoint("工作副盘", "本周整理工作副盘的纪要要点。")),
                out.keypoints(), "keypoints 原样透传（EXTRACTIVE 不产要点，替换口径不动它）");
    }

    @Test
    void emptyTermListKeepsResultByteIdentical() {
        // T3.6 同款回归判据：词表为空不改变任何行为（返回同一实例，零拷贝零替换）
        StructuringResult input = new StructuringResult(
                "摘要原样", List.of("关键词"), List.of(new TodoService.TodoItem("待办", null, null)),
                List.of(new StructuringResult.KeyPoint("要点标题", "要点说明正文。")),
                AiTier.EXTRACTIVE);

        assertSame(input, ExtractiveGlossaryApplier.apply(input, List.of()));
    }
}
