package com.meetingnotes.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3.3 ①：首选档判定（plan §5.1 / D-077）+ 级联顺序（D-075）。
 *
 * <p>判定矩阵逐格：有 key 且非离线 → CLOUD；离线模式禁用云档（但本地档仍可用，D-077 硬门禁）；
 * 本地档必须"开关 + 模型名 + 探活成功"三者齐备；探活结果缓存一次（每任务不再付 2s）。
 */
@ExtendWith(MockitoExtension.class)
class AiStructuringRouterTest {

    private static final String MODEL = "qwen2.5:1.5b";

    @Mock
    private OllamaProbe probe;

    @Test
    void configuredKeyWithOnlineModePrefersCloud() {
        AiStructuringRouter router = new AiStructuringRouter(true, false, false, "", probe);

        assertEquals(AiTier.CLOUD, router.preferredTier());
        verify(probe, never()).reachable();
    }

    @Test
    void offlineModeDisablesCloudEvenWithKey() {
        AiStructuringRouter router = new AiStructuringRouter(true, true, false, "", probe);

        assertEquals(AiTier.EXTRACTIVE, router.preferredTier(),
                "D-077：OFFLINE_MODE=true 禁一切出站 → 云档硬门禁，即使三件套齐也不判 CLOUD");
    }

    @Test
    void offlineModeStillAllowsLocalTier() {
        when(probe.reachable()).thenReturn(true);
        AiStructuringRouter router = new AiStructuringRouter(true, true, true, MODEL, probe);

        assertEquals(AiTier.LOCAL, router.preferredTier(), "离线模式下本地 LLM 仍可用（不出网）");
    }

    @Test
    void unreachableOllamaFallsBackToExtractive() {
        when(probe.reachable()).thenReturn(false);
        AiStructuringRouter router = new AiStructuringRouter(false, false, true, MODEL, probe);

        assertEquals(AiTier.EXTRACTIVE, router.preferredTier());
    }

    @Test
    void ollamaWithoutModelNameDoesNotEvenProbe() {
        AiStructuringRouter router = new AiStructuringRouter(false, false, true, "   ", probe);

        assertEquals(AiTier.EXTRACTIVE, router.preferredTier());
        verify(probe, never()).reachable();
    }

    @Test
    void probeResultIsCachedAcrossCalls() {
        when(probe.reachable()).thenReturn(true);
        AiStructuringRouter router = new AiStructuringRouter(false, false, true, MODEL, probe);

        router.preferredTier();
        router.preferredTier();

        verify(probe, times(1)).reachable();
    }

    @Test
    void cascadeOrderIsCloudThenLocalThenExtractiveThenStop() {
        AiStructuringRouter router = new AiStructuringRouter(false, false, false, "", probe);

        assertEquals(AiTier.LOCAL, router.nextTier(AiTier.CLOUD));
        assertEquals(AiTier.EXTRACTIVE, router.nextTier(AiTier.LOCAL));
        assertNull(router.nextTier(AiTier.EXTRACTIVE), "抽取式已是最后一档，降级链到此为止");
    }
}
