package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

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

    private TaskRequeueSupport newSupport() {
        return new TaskRequeueSupport(jdbc, queuePublisher);
    }

    @Test
    void reviveDeadThenPublishRunsVerbatimSqlAndPublishes() {
        newSupport().reviveDeadThenPublish(9L);

        verify(jdbc).update(contains("UPDATE task SET attempts = 0, status = 'PENDING', error_code = NULL"), eq(9L));
        verify(jdbc).update(contains("DELETE FROM dead_letter WHERE task_id = ?"), eq(9L));
        verify(queuePublisher).publish(9L);
        verifyNoMoreInteractions(jdbc);
    }

    /** 批量重试口径：入队失败 → 回滚成 DEAD（error_code=QUEUE_UNAVAILABLE）+ 补回死信 → 503 原样上抛。 */
    @Test
    void publishFailureRollsBackToDeadAndRestoresDeadLetter() {
        doThrow(new ApiException(503, "QUEUE_UNAVAILABLE", "队列暂不可用，请稍后重试"))
                .when(queuePublisher).publish(7L);

        ApiException ex = assertThrows(ApiException.class,
                () -> newSupport().reviveDeadThenPublishOrRollback(7L));

        assertEquals(503, ex.getStatus(), "503 必须原样上抛（契约不变）");
        assertEquals("QUEUE_UNAVAILABLE", ex.getCode());
        verify(jdbc).update(contains("status = 'DEAD', error_code = 'QUEUE_UNAVAILABLE'"), eq(7L));
        verify(jdbc).update(contains("INSERT INTO dead_letter"), eq(7L), contains("Redis 不可用"));
        verify(queuePublisher).publish(7L);
    }

    /** 入队成功 → 绝不出现回滚 SQL（半复活口径只在失败时存在）。 */
    @Test
    void publishSuccessNeverRollsBack() {
        newSupport().reviveDeadThenPublishOrRollback(7L);
        verify(queuePublisher).publish(7L);
        verify(jdbc, never()).update(contains("status = 'DEAD'"), eq(7L));
        verify(jdbc, never()).update(contains("INSERT INTO dead_letter"), anyString(), anyString());
    }

    /** 幂等命中 PENDING 的重投：publish 一次，logTag 由调用方传入（文案沿用各处原文）。 */
    @Test
    void publishPendingRepublishesAndLogsCallerText() {
        newSupport().publishPending(9L, "任务 9 重传命中 PENDING，重新入队（T4.50 自愈）");
        verify(queuePublisher).publish(9L);
    }
}
