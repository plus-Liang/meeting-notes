package com.meetingnotes.common;

/**
 * 请求线程的当前用户（T4.37a）：ApiTokenFilter 认证成功后写入，finally 必须 clear()。
 *
 * <p>替代散在 16 个类里的 DEFAULT_USER_ID=1（D-040 单用户时代的遗留）。
 * {@link #current()} 未设置时**抛 IllegalStateException，不静默回退 1**——防止漏改的端点
 * 悄悄变回单用户（卡面口径）。
 *
 * <p>ThreadLocal 只在请求线程用；异步链路（worker/StructuringService）**不许读它**，
 * 按 taskId 查 task.user_id 显式解析归属（卡面第 7 条）。
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(long userId) {
        CURRENT.set(userId);
    }

    /** 当前用户 id；未设置（异步线程/漏接过滤器）→ 显式失败，不回退 1。 */
    public static long current() {
        Long userId = CURRENT.get();
        if (userId == null) {
            throw new IllegalStateException(
                    "UserContext 未设置：当前线程不是已认证的请求线程（异步链路必须按 taskId 显式解析 user_id）");
        }
        return userId;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
