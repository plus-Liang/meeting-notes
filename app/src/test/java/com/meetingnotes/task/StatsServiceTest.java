package com.meetingnotes.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/** T4.25：统计口径——success_rate 3 位小数/分母 0 → null、avg_processing_ratio 无数据 → null、tiers null 单列 "null"、空库全空。 */
@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private ResultSet rs;
    @Mock
    private TaskQueryService taskQueryService;

    private StatsService newService() {
        return new StatsService(jdbc, taskQueryService);
    }

    /** 让 jdbc.query 的 RowCallbackHandler 喂 3 行 tiers（CLOUD=6 / LOCAL=1 / null=1）。 */
    private void stubTierRows() throws Exception {
        when(rs.getString(1)).thenReturn("CLOUD", "LOCAL", null);
        when(rs.getLong(2)).thenReturn(6L, 1L, 1L);
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (int i = 0; i < 3; i++) {
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(contains("GROUP BY r.ai_tier"), ArgumentMatchers.<RowCallbackHandler>any(), eq(1L));
    }

    private void stubNoTierRows() {
        doAnswer(inv -> null).when(jdbc)
                .query(contains("GROUP BY r.ai_tier"), ArgumentMatchers.<RowCallbackHandler>any(), eq(1L));
    }

    @Test
    void computesSuccessRateWithRoundingAndTiers() throws Exception {
        when(taskQueryService.counts(1L)).thenReturn(new TaskQueryService.TaskCounts(11, 1, 8, 2));
        when(jdbc.queryForObject(contains("SUM(duration_sec)"), eq(Long.class), eq(1L))).thenReturn(1234L);
        when(jdbc.queryForObject(contains("AVG("), eq(Double.class), eq(1L))).thenReturn(2.3456);
        stubTierRows();

        StatsService.StatsResponse s = newService().stats(1L);

        assertEquals(new StatsService.TaskStats(11, 1, 8, 2), s.tasks(), "tasks 与 /tasks/counts 同口径");
        assertEquals(1234L, s.durationSecTotal());
        assertEquals(0.8, s.successRate(), 1e-9, "success_rate = 8/10，3 位小数");
        assertEquals(2.346, s.avgProcessingRatio(), 1e-9, "avg_processing_ratio 保留 3 位小数");
        assertEquals(6L, s.tiers().get("CLOUD"));
        assertEquals(1L, s.tiers().get("LOCAL"));
        assertEquals(1L, s.tiers().get("null"), "ai_tier 为 null 单列 \"null\" 键");
        assertEquals(3, s.tiers().size());
    }

    @Test
    void successRateUsesThreeDecimalsForRepeatingFractions() {
        when(taskQueryService.counts(1L)).thenReturn(new TaskQueryService.TaskCounts(3, 0, 1, 2));
        when(jdbc.queryForObject(contains("SUM(duration_sec)"), eq(Long.class), eq(1L))).thenReturn(0L);
        when(jdbc.queryForObject(contains("AVG("), eq(Double.class), eq(1L))).thenReturn(null);
        stubNoTierRows();

        StatsService.StatsResponse s = newService().stats(1L);

        assertEquals(0.333, s.successRate(), 1e-9, "1/3 → 0.333（3 位小数）");
        assertNull(s.avgProcessingRatio(), "无 DONE 且 duration>0 的任务 → null");
    }

    @Test
    void emptyVaultReturnsNullRatesAndZeroDuration() {
        when(taskQueryService.counts(1L)).thenReturn(new TaskQueryService.TaskCounts(0, 0, 0, 0));
        when(jdbc.queryForObject(contains("SUM(duration_sec)"), eq(Long.class), eq(1L))).thenReturn(0L);
        when(jdbc.queryForObject(contains("AVG("), eq(Double.class), eq(1L))).thenReturn(null);
        stubNoTierRows();

        StatsService.StatsResponse s = newService().stats(1L);

        assertEquals(0, s.tasks().total());
        assertEquals(0, s.durationSecTotal());
        assertNull(s.successRate(), "分母 0（无终态任务）→ null，界面写 —");
        assertNull(s.avgProcessingRatio(), "空库 → null，界面写 —");
        assertTrue(s.tiers().isEmpty(), "空库 tiers 空对象");
    }
}
