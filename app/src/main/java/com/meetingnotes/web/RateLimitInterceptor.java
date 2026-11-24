package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import com.meetingnotes.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T4.80/P-29 前半：接口限流——固定窗口（fixed window）计数，只拦四条写入路径：
 * POST /api/v1/auth/login（按客户端 IP，默认 10/窗口）、POST /api/v1/auth/register（按 IP，默认 5/窗口）、
 * POST /api/v1/tasks 与 /api/v1/tasks/batch（按 {@link UserContext} 用户 id，默认 30/窗口）。
 * 其余方法/路径与 OPTIONS 预检一律放行（不做全局限流，禁止项 7）。
 *
 * <p>实现：{@code ConcurrentHashMap<String, Window>}，同一 key 的读改写经 {@code compute} 原子完成
 * （Window 不可变 record，整条替换，无锁）；窗口滚动时自然重置计数。防内存无界：map 超过
 * {@value #CLEANUP_THRESHOLD} 条时顺手清一遍过期条目（不挂 @Scheduled，靠流量触发，禁止项 4）。
 * 超限抛 {@code ApiException(429, "RATE_LIMITED")}，抛前把窗口剩余秒数写入请求属性
 * {@code rateLimitRetryAfterSec}（由 {@link ApiExceptionHandler} 转成 Retry-After 头）。
 *
 * <p><b>单实例语义</b>：计数在 JVM 内存（本卡不引 Redis、不引新依赖，禁止项 3），多实例部署时
 * 每个实例独立计数，真实上限 = 配置值 × 实例数；扩容到多实例时须改用 Redis 计数，或把各实例的
 * 配置值除以实例数（登记 P-23 扩容清单）。登录失败计数 + 账号锁定属 P-29 后半（另一张卡），
 * 本类只按请求频率限流，不做失败判定，也不改登录/注册业务行为（禁止项 2）。
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 请求属性名：窗口剩余秒数（ApiExceptionHandler 读它设 Retry-After 头）。 */
    public static final String RETRY_AFTER_ATTR = "rateLimitRetryAfterSec";

    /** 过期清理触发阈值：map 超过这么多条时顺手清一次（防内存无界）。 */
    static final int CLEANUP_THRESHOLD = 10_000;

    /** 限流桶：与卡面 C1 表一一对应。 */
    enum Bucket { LOGIN, REGISTER, TASKS }

    private final boolean enabled;
    private final long windowMillis;
    private final int loginLimit;
    private final int registerLimit;
    private final int tasksLimit;
    private final Clock clock;

    /** 计数容器：key = 桶名:身份；Window 不可变，经 compute 原子整条替换。 */
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /** 固定窗口计数（不可变 record：windowEndMillis 起算的 count 次，过期即滚动重置）。 */
    private record Window(long windowEndMillis, int count) {}

    public RateLimitInterceptor(boolean enabled, long windowSec, int loginLimit, int registerLimit,
                                int tasksLimit, Clock clock) {
        this.enabled = enabled;
        this.windowMillis = windowSec * 1000L;
        this.loginLimit = loginLimit;
        this.registerLimit = registerLimit;
        this.tasksLimit = tasksLimit;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Bucket bucket = resolveBucket(request);
        if (bucket == null) {
            return true;
        }
        String identity = identity(request, bucket);
        if (identity == null) {
            return true; // tasks 桶取不到用户：放行，不因身份缺失误伤（卡面 C1）
        }
        if (windows.size() > CLEANUP_THRESHOLD) { // 顺手清理过期窗口（防内存无界，无 @Scheduled）
            long now = clock.millis();
            windows.entrySet().removeIf(e -> e.getValue().windowEndMillis() <= now);
        }
        int limit = limitOf(bucket);
        Window window = windows.compute(bucket.name() + ":" + identity, (k, old) -> {
            long now = clock.millis();
            if (old == null || old.windowEndMillis() <= now) {
                return new Window(now + windowMillis, 1); // 窗口滚动：重置计数
            }
            return new Window(old.windowEndMillis(), old.count() + 1);
        });
        if (window.count() > limit) {
            request.setAttribute(RETRY_AFTER_ATTR, String.valueOf(remainingSeconds(window.windowEndMillis())));
            throw new ApiException(429, "RATE_LIMITED", "请求过于频繁，请稍后重试");
        }
        return true;
    }

    /** 只拦 C1 表里四条写入路径（POST + 精确路径）；命中返回桶，否则 null 放行。 */
    private Bucket resolveBucket(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        return switch (request.getRequestURI()) {
            case "/api/v1/auth/login" -> Bucket.LOGIN;
            case "/api/v1/auth/register" -> Bucket.REGISTER;
            case "/api/v1/tasks", "/api/v1/tasks/batch" -> Bucket.TASKS;
            default -> null;
        };
    }

    /** 身份：login/register 按客户端 IP（{@code getRemoteAddr()}）；tasks 按用户 id（未设置/id≤0 → null 放行）。 */
    private String identity(HttpServletRequest request, Bucket bucket) {
        return switch (bucket) {
            case LOGIN, REGISTER -> request.getRemoteAddr();
            case TASKS -> {
                try {
                    long userId = UserContext.current();
                    yield userId <= 0 ? null : "user-" + userId;
                } catch (IllegalStateException unset) {
                    yield null; // UserContext.current() 未设置时显式抛出（非回退 1）：按卡面放行不误伤
                }
            }
        };
    }

    private int limitOf(Bucket bucket) {
        return switch (bucket) {
            case LOGIN -> loginLimit;
            case REGISTER -> registerLimit;
            case TASKS -> tasksLimit;
        };
    }

    /** 窗口剩余秒数：向上取整、至少 1（Retry-After 语义，N8）。 */
    private int remainingSeconds(long windowEndMillis) {
        long remainMs = windowEndMillis - clock.millis();
        return (int) Math.max(1, (remainMs + 999) / 1000);
    }
}
