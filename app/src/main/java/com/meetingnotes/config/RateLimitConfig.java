package com.meetingnotes.config;

import com.meetingnotes.web.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

/**
 * T4.80/P-29 前半：限流拦截器注册（与 {@link StaticCacheConfig} 并存的第二个
 * {@code WebMvcConfigurer}——多个 WebMvcConfigurer 并存是合法的）。
 *
 * <p>五项参数经 {@code @Value} 注入后在此 new 出 {@link RateLimitInterceptor}
 * （不做 @Component，避免两套创建路径，卡面 C2 二选一口径）。
 * {@code addPathPatterns("/api/v1/**")} 只圈 API 域，<b>路径/方法/身份判定全部交给拦截器内部</b>
 * （卡面 C2：只需一个注册点）。
 */
@Configuration
public class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    public RateLimitConfig(@Value("${app.rate-limit.enabled:true}") boolean enabled,
                           @Value("${app.rate-limit.window-sec:60}") long windowSec,
                           @Value("${app.rate-limit.login-per-window:10}") int loginPerWindow,
                           @Value("${app.rate-limit.register-per-window:5}") int registerPerWindow,
                           @Value("${app.rate-limit.tasks-per-window:30}") int tasksPerWindow) {
        this.rateLimitInterceptor = new RateLimitInterceptor(enabled, windowSec, loginPerWindow,
                registerPerWindow, tasksPerWindow, Clock.systemUTC());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/v1/**");
    }
}
