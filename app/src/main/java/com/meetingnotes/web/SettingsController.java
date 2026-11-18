package com.meetingnotes.web;

import com.meetingnotes.ai.AiStructuringRouter;
import com.meetingnotes.task.QuotaService;
import com.meetingnotes.common.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设置页数据（T4.1，**只读**）：配额（{@link QuotaService} 同口径）+ AI 档位
 * （{@link AiStructuringRouter#preferredTier()} 同一判定来源，禁止复制判定逻辑）。
 * 术语表走 {@code GET /api/v1/glossary}（TaskController）。不新增可写配置。
 */
@RestController
@RequestMapping("/api/v1")
public class SettingsController {


    private final QuotaService quotaService;
    private final AiStructuringRouter router;

    public SettingsController(QuotaService quotaService, AiStructuringRouter router) {
        this.quotaService = quotaService;
        this.router = router;
    }

    /** 今日配额（口径与 QuotaService.consume 的 CURRENT_DATE upsert 一致）。 */
    public record QuotaView(long usedMinutes, long minutesLimit, long usedTasks, long tasksLimit,
                            long remainingMinutes, long remainingTasks) {
    }

    /** AI 档位：首选档 + 三条件（与启动日志"首选档判定"同口径）。 */
    public record AiTierView(String preferred, boolean cloudConfigured, boolean offlineMode, boolean localReady) {
    }

    public record SettingsResponse(QuotaView quota, AiTierView aiTier) {
    }

    @GetMapping("/settings")
    public SettingsResponse settings() {
        QuotaService.Usage usage = quotaService.currentUsage(UserContext.current());
        QuotaView quota = new QuotaView(
                usage.usedMinutes(), quotaService.minutesPerDay(),
                usage.usedTasks(), quotaService.tasksPerDay(),
                Math.max(0, quotaService.minutesPerDay() - usage.usedMinutes()),
                Math.max(0, quotaService.tasksPerDay() - usage.usedTasks()));
        AiStructuringRouter.TierView tier = router.tierView();
        return new SettingsResponse(quota, new AiTierView(tier.preferred(), tier.cloudConfigured(),
                tier.offlineMode(), tier.localReady()));
    }
}
