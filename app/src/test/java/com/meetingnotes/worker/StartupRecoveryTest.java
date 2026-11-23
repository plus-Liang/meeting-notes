package com.meetingnotes.worker;

import com.meetingnotes.task.QueuePublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T4.50/T4.62 S1：启动恢复——RUNNING 回收 + 积压 PENDING 重投，全部经 QueuePublisher.tryPublish
 * （永不抛，启动期 Redis 未就绪不拖垮上下文）；判据/刷新语句在 StalePendingQueries（本测试只验委托与顺序）。
 * A2 负向：pushBack 若改回首插直调或误用 publish（会抛），本测试的 tryPublish 断言立即变红。
 * T4.78/P-01：RUNNING 回收判据改为「僵尸 RUNNING」（updated_at 早于 stale-running-minutes，
 * 默认 5 分钟）——stub 同步换 query(String, RowMapper, Object...) varargs 重载；判据语义由真 H2 的
 * StartupRecoveryDbTest 覆盖，本类只验委托/顺序/兜底与 ≤0 回退。
 */
@ExtendWith(MockitoExtension.class)
class StartupRecoveryTest {

    private static final String QUEUE_KEY = "meetingnotes:task-queue";

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private QueuePublisher queuePublisher;
    @Mock
    private StalePendingQueries stalePendingQueries;

    private StartupRecovery newRecovery() {
        return new StartupRecovery(jdbc, queuePublisher, stalePendingQueries);
    }

    @Test
    void recoversRunningAndRequeuesStalePending() {
        when(jdbc.query(contains("WHERE status = 'RUNNING'"), ArgumentMatchers.<RowMapper<Long>>any(),
                ArgumentMatchers.any()))
                .thenReturn(List.of(7L));
        when(stalePendingQueries.findStalePending(ArgumentMatchers.any())).thenReturn(List.of(9L));

        newRecovery().run(null);

        /* T4.62 S1/A2：重投必须走 tryPublish（永不抛），且绝无 Redis 直调（redis 不再是本类的依赖） */
        verify(queuePublisher, times(1)).tryPublish(7L);
        verify(queuePublisher, times(1)).tryPublish(9L);
        verify(queuePublisher, never()).tryPublishToTail(7L);
        verify(queuePublisher, never()).publish(7L);
        /* 启动口径：先 touch 全部、再逐条投递（顺序语义与运行期 sweeper 相反，不许合并） */
        InOrder order = inOrder(stalePendingQueries, queuePublisher);
        order.verify(stalePendingQueries).touch(9L);
        order.verify(queuePublisher).tryPublish(9L);
    }

    @Test
    void emptyDatabaseSkipsRecoveryQuietly() {
        when(jdbc.query(contains("WHERE status = 'RUNNING'"), ArgumentMatchers.<RowMapper<Long>>any(),
                ArgumentMatchers.any()))
                .thenThrow(new DataAccessResourceFailureException("relation does not exist"));

        assertDoesNotThrow(() -> newRecovery().run(null));
        verify(queuePublisher, never()).tryPublish(7L);
        verify(queuePublisher, never()).tryPublish(9L);
    }

    @Test
    void redisDownDuringRecoveryDoesNotCrash() {
        when(jdbc.query(contains("WHERE status = 'RUNNING'"), ArgumentMatchers.<RowMapper<Long>>any(),
                ArgumentMatchers.any()))
                .thenReturn(List.of(7L));
        when(stalePendingQueries.findStalePending(ArgumentMatchers.any())).thenReturn(List.of(9L));
        /* tryPublish 的失败语义 = 返回 false（publish 已把 DataAccessException 转 503 内部消化，永不外抛） */
        when(queuePublisher.tryPublish(7L)).thenReturn(false);

        assertDoesNotThrow(() -> newRecovery().run(null));
        verify(queuePublisher).tryPublish(9L); /* 单条失败不中断：其余 id 仍逐条尝试 */
    }

    /** T4.67 S2：tryPublish 抛非 ApiException 的运行时异常（如连接工厂未就绪）→ pushBack 宽兜底不向上抛。 */
    @Test
    void nonApiRuntimeExceptionFromTryPublishDoesNotCrashStartup() {
        when(jdbc.query(contains("WHERE status = 'RUNNING'"), ArgumentMatchers.<RowMapper<Long>>any(),
                ArgumentMatchers.any()))
                .thenReturn(List.of(7L));
        doThrow(new IllegalStateException("connection factory not initialized"))
                .when(queuePublisher).tryPublish(7L);

        assertDoesNotThrow(() -> newRecovery().run(null));
        verify(queuePublisher).tryPublish(7L); /* 宽兜底吞掉后继续逐条尝试 */
    }

    /** T4.78：阈值 ≤0 属误配（0 = 全表回收，正是本卡要消灭的行为）→ WARN 并回退 5 分钟（cutoff=now-5min）。 */
    @Test
    void nonPositiveStaleRunningMinutesFallsBackToFive() {
        StartupRecovery recovery = newRecovery();
        ReflectionTestUtils.setField(recovery, "recoveryStaleMinutes", 0L);
        when(jdbc.query(contains("WHERE status = 'RUNNING'"), ArgumentMatchers.<RowMapper<Long>>any(),
                ArgumentMatchers.any())).thenReturn(List.of(7L));
        ArgumentCaptor<Timestamp> cutoff = ArgumentCaptor.forClass(Timestamp.class);

        recovery.run(null);

        verify(jdbc).query(contains("AND updated_at < ?"), ArgumentMatchers.<RowMapper<Long>>any(),
                cutoff.capture());
        long diffMin = (System.currentTimeMillis() - cutoff.getValue().getTime()) / 60000L;
        assertTrue(diffMin >= 4 && diffMin <= 6, "≤0 回退后 cutoff 应为 now-5min，实测 " + diffMin + "min");
    }
}
