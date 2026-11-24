package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * T4.81/P-29 后半：登录失败计数与账号锁定（从 AuthService 抽出——卡面许可的第 9 个文件）。
 *
 * <p>语义：同一账号连续失败 {@code max-failures} 次 → 锁 {@code lockout-minutes} 分钟
 * （**落库不落内存**：重启/多实例都保持一致，避免「重启即解锁」绕过）；锁定期内正确密码也 423。
 * 与 T4.80 的限流互补：限流挡「短时高频」（按 IP/用户频率），本类挡「每分钟试 9 次」的慢速撞库
 * （按账号维度）。
 *
 * <p>SQL 口径（卡面 C2）：**两步式 UPDATE + SELECT，禁用 {@code UPDATE .. RETURNING}**——
 * PG 专用语法，H2 真库测试跑不了（D-085，同 StartupRecovery 先例）；真实 SQL 语义由
 * {@code LoginLockoutDbTest}（真 H2）覆盖。锁定时点由 JVM 时钟算好作为参数传入
 * （{@code make_interval(mins => ?)} 同为 PG 专用字面量；JVM/PG 同宿主同时钟，D-067 口径同
 * {@code StalePendingQueries.cutoff}）。
 *
 * <p>防误配：{@code max-failures}/{@code lockout-minutes} ≤ 0 启动期 {@code log.warn} 并回退默认 5/15
 * （与 stale-running-minutes / visibility-timeout-sec / 限流同口径，K-175）。
 * 日志只写 userId 与计数，绝不写密码/哈希（卡面禁止项 4）。
 */
@Service
public class LoginLockoutService {

    private static final Logger log = LoggerFactory.getLogger(LoginLockoutService.class);

    static final int DEFAULT_MAX_FAILURES = 5;
    static final int DEFAULT_LOCKOUT_MINUTES = 15;

    private final JdbcTemplate jdbc;
    private final int maxFailures;
    private final int lockoutMinutes;
    private final Clock clock;

    /** Spring 入口（Clock 默认 systemUTC）；测试走四参构造做时间旅行（卡面 C2 口径）。 */
    @Autowired
    public LoginLockoutService(JdbcTemplate jdbc,
                               @Value("${app.auth.login-max-failures:" + DEFAULT_MAX_FAILURES + "}") int maxFailures,
                               @Value("${app.auth.lockout-minutes:" + DEFAULT_LOCKOUT_MINUTES + "}") int lockoutMinutes) {
        this(jdbc, maxFailures, lockoutMinutes, Clock.systemUTC());
    }

    public LoginLockoutService(JdbcTemplate jdbc, int maxFailures, int lockoutMinutes, Clock clock) {
        this.jdbc = jdbc;
        int max = maxFailures;
        int minutes = lockoutMinutes;
        if (max <= 0) {
            log.warn("app.auth.login-max-failures={} 非法（必须 > 0），回退到 {} 次", max, DEFAULT_MAX_FAILURES);
            max = DEFAULT_MAX_FAILURES;
        }
        if (minutes <= 0) {
            log.warn("app.auth.lockout-minutes={} 非法（必须 > 0），回退到 {} 分钟", minutes, DEFAULT_LOCKOUT_MINUTES);
            minutes = DEFAULT_LOCKOUT_MINUTES;
        }
        this.maxFailures = max;
        this.lockoutMinutes = minutes;
        this.clock = clock;
    }

    public int maxFailures() {
        return maxFailures;
    }

    /** 是否处于锁定期：locked_until 晚于当前时钟（过去时间不算锁定，卡面 C5 ④）。 */
    public boolean isLocked(Timestamp lockedUntil) {
        return lockedUntil != null && lockedUntil.after(Timestamp.from(clock.instant()));
    }

    /** 剩余锁定分钟：向上取整、至少 1（423 文案用，不泄露失败次数）。 */
    public int remainingMinutes(Timestamp lockedUntil) {
        long remainMs = lockedUntil.getTime() - clock.instant().toEpochMilli();
        return (int) Math.max(1, (remainMs + 60_000 - 1) / 60_000);
    }

    /** 423 LOCKED 异常（文案不提示密码对错、不泄露失败/剩余次数——卡面 C2 的刻意取舍：可用性优先）。 */
    public ApiException lockedException() {
        return new ApiException(423, "LOCKED",
                "账号已锁定，请 " + lockoutMinutes + " 分钟后再试，或联系管理员解锁");
    }

    /**
     * 记录一次登录失败：两步式（卡面 C2）——
     * ① UPDATE 累加（新计数达上限时把 locked_until 一次算好写入）；
     * ② SELECT 读回新值判定是否上锁（读不到 = 行已被删，按已锁定处理，不抛未捕获异常）。
     *
     * @return true = 本次失败后账号进入锁定（含读不到行的防御路径）
     */
    public boolean recordFailure(long userId) {
        Timestamp lockFromNow = Timestamp.from(clock.instant().plus(Duration.ofMinutes(lockoutMinutes)));
        jdbc.update("UPDATE app_user SET failed_login_attempts = failed_login_attempts + 1, "
                        + "locked_until = CASE WHEN failed_login_attempts + 1 >= ? THEN ? ELSE locked_until END "
                        + "WHERE id = ?",
                maxFailures, lockFromNow, userId);
        List<Integer> attempts = jdbc.query("SELECT failed_login_attempts FROM app_user WHERE id = ?",
                (rs, i) -> rs.getInt(1), userId);
        if (attempts.isEmpty()) {
            log.warn("登录失败计数读回为空（账号行可能已被删除），按已锁定处理：userId={}", userId);
            return true;
        }
        boolean locked = attempts.get(0) >= maxFailures;
        if (locked) {
            log.warn("账号登录连续失败 {} 次达到上限，锁定 {} 分钟：userId={}", attempts.get(0), lockoutMinutes, userId);
        }
        return locked;
    }

    /** 登录成功清零/清空（在签发会话之前调用）；返回影响行数（0 = 用户不存在，解锁端点据此 404）。 */
    public int reset(long userId) {
        return jdbc.update(
                "UPDATE app_user SET failed_login_attempts = 0, locked_until = NULL WHERE id = ?", userId);
    }
}
