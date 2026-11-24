package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * T4.60 B2：TaskRequeueSupport 是四套「DEAD 复活 + PENDING 自愈」SQL 的单一实现——
 * 这里钉住 SQL 文本逐字不变（C2）与批量重试的回滚口径（失败→DEAD + 补死信 + 503 原样上抛）。
 */
@ExtendWith(MockitoExtension.class)
class TaskRequeueSupportTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private QueuePublisher queuePublisher;
    @Mock
    private com.meetingnotes.worker.StalePendingQueries stalePendingQueries;
    @Mock
    private QuotaService quotaService;
    @Mock
    private DeadTaskRecorder deadTaskRecorder;
    @Mock
    private TransactionTemplate tx;

    @org.junit.jupiter.api.BeforeEach
    void stubTx() {
        lenient().doAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
    }

    private TaskRequeueSupport newSupport() {
        return new TaskRequeueSupport(jdbc, queuePublisher, stalePendingQueries, quotaService, deadTaskRecorder, tx);
    }

    private void stubReviveRow(long id, long userId, int durationSec) throws Exception {
        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(userId);
        when(rs.getInt(2)).thenReturn(durationSec);
        when(jdbc.query(contains("quota_refunded = FALSE"),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any(), eq(id)))
                .thenAnswer(inv -> ((ResultSetExtractor<Object>) inv.getArgument(1)).extractData(rs));
    }

    /** T4.87 C4：复活 = 重置 + quota_refunded=FALSE + 重新消费（ceil）+ 清死信 + 重投。 */
    @Test
    void reviveDeadThenPublishReconsumesAndPublishes() throws Exception {
        stubReviveRow(9L, 1L, 120);
        newSupport().reviveDeadThenPublish(9L);

        verify(jdbc).query(contains("UPDATE task SET attempts = 0, status = 'PENDING', error_code = NULL"),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any(), eq(9L));
        verify(quotaService).consume(1L, 2);
        verify(jdbc).update(contains("DELETE FROM dead_letter WHERE task_id = ?"), eq(9L));
        verify(queuePublisher).publish(9L);
        verifyNoMoreInteractions(jdbc);
    }

    /** 批量重试口径：入队失败 → 走 DeadTaskRecorder 回滚 DEAD+补死信+退款 → 503 原样上抛。
     *  （DEAD/dead_letter/退款 的 SQL 断言在 DeadTaskRecorderTest，此处钉委托与 503 契约。） */
    @Test
    void publishFailureRollsBackViaDeadTaskRecorder() throws Exception {
        stubReviveRow(7L, 1L, 120);
        doThrow(new ApiException(503, "QUEUE_UNAVAILABLE", "队列暂不可用，请稍后重试"))
                .when(queuePublisher).publish(7L);

        ApiException ex = assertThrows(ApiException.class,
                () -> newSupport().reviveDeadThenPublishOrRollback(7L));

        assertEquals(503, ex.getStatus(), "503 必须原样上抛（契约不变）");
        assertEquals("QUEUE_UNAVAILABLE", ex.getCode());
        verify(deadTaskRecorder).markDead(eq(7L), eq("QUEUE_UNAVAILABLE"), contains("Redis 不可用"));
        verify(queuePublisher).publish(7L);
    }

    /** 入队成功 → 绝不回滚（半复活口径只在失败时存在）。 */
    @Test
    void publishSuccessNeverRollsBack() throws Exception {
        stubReviveRow(7L, 1L, 120);
        newSupport().reviveDeadThenPublishOrRollback(7L);
        verify(queuePublisher).publish(7L);
        verify(deadTaskRecorder, never()).markDead(anyLong(), anyString(), anyString());
    }

    /** T4.87 C4/C8：复活时配额不足 → 429 上抛、不重投、不清死信（事务回滚 ⇒ 真栈保持 DEAD/refunded=TRUE，A4 验）。 */
    @Test
    void reviveQuotaExceededThrows429AndDoesNotPublish() throws Exception {
        stubReviveRow(7L, 1L, 120);
        doThrow(new ApiException(429, "QUOTA_EXCEEDED", "今日配额已用尽"))
                .when(quotaService).consume(1L, 2);

        ApiException ex = assertThrows(ApiException.class,
                () -> newSupport().reviveDeadThenPublish(7L));
        assertEquals(429, ex.getStatus());
        verify(queuePublisher, never()).publish(anyLong());
        verify(jdbc, never()).update(contains("DELETE FROM dead_letter"), anyLong());
    }

    /** T4.87 C8：复活只扣一次（单一实现，不许别处再扣）。 */
    @Test
    void reviveConsumesExactlyOnce() throws Exception {
        stubReviveRow(9L, 1L, 120);
        newSupport().reviveDeadThenPublish(9L);
        verify(quotaService, times(1)).consume(1L, 2);
    }

    /** 幂等命中 PENDING 的重投：publish 一次 + touch 一次（T4.67 S9：防清扫器重复投递），logTag 沿用原文。 */
    @Test
    void publishPendingRepublishesTouchesAndLogsCallerText() {
        newSupport().publishPending(9L, "任务 9 重传命中 PENDING，重新入队（T4.50 自愈）");
        verify(queuePublisher).publish(9L);
        verify(stalePendingQueries).touch(9L);
    }

    /** T4.67 S9 变异对照：publishPending 的 touch 删掉时本用例必须红（否则清扫器 60s 内重复投递）。 */
    @Test
    void publishPendingTouchesImmediatelyAfterPublish() {
        newSupport().publishPending(9L, "任务 9 重传命中 PENDING，重新入队（T4.50 自愈）");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(queuePublisher, stalePendingQueries);
        order.verify(queuePublisher).publish(9L);
        order.verify(stalePendingQueries).touch(9L); /* 先投后 touch 的顺序也要钉住 */
    }
}
