package com.meetingnotes.config;

import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.Delay;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.91/S1 契约：customizer 把 ClientResources.Builder 的重连退避设为 min→max（指数，cap=max）；
 * 真栈 watchdog DEBUG 日志已实证该值被 ConnectionWatchdog 使用（间隔封顶 = cap）。
 * 非法参数（min<=0 / max<min）WARN 回退 500/5000，不抛异常。build() 后经公开 reconnectDelay() 断言序列值。
 */
class RedisReconnectConfigTest {

    private Delay delayOf(long minMs, long maxMs) {
        ClientResourcesBuilderCustomizer customizer =
                new RedisReconnectConfig().redisReconnectDelayCustomizer(minMs, maxMs);
        ClientResources.Builder builder = ClientResources.builder();
        customizer.customize(builder);
        // 走公开 API：build() 后读 reconnectDelay()（Builder 是接口、实现类字段类型不保证，反射脆弱）
        ClientResources resources = builder.build();
        try {
            return resources.reconnectDelay();
        } finally {
            resources.shutdown(0, 0, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private void assert500To5000(Delay d) {
        // Lettuce 6.6 实测语义：createDelay(n) = clamp(2^(n-1) ms, lower, upper)，返回 Duration。
        // lower=500 ⇒ 前 9 次均 500ms（2^0..2^8 < 500 被下限钳住），第 10 次起 512/1024/2048/4096，第 14 次触顶 5000。
        assertEquals(500, d.createDelay(1).toMillis(), "第 1 次重连延迟应为下限 500ms");
        assertEquals(500, d.createDelay(9).toMillis(), "2^8=256<500，仍被下限钳住");
        assertEquals(512, d.createDelay(10).toMillis(), "第 10 次 2^9=512ms");
        assertEquals(1024, d.createDelay(11).toMillis(), "第 11 次翻倍 1024ms");
        assertEquals(2048, d.createDelay(12).toMillis(), "第 12 次 2048ms");
        assertEquals(5000, d.createDelay(14).toMillis(), "第 14 次 2^13=8192 触顶 5000ms");
        assertEquals(5000, d.createDelay(100).toMillis(), "长 outage 后仍封顶 5000ms（不得回到 30s）");
        assertTrue(d.createDelay(10).toMillis() < d.createDelay(12).toMillis(), "退避必须单调递增");
    }

    @Test
    void defaultParametersProduce500To5000Backoff() throws Exception {
        assert500To5000(delayOf(500, 5000));
    }

    @Test
    void nonPositiveMinFallsBackToDefault() throws Exception {
        assert500To5000(delayOf(0, 99999));
        assert500To5000(delayOf(-5, 99999));
    }

    @Test
    void maxBelowMinFallsBackToDefault() throws Exception {
        assert500To5000(delayOf(1000, 200));
    }
}