package com.meetingnotes.task;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.60 B3：QueuePublisher 的直接测试——此前全仓只 mock 它、DataAccessException→503 分支从未执行。
 * 五条：头插 / 尾插（且不碰头插）/ 头插异常→503 / 两个 tryX 不抛 / 队列 key 为 null 时按现状不抛。
 * mock 的是依赖（StringRedisTemplate），被测对象 QueuePublisher 是真实实例。
 */
@ExtendWith(MockitoExtension.class)
class QueuePublisherTest {

    private static final String QUEUE_KEY = "meetingnotes:task-queue";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ListOperations<String, String> listOps;

    private QueuePublisher newPublisher(String queueKey) {
        return new QueuePublisher(redis, queueKey);
    }

    @BeforeEach
    void stubOps() {
        when(redis.opsForList()).thenReturn(listOps);
    }

    @Test
    void publishGoesToHeadViaLeftPush() {
        newPublisher(QUEUE_KEY).publish(7L);
        verify(listOps).leftPush(QUEUE_KEY, "7");
    }

    /** T4.60 B1：尾插走 rightPush，且绝不碰头插（重投不得插队）。 */
    @Test
    void publishToTailGoesToTailViaRightPush() {
        newPublisher(QUEUE_KEY).publishToTail(7L);
        verify(listOps).rightPush(QUEUE_KEY, "7");
        verify(listOps, never()).leftPush(anyString(), anyString());
    }

    /** DataAccessException → 503 QUEUE_UNAVAILABLE（原文案逐字），这是 T4.60 前从未被执行的分支。 */
    @Test
    void publishMapsDataAccessExceptionTo503QueueUnavailable() {
        when(listOps.leftPush(eq(QUEUE_KEY), anyString()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        ApiException ex = assertThrows(ApiException.class, () -> newPublisher(QUEUE_KEY).publish(7L));
        assertEquals(503, ex.getStatus());
        assertEquals("QUEUE_UNAVAILABLE", ex.getCode());
        assertEquals("队列暂不可用，请稍后重试", ex.getMessage());
    }

    /** tryPublish / tryPublishToTail 在同一异常下返回 false，不向上抛。 */
    @Test
    void tryVariantsReturnFalseOnRedisFailure() {
        when(listOps.leftPush(eq(QUEUE_KEY), anyString()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        when(listOps.rightPush(eq(QUEUE_KEY), anyString()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        assertFalse(newPublisher(QUEUE_KEY).tryPublish(7L));
        assertFalse(newPublisher(QUEUE_KEY).tryPublishToTail(7L));
        assertTrue(true, "两处均未抛出（走到断言即证明）");
    }

    /** 队列 key 为 null/空：构造器允许传入，现状是交给 redis 模板处理（mock 下不抛）——按现状断言，不顺手改。 */
    @Test
    void nullQueueKeyBehavesAsCurrentImplementation() {
        QueuePublisher nullKey = newPublisher(null);
        assertDoesNotThrow(() -> nullKey.publish(7L));
        assertDoesNotThrow(() -> nullKey.publishToTail(7L));
        verify(listOps).leftPush(null, "7");
        verify(listOps).rightPush(null, "7");
    }
}
