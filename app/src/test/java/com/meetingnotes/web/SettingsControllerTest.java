package com.meetingnotes.web;

import com.meetingnotes.ai.AiStructuringRouter;
import com.meetingnotes.task.QuotaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * T4.1 设置页响应：配额剩余取 max(0, limit-used) 不出负数；AI 档位从
 * AiStructuringRouter.tierView() 透传（单一判定来源，不复制逻辑）。
 */
@ExtendWith(MockitoExtension.class)
class SettingsControllerTest {

    @Mock
    private QuotaService quotaService;
    @Mock
    private AiStructuringRouter router;

    @Test
    void settingsReturnsRemainingQuotaAndPassesTierThrough() {
        when(quotaService.currentUsage(1L)).thenReturn(new QuotaService.Usage(65, 10));
        when(quotaService.minutesPerDay()).thenReturn(120L);
        when(quotaService.tasksPerDay()).thenReturn(50L);
        when(router.tierView()).thenReturn(new AiStructuringRouter.TierView("CLOUD", true, false, false));

        SettingsController.SettingsResponse resp = new SettingsController(quotaService, router).settings();

        assertEquals(65, resp.quota().usedMinutes());
        assertEquals(120, resp.quota().minutesLimit());
        assertEquals(55, resp.quota().remainingMinutes());
        assertEquals(10, resp.quota().usedTasks());
        assertEquals(50, resp.quota().tasksLimit());
        assertEquals(40, resp.quota().remainingTasks());
        assertEquals("CLOUD", resp.aiTier().preferred());
        assertTrue(resp.aiTier().cloudConfigured());
    }

    @Test
    void remainingNeverGoesNegativeWhenOverUsed() {
        when(quotaService.currentUsage(1L)).thenReturn(new QuotaService.Usage(130, 60));
        when(quotaService.minutesPerDay()).thenReturn(120L);
        when(quotaService.tasksPerDay()).thenReturn(50L);
        when(router.tierView()).thenReturn(new AiStructuringRouter.TierView("EXTRACTIVE", false, true, false));

        SettingsController.SettingsResponse resp = new SettingsController(quotaService, router).settings();

        assertEquals(0, resp.quota().remainingMinutes(), "超用不出负数（防御口径）");
        assertEquals(0, resp.quota().remainingTasks());
    }
}
