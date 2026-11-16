package com.meetingnotes.web;

import com.meetingnotes.task.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 使用统计端点（T4.25，从 TaskController 独立成类，先例同 TaskCountsController——TaskController 顶 M3 行数上限）：
 * {@code GET /api/v1/stats} → {tasks, duration_sec_total, success_rate, avg_processing_ratio, tiers}。
 * 只读聚合、无参数、无分页；键集固定，success_rate / avg_processing_ratio 无数据时为 null（键不省略）。
 * 口径见 {@link StatsService} 注释与接入指南「使用统计」节：success_rate = done/(done+dead)；
 * avg_processing_ratio 含排队，不是 RTF（界面文案「平均处理倍速（含排队，近似值）」）。
 */
@RestController
@RequestMapping("/api/v1")
public class StatsController {

    /** 认证体系接入前固定单用户（与 TaskController 一致）。 */
    private static final long DEFAULT_USER_ID = 1L;

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/stats")
    public StatsService.StatsResponse stats() {
        return statsService.stats(DEFAULT_USER_ID);
    }
}
