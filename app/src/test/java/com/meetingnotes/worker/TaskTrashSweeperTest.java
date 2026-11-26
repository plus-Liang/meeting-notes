package com.meetingnotes.worker;

import com.meetingnotes.task.TaskTrashService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T4.96/C11：回收站清扫器编排语义——enabled=false 整轮跳过（不查库）；enabled=true 每轮
 * purgeExpired(200)，日志带 purged/retentionDays；启动钩子同样跑一次。
 * retention <1 回退 30（与 TaskTrashService.sanitizeRetention 同口径，此处验证字段值）。
 */
@ExtendWith(MockitoExtension.class)
class TaskTrashSweeperTest {

    @Mock
    private TaskTrashService taskTrashService;

    private TaskTrashSweeper newSweeper(boolean enabled, int configuredRetention) {
        return new TaskTrashSweeper(taskTrashService, enabled, configuredRetention);
    }

    @Test
    void disabledSweeperDoesNothingAtAll() {
        TaskTrashSweeper sweeper = newSweeper(false, 30);

        sweeper.sweepExpiredTrash();
        sweeper.sweepOnStartup();

        verifyNoInteractions(taskTrashService); // 整轮跳过：连库都不查
    }

    @Test
    void periodicSweepPurgesWithBatchLimit() {
        when(taskTrashService.purgeExpired(200)).thenReturn(1);

        newSweeper(true, 30).sweepExpiredTrash();

        verify(taskTrashService).purgeExpired(eq(200)); // 每轮上限 200（D-245 S4）
    }

    @Test
    void startupHookAlsoSweepsOnce() {
        when(taskTrashService.purgeExpired(200)).thenReturn(0);

        newSweeper(true, 30).sweepOnStartup();

        verify(taskTrashService).purgeExpired(anyInt());
    }

    /** retention <1 回退 30：字段值即证据（清扫调用仍透传 limit，retention 判定在 service 内）。 */
    @Test
    void nonPositiveRetentionFallsBackToThirty() {
        TaskTrashSweeper sweeper = newSweeper(true, 0);
        ReflectionTestUtils.setField(sweeper, "enabled", true);

        int days = (Integer) ReflectionTestUtils.getField(sweeper, "retentionDays");
        assertEquals(30, days, "0 配置必须回退 30（一次误配不能等于永久删除）");

        TaskTrashSweeper negative = newSweeper(true, -5);
        assertEquals(30, (int) (Integer) ReflectionTestUtils.getField(negative, "retentionDays"));
    }

    /** disabled 时启动钩子打一行 INFO（可 grep），且不触达 service。 */
    @Test
    void disabledStartupLogsSkipWithoutQuerying() {
        TaskTrashSweeper sweeper = newSweeper(false, 30);
        sweeper.sweepOnStartup();
        verify(taskTrashService, never()).purgeExpired(anyInt());
    }
}
