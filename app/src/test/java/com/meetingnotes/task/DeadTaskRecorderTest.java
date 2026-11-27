package com.meetingnotes.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.87 C3/C8：置 DEAD 单一入口——DEAD + dead_letter + 退款同事务；退款幂等（已退过 false）不抛、DEAD 仍成立。
 * （DEAD⟺refunded 的真事务回滚/并发幂等由隔离栈 A2/A7 验；此处钉委托与单事务包装。）
 */
@ExtendWith(MockitoExtension.class)
class DeadTaskRecorderTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private QuotaService quotaService;
    @Mock
    private TransactionTemplate tx;

    @BeforeEach
    void stubTx() {
        lenient().when(tx.execute(any()))
                .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
    }

    private DeadTaskRecorder newRecorder() {
        return new DeadTaskRecorder(jdbc, quotaService, tx);
    }

    @Test
    void markDeadWritesDeadAndDeadLetterAndRefundsInOneTransaction() {
        when(quotaService.refundForTask(5L)).thenReturn(true);

        boolean refunded = newRecorder().markDead(5L, "ASR_ERROR", "服务不可达");

        assertEquals(true, refunded);
        verify(jdbc).update(contains("UPDATE task SET status = 'DEAD', error_code = ?, next_retry_at = NULL"),
                eq("ASR_ERROR"), eq(5L)); // T4.100/C7：DEAD 清 next_retry_at
        verify(jdbc).update(contains("INSERT INTO dead_letter (task_id, reason) VALUES (?, ?)"), eq(5L), eq("服务不可达"));
        verify(quotaService).refundForTask(5L);
        verify(tx).execute(any()); // 三步同一事务
    }

    @Test
    void markDeadIdempotentWhenAlreadyRefundedStillMarksDead() {
        when(quotaService.refundForTask(5L)).thenReturn(false); // 已退过 / quota 行不存在

        boolean refunded = newRecorder().markDead(5L, "QUEUE_UNAVAILABLE", "回滚");

        assertEquals(false, refunded, "已退过 → false，不重复退、不抛");
        verify(jdbc).update(contains("UPDATE task SET status = 'DEAD'"), eq("QUEUE_UNAVAILABLE"), eq(5L));
        verify(jdbc).update(contains("INSERT INTO dead_letter"), eq(5L), eq("回滚"));
    }
}
