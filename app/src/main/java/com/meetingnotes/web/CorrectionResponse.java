package com.meetingnotes.web;

import java.util.List;

/**
 * POST /api/v1/tasks/{id}/corrections 响应体（T3.5）。
 * {@code replaced_count} 口径（e）：transcript 出现次数 + summary 出现次数
 * + keywords 命中**元素数** + todos 字符串字段出现次数（四段合计）；
 * glossary 为本次请求涉及词目的最新状态（hits 为 upsert 后的累计值）。
 */
public record CorrectionResponse(
        long taskId,
        int replacedCount,
        List<GlossaryEntryView> glossary) {

    public record GlossaryEntryView(String wrongText, String rightText, int hits) {
    }
}
