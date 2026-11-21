package com.meetingnotes.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.50：启动恢复扩展——RUNNING 回收之外，积压 PENDING（updated_at 超 2 分钟）也重新入队；
 * 空库（无表）/ Redis 未就绪不得拖垮应用上下文（D-035 口径）。
 */
@ExtendWith(MockitoExtension.class)
class StartupRecoveryTest {

    private static final String QUEUE_KEY = "meetingnotes:task-queue";

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ListOperations<String, String> listOps;

    private StartupRecovery newRecovery() {
        return new StartupRecovery(jdbc, redis, QUEUE_KEY);
    }

    @Test
    void recoversRunningAndRequeuesStalePending() {
        when(jdbc.query(contains("WHERE status = 'RUNNING'"), ArgumentMatchers.<RowMapper<Long>>any()))
                .thenReturn(List.of(7L));
        when(jdbc.query(contains("status = 'PENDING'"), ArgumentMatchers.<RowMapper<Long>>any(),
                ArgumentMatchers.any())).thenReturn(List.of(9L));
        when(redis.opsForList()).thenReturn(listOps);

        newRecovery().run(null);

        verify(listOps).leftPush(QUEUE_KEY, "7");
        verify(listOps).leftPush(QUEUE_KEY, "9");
    }

    @Test
    void emptyDatabaseSkipsRecoveryQuietly() {
        when(jdbc.query(contains("WHERE status = 'RUNNING'"), ArgumentMatchers.<RowMapper<Long>>any()))
                .thenThrow(new DataAccessResourceFailureException("relation does not exist"));

        assertDoesNotThrow(() -> newRecovery().run(null));
        verify(redis, never()).opsForList();
    }

    @Test
    void redisDownDuringRecoveryDoesNotCrash() {
        when(jdbc.query(contains("WHERE status = 'RUNNING'"), ArgumentMatchers.<RowMapper<Long>>any()))
                .thenReturn(List.of(7L));
        when(jdbc.query(contains("status = 'PENDING'"), ArgumentMatchers.<RowMapper<Long>>any(),
                ArgumentMatchers.any())).thenReturn(List.of(9L));
        when(redis.opsForList()).thenReturn(listOps);
        when(listOps.leftPush(anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));

        assertDoesNotThrow(() -> newRecovery().run(null));
    }
}
