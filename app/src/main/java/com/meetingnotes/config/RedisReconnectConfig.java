package com.meetingnotes.config;

import io.lettuce.core.resource.Delay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * T4.91/S1：Lettuce 重连退避收紧为 min→max（默认 500ms→5s）。
 * <ul>
 *   <li>① 扩展点是 {@link ClientResourcesBuilderCustomizer}（真栈 watchdog DEBUG 日志实证：
 *       ConnectionWatchdog 构造取 {@code clientResources.reconnectDelay()}，5000 配置下停机
 *       重连间隔封顶 5-6s 与 cap 一致；首版曾疑其无效，实为对照组实验设计缺陷，见卡面）。
 *       Boot 3.5 无 ClientOptions bean 钩子；自定义 ClientOptions/LettuceConnectionFactory 会连带覆盖
 *       {@code spring.data.redis.timeout=3s}（T4.90 成果），属严重回归。</li>
 *   <li>② 退避：Lettuce 默认 exponential 100ms→30s（T4.90 实测长停后恢复 14–24s），
 *       本卡改成 min→max 把长停恢复压到个位数秒。</li>
 *   <li>③ 影响所有 Redis 连接（队列 BLPOP、清扫、回执 ZSET），属全局行为，不是只改 worker。</li>
 * </ul>
 * min&lt;=0 或 max&lt;min 时 WARN 回退 500/5000，不抛异常（Redis 抖动不能让应用起不来）。
 */
@Configuration(proxyBeanMethods = false)
public class RedisReconnectConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisReconnectConfig.class);
    private static final long DEFAULT_MIN_MS = 500;
    private static final long DEFAULT_MAX_MS = 5000;

    @Bean
    ClientResourcesBuilderCustomizer redisReconnectDelayCustomizer(
            @Value("${app.redis.reconnect-min-ms:500}") long minMs,
            @Value("${app.redis.reconnect-max-ms:5000}") long maxMs) {
        long lo = minMs;
        long hi = maxMs;
        if (lo <= 0 || hi < lo) {
            log.warn("app.redis.reconnect-min-ms/max-ms 非法（{}/{}），回退到 {}/{}", minMs, maxMs, DEFAULT_MIN_MS, DEFAULT_MAX_MS);
            lo = DEFAULT_MIN_MS;
            hi = DEFAULT_MAX_MS;
        }
        final long lower = lo;
        final long upper = hi;
        return builder -> builder.reconnectDelay(
                Delay.exponential(Duration.ofMillis(lower), Duration.ofMillis(upper), 2, TimeUnit.MILLISECONDS));
    }
}