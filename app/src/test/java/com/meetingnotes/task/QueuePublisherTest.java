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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T4.60 B3：QueuePublisher 的直接测试——此前全仓只 mock 它、DataAccessException→503 分支从未执行。
 * 五条：头插 / 尾插（且不碰头插）/ 头插异常→503 / 两个 tryX 不抛且返回 false（真实行为断言，无恒真）/
 * 队列 key 为 null/空白时构造器直接拒绝（T4.67-fix F1 的明确契约，不再给未定义行为上锁）。
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
        /* lenient：null-key 用例在构造器就抛异常，消费不到这个共享桩（其余用例仍受严格桩约束） */
        lenient().when(redis.opsForList()).thenReturn(listOps);
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
        assertDoesNotThrow(() -> assertFalse(newPublisher(QUEUE_KEY).tryPublish(7L), "tryPublish 失败必须返回 false"),
                "tryPublish 不得向调用方抛出（T4.67-fix F1：真实行为断言，不用恒真）");
        assertDoesNotThrow(() -> assertFalse(newPublisher(QUEUE_KEY).tryPublishToTail(7L), "tryPublishToTail 失败必须返回 false"),
                "tryPublishToTail 不得向调用方抛出");
        verify(listOps).leftPush(eq(QUEUE_KEY), anyString());
        verify(listOps).rightPush(eq(QUEUE_KEY), anyString());
    }

    /** 队列 key 为 null/空白：构造器直接拒绝（T4.67-fix F1 的明确契约），四个入队方法不再各自处理 null key。 */
    @Test
    void nullOrBlankQueueKeyIsRejectedByConstructor() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> newPublisher(null));
        assertTrue(ex.getMessage().contains("queueKey"), "异常信息应指明是 queueKey 配置问题：" + ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> newPublisher(""));
        assertThrows(IllegalArgumentException.class, () -> newPublisher("   "));
        verifyNoInteractions(listOps);
    }
}
