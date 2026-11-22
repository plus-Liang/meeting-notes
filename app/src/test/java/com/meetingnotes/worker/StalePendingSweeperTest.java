package com.meetingnotes.worker;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.task.QueuePublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.61/T4.62 S2：周期清扫的**编排语义**（判据/刷新 SQL 已收敛到 StalePendingQueries，
 * 其方向语义由 StalePendingQueriesTest 在真 H2 上钉住）：
 * ①陈旧行→先 publish 后 touch（顺序不许反：投递失败绝不刷新）；②空集→零动作；
 * ③enabled=false→连 findStalePending 都不调；④单条投递 503→warn 继续，失败行不 touch。
 */
@ExtendWith(MockitoExtension.class)
class StalePendingSweeperTest {

    @Mock
    private QueuePublisher queuePublisher;
    @Mock
    private StalePendingQueries stalePendingQueries;

    private StalePendingSweeper newSweeper(boolean enabled) {
        StalePendingSweeper sweeper = new StalePendingSweeper(queuePublisher, stalePendingQueries);
        ReflectionTestUtils.setField(sweeper, "enabled", enabled);
        ReflectionTestUtils.setField(sweeper, "staleMinutes", 2L);
        return sweeper;
    }

    @Test
    void stalePendingIsPublishedThenTouchedInThatOrder() {
        when(stalePendingQueries.findStalePending(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(9L));

        newSweeper(true).sweepStalePending();

        InOrder order = inOrder(queuePublisher, stalePendingQueries);
        order.verify(queuePublisher).publish(9L);
        order.verify(stalePendingQueries).touch(9L); /* 先投递成功、再 touch（失败绝不刷新） */
    }

    @Test
    void emptySweepSetMeansZeroActions() {
        when(stalePendingQueries.findStalePending(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Collections.emptyList());

        newSweeper(true).sweepStalePending();

        verify(queuePublisher, never()).publish(anyLong());
        verify(stalePendingQueries, never()).touch(anyLong());
    }

    /** enabled=false：连判据查询都不发起（完全不动）。 */
    @Test
    void disabledSweeperDoesNotEvenQuery() {
        newSweeper(false).sweepStalePending();

        verify(stalePendingQueries, never()).findStalePending(org.mockito.ArgumentMatchers.any());
        verify(queuePublisher, never()).publish(anyLong());
        verify(stalePendingQueries, never()).touch(anyLong());
    }

    /** 单条投递 503：warn 继续，失败行不 touch（下一轮再兜底），其余行照常。 */
    @Test
    void publishFailureDoesNotCrashAndSkipsTouch() {
        when(stalePendingQueries.findStalePending(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(7L, 8L));
        doThrow(new ApiException(503, "QUEUE_UNAVAILABLE", "队列暂不可用，请稍后重试"))
                .when(queuePublisher).publish(7L);

        assertDoesNotThrow(() -> newSweeper(true).sweepStalePending());

        verify(queuePublisher).publish(7L);
        verify(queuePublisher).publish(8L);
        verify(stalePendingQueries, never()).touch(7L);
        verify(stalePendingQueries).touch(8L);
    }
}
