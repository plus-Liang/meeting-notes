package com.meetingnotes.worker;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.task.QueuePublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.61/T4.62/T4.67 S1：周期清扫的**编排语义**（判据/刷新 SQL 已收敛到 StalePendingQueries，
 * 其方向语义由 StalePendingQueriesTest 在真 H2 上钉住）：
 * ①陈旧行→先 publishToTail 后 touch（顺序不许反：投递失败绝不刷新；T4.67-fix F5 改尾插）；
 * ②空集→零动作；③enabled=false→连 findStalePending 都不调；④单条投递 503→warn 继续，失败行不 touch；
 * ⑤判据查询抛 DataAccessException→吞掉、零投递（T4.67-fix F2/S6）；⑥同批多行按返回序（ASC=旧→新）
 * 逐条尾插，touch 各自在对应 publishToTail 之后、绝不头插（T4.67-fix F5，A2 变异②落点）。
 * S1：cutoff 必须随配置的 staleMinutes 走（非默认值 7 也能算对，±60s 容差）；
 *     默认值契约 = 字面量 2 分钟（字段初始化器 + @Value 注解串，变异改 :0 时变红）。
 */
@ExtendWith(MockitoExtension.class)
class StalePendingSweeperTest {

    @Mock
    private QueuePublisher queuePublisher;
    @Mock
    private StalePendingQueries stalePendingQueries;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ZSetOperations<String, String> zSetOps;

    private StalePendingSweeper newSweeper(boolean enabled) {
        StalePendingSweeper sweeper = new StalePendingSweeper(queuePublisher, stalePendingQueries, redis);
        ReflectionTestUtils.setField(sweeper, "enabled", enabled);
        ReflectionTestUtils.setField(sweeper, "staleMinutes", 2L);
        return sweeper;
    }

    /* ===== T4.67 S1：cutoff 必须随配置的 staleMinutes 走；默认值 = 2 分钟（字面量契约） ===== */

    /** 非默认值 7 分钟：cutoff ≈ now - 7 分钟（±60s 容差，避免时钟抖动）。 */
    @Test
    void cutoffReflectsConfiguredStaleMinutes() {
        StalePendingSweeper sweeper = newSweeper(true);
        ReflectionTestUtils.setField(sweeper, "staleMinutes", 7L);
        when(stalePendingQueries.findStalePending(any())).thenReturn(Collections.emptyList());

        sweeper.sweepStalePending();

        ArgumentCaptor<Timestamp> captor = ArgumentCaptor.forClass(Timestamp.class);
        verify(stalePendingQueries).findStalePending(captor.capture());
        long captured = captor.getValue().getTime();
        long now = System.currentTimeMillis();
        long sevenMinMs = 7 * 60 * 1000L;
        /* 容差 ±60s：断言执行本身有时钟消耗 */
        assertTrue(captured >= now - sevenMinMs - 60_000 && captured <= now - sevenMinMs + 60_000,
                "cutoff 应 ≈ now - 7 分钟（±60s）：captured=" + captured + " now=" + now);
    }

    /** 默认值字面量契约：字段初始化器 = 2；@Value 注解串默认 = 2 分钟（变异改 :0 时此断言变红）。 */
    @Test
    void staleMinutesDefaultsToTwoMinutes() throws Exception {
        StalePendingSweeper fresh = new StalePendingSweeper(queuePublisher, stalePendingQueries, redis);
        assertEquals(2L, (Long) ReflectionTestUtils.getField(fresh, "staleMinutes"),
                "字段声明处默认值必须是 2 分钟（S4：与 StartupRecovery 共用单一来源）");

        Value v = StalePendingSweeper.class.getDeclaredField("staleMinutes")
                .getAnnotation(Value.class);
        assertTrue(v != null && v.value().endsWith(":" + StalePendingQueries.DEFAULT_STALE_MINUTES + "}"),
                "@Value 默认值必须引用共享常量（当前 = " + (v == null ? "null" : v.value()) + "）");
    }

    @Test
    void stalePendingIsPublishedToTailThenTouchedInThatOrder() {
        when(stalePendingQueries.findStalePending(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(9L));

        newSweeper(true).sweepStalePending();

        InOrder order = inOrder(queuePublisher, stalePendingQueries);
        order.verify(queuePublisher).publishToTail(9L);
        order.verify(stalePendingQueries).touch(9L); /* 先投递成功、再 touch（失败绝不刷新） */
        verify(queuePublisher, never()).publish(9L); /* T4.67-fix F5：清扫重投绝不走头插通道（不插队） */
    }

    @Test
    void emptySweepSetMeansZeroActions() {
        when(stalePendingQueries.findStalePending(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Collections.emptyList());

        newSweeper(true).sweepStalePending();

        verify(queuePublisher, never()).publish(anyLong());
        verify(queuePublisher, never()).publishToTail(anyLong());
        verify(stalePendingQueries, never()).touch(anyLong());
    }

    /** enabled=false：连判据查询都不发起（完全不动）。 */
    @Test
    void disabledSweeperDoesNotEvenQuery() {
        newSweeper(false).sweepStalePending();

        verify(stalePendingQueries, never()).findStalePending(org.mockito.ArgumentMatchers.any());
        verify(queuePublisher, never()).publish(anyLong());
        verify(queuePublisher, never()).publishToTail(anyLong());
        verify(stalePendingQueries, never()).touch(anyLong());
    }

    /** 单条投递 503：warn 继续，失败行不 touch（下一轮再兜底），其余行照常。 */
    @Test
    void tailPublishFailureDoesNotCrashAndSkipsTouch() {
        when(stalePendingQueries.findStalePending(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(7L, 8L));
        doThrow(new ApiException(503, "QUEUE_UNAVAILABLE", "队列暂不可用，请稍后重试"))
                .when(queuePublisher).publishToTail(7L);

        assertDoesNotThrow(() -> newSweeper(true).sweepStalePending());

        verify(queuePublisher).publishToTail(7L);
        verify(queuePublisher).publishToTail(8L);
        verify(stalePendingQueries, never()).touch(7L);
        verify(stalePendingQueries).touch(8L);
        verify(queuePublisher, never()).publish(anyLong()); /* 尾插语义不回退到头插 */
    }

    /* ===== T4.67-fix F2（S6）：判据查询抛 DataAccessException 必须被吞掉——不向上抛、零投递、零 touch ===== */

    @Test
    void queryFailureIsSwallowedWithoutPublishing() {
        when(stalePendingQueries.findStalePending(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataAccessException("表不存在（测试环境）") {
                });

        assertDoesNotThrow(() -> newSweeper(true).sweepStalePending());

        verify(queuePublisher, never()).publish(anyLong());
        verify(queuePublisher, never()).publishToTail(anyLong());
        verify(stalePendingQueries, never()).touch(anyLong());
    }

    /* ===== T4.67-fix F5（S8 改判）：同批按返回序（ASC=旧→新）逐条尾插；touch 各自在其 publishToTail 之后 ===== */

    @Test
    void batchIsRequeuedOldestFirstViaTailWithoutQueueJumping() {
        /* findStalePending ASC 返回：7=最旧、8=较新——清扫按返回序逐条追加队尾（旧者先被 worker 取走） */
        when(stalePendingQueries.findStalePending(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(7L, 8L));

        newSweeper(true).sweepStalePending();

        InOrder order = inOrder(queuePublisher, stalePendingQueries);
        order.verify(queuePublisher).publishToTail(7L);
        order.verify(stalePendingQueries).touch(7L);
        order.verify(queuePublisher).publishToTail(8L);
        order.verify(stalePendingQueries).touch(8L);
        verify(queuePublisher, never()).publish(anyLong()); /* 不插队：绝不头插 */
    }

    /* ===== T4.79/P-02：僵尸回执清扫（可见性超时 + DB 状态分派） ===== */

    /** 带 T4.79 配置的构造：visibility=20s、heartbeat=20s（心跳×3=60s 新鲜窗）。 */
    private StalePendingSweeper newSweeperWithReceiptConfig() {
        StalePendingSweeper sweeper = new StalePendingSweeper(queuePublisher, stalePendingQueries, redis);
        ReflectionTestUtils.setField(sweeper, "enabled", true);
        ReflectionTestUtils.setField(sweeper, "staleMinutes", 2L);
        ReflectionTestUtils.setField(sweeper, "visibilityTimeoutSec", 20L);
        ReflectionTestUtils.setField(sweeper, "heartbeatSec", 20L);
        ReflectionTestUtils.setField(sweeper, "processingKey", "meetingnotes:worker-processing");
        return sweeper;
    }

    private void stubEmptyPendingSweep() {
        when(stalePendingQueries.findStalePending(org.mockito.ArgumentMatchers.any())).thenReturn(Collections.emptyList());
        when(redis.opsForZSet()).thenReturn(zSetOps);
    }

    /** ④ 可见性超时 + 心跳新鲜（updated_at 在 heartbeat×3 内）→ 跳过：不回收、不重投、不删回执。 */
    @Test
    void freshHeartbeatRunningIsNotRecovered() {
        StalePendingSweeper sweeper = newSweeperWithReceiptConfig();
        stubEmptyPendingSweep();
        when(zSetOps.rangeByScore(eq("meetingnotes:worker-processing"), eq(0.0), anyDouble(), eq(0L), eq(50L)))
                .thenReturn(Set.of("5"));
        when(stalePendingQueries.findTaskStatus(5L)).thenReturn(Map.of(
                "status", "RUNNING", "updated_at", new Timestamp(System.currentTimeMillis() - 1000L)));

        sweeper.sweepStalePending();

        verify(stalePendingQueries, never()).reclaimZombieRunning(anyLong());
        verify(queuePublisher, never()).publishToTail(anyLong());
        verify(zSetOps, never()).remove(anyString(), anyString());
    }

    /** ⑤ 可见性超时 + 心跳陈旧（updated_at 超 heartbeat×3）→ 原子抢回收成功 → publishToTail + ZREM。 */
    @Test
    void zombieReceiptReclaimsStaleRunningAndRequeues() {
        StalePendingSweeper sweeper = newSweeperWithReceiptConfig();
        stubEmptyPendingSweep();
        when(zSetOps.rangeByScore(eq("meetingnotes:worker-processing"), eq(0.0), anyDouble(), eq(0L), eq(50L)))
                .thenReturn(Set.of("5"));
        when(stalePendingQueries.findTaskStatus(5L)).thenReturn(Map.of(
                "status", "RUNNING", "updated_at", new Timestamp(System.currentTimeMillis() - 600_000L)));
        when(stalePendingQueries.reclaimZombieRunning(5L)).thenReturn(1);
        when(queuePublisher.tryPublishToTail(5L)).thenReturn(true);

        sweeper.sweepStalePending();

        verify(stalePendingQueries).reclaimZombieRunning(5L);
        verify(queuePublisher).tryPublishToTail(5L);
        verify(zSetOps).remove("meetingnotes:worker-processing", "5");
    }

    /** ⑥ 任务行已被删 → 只 ZREM，绝不重投、绝不回收。 */
    @Test
    void deletedTaskReceiptIsOnlyRemoved() {
        StalePendingSweeper sweeper = newSweeperWithReceiptConfig();
        stubEmptyPendingSweep();
        when(zSetOps.rangeByScore(eq("meetingnotes:worker-processing"), eq(0.0), anyDouble(), eq(0L), eq(50L)))
                .thenReturn(Set.of("5"));
        when(stalePendingQueries.findTaskStatus(5L)).thenReturn(null);

        sweeper.sweepStalePending();

        verify(zSetOps).remove("meetingnotes:worker-processing", "5");
        verify(stalePendingQueries, never()).reclaimZombieRunning(anyLong());
        verify(queuePublisher, never()).publishToTail(anyLong());
    }

    /** ⑦ 回收条件 UPDATE 影响行数 0（别人先动）→ 不重复投递，只清回执。 */
    @Test
    void lostReclaimRaceDoesNotRequeueAgain() {
        StalePendingSweeper sweeper = newSweeperWithReceiptConfig();
        stubEmptyPendingSweep();
        when(zSetOps.rangeByScore(eq("meetingnotes:worker-processing"), eq(0.0), anyDouble(), eq(0L), eq(50L)))
                .thenReturn(Set.of("5"));
        when(stalePendingQueries.findTaskStatus(5L)).thenReturn(Map.of(
                "status", "RUNNING", "updated_at", new Timestamp(System.currentTimeMillis() - 600_000L)));
        when(stalePendingQueries.reclaimZombieRunning(5L)).thenReturn(0);

        sweeper.sweepStalePending();

        verify(stalePendingQueries).reclaimZombieRunning(5L);
        verify(queuePublisher, never()).publishToTail(anyLong());
        verify(zSetOps).remove("meetingnotes:worker-processing", "5");
    }

    /** A5 负控（单测层）：visibility ≤0 属误配 → 回退默认 180s（cutoff ≈ now-180s），绝无 0 值全量回收。 */
    @Test
    void nonPositiveVisibilityTimeoutFallsBackToDefault() {
        StalePendingSweeper sweeper = newSweeperWithReceiptConfig();
        ReflectionTestUtils.setField(sweeper, "visibilityTimeoutSec", 0L);
        stubEmptyPendingSweep();
        ArgumentCaptor<Double> maxCaptor = ArgumentCaptor.forClass(Double.class);
        when(zSetOps.rangeByScore(eq("meetingnotes:worker-processing"), eq(0.0), maxCaptor.capture(), eq(0L), eq(50L)))
                .thenReturn(Set.of());

        sweeper.sweepStalePending();

        long now = System.currentTimeMillis();
        double max = maxCaptor.getValue();
        assertTrue(max >= now - 181_000 && max <= now - 179_000,
                "≤0 应回退 180s（cutoff ≈ now-180s），实测 " + max + " vs now=" + now);
        verify(stalePendingQueries, never()).reclaimZombieRunning(anyLong());
    }
}
