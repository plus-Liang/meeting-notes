package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 账号业务（T4.37a）：注册（含 1 号账号认领）/ 登录 / 注销 / 我的信息 / 会话管理。
 * 口径：用户名 trim 后 3–32 字符、字符集 {@code [A-Za-z0-9_\-\u4e00-\u9fa5]}、大小写不敏感唯一
 * （409 USERNAME_TAKEN，**并发安全**：以唯一索引为最终闸门，K-91）；密码去空白后 8–64 字符；
 * 1 号账号未认领（password_hash='!'）→ **T4.93 起认领只走安装向导**（register 未认领时 403
 * SETUP_REQUIRED，原「首个注册者自动成为管理员」的接管洞已堵；向导必须带一次性 SETUP_CODE，
 * {@link #setupClaimAdmin}，安装码是原子认领前的一道闸，不是替代）；其余注册受
 * {@code AUTH_REGISTRATION_ENABLED} 控制（关闭 → 403 REGISTRATION_DISABLED），新用户 role=USER；
 * 登录失败统一 401 INVALID_CREDENTIALS（不泄露用户名是否存在）；T4.71：登录/注册签发新会话进
 * user_session（多设备并存互不踢），logout 只删当前令牌的会话，超出 max-sessions 淘汰最旧。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 占位密码哈希：1 号账号未认领的标记（V8 迁移写入）。 */
    static final String UNCLAIMED_MARKER = "!";

    private final JdbcTemplate jdbc;
    private final PasswordHasher passwordHasher;
    private final TokenService tokenService;
    private final LoginLockoutService loginLockout;
    private final boolean registrationEnabled;
    private final int maxSessions;
    /** T4.93：首启安装码（SETUP_CODE）。空 = 禁止认领（安全侧默认，K-183：未配置时任何码都拒绝）。 */
    private final String setupCode;

    public AuthService(JdbcTemplate jdbc,
                       PasswordHasher passwordHasher,
                       TokenService tokenService,
                       LoginLockoutService loginLockout,
                       @Value("${app.auth.registration-enabled}") boolean registrationEnabled,
                       @Value("${app.auth.max-sessions}") int maxSessions,
                       @Value("${app.setup.code:}") String setupCode) {
        this.jdbc = jdbc;
        this.passwordHasher = passwordHasher;
        this.tokenService = tokenService;
        this.loginLockout = loginLockout;
        this.registrationEnabled = registrationEnabled;
        this.maxSessions = maxSessions;
        this.setupCode = setupCode == null ? "" : setupCode.trim();
    }

    /** 统一响应（对外 snake_case）：token 明文只在这里出现一次。 */
    public record AuthSession(long userId, String username, String role, String token) {
    }

    public record UserInfo(long userId, String username, String role) {
    }

    /** login 查询中间态（password_hash 不出服务）；T4.81 起含失败计数/锁定时间（V11）。 */
    record Credentials(long userId, String username, String passwordHash, String role,
                       int failedAttempts, java.sql.Timestamp lockedUntil) {
    }

    public AuthSession register(String rawUsername, String rawPassword) {
        String username = requireValidUsername(rawUsername);
        String password = requireValidPassword(rawPassword);

        // T4.93：未初始化实例禁止走注册认领（堵接管洞）——认领只保留在向导 setupClaimAdmin 且必须带码
        if (!adminClaimed()) {
            throw new ApiException(403, "SETUP_REQUIRED", "实例尚未初始化，请打开 /setup 创建管理员");
        }
        if (!registrationEnabled) {
            throw new ApiException(403, "REGISTRATION_DISABLED", "注册已关闭，请联系管理员");
        }
        requireUsernameFree(username); // 快路径查重；并发窗口由唯一索引兜底（DuplicateKeyException → 409）
        return createUser(username, password, "USER");
    }

    /** 首注册认领：**条件更新** WHERE id=1 AND password_hash='!'（原子，K-91）。T4.93 起调用方只剩
     *  向导 {@link #setupClaimAdmin}——注册路径不再触达。1 行 → 返回 admin 会话；0 行 → null；
     *  用户名撞唯一索引 → DuplicateKeyException 统一 409。 */
    private AuthSession claimAdminIfUnclaimed(String username, String password) {
        String token = tokenService.generate();
        String tokenHash = tokenService.sha256Hex(token);
        try {
            int claimed = jdbc.update("UPDATE app_user SET username = ?, password_hash = ?, role = 'ADMIN'"
                    + " WHERE id = 1 AND password_hash = ?", username, passwordHasher.hash(password), UNCLAIMED_MARKER);
            if (claimed == 0) {
                return null;
            }
            issueSession(1L, tokenHash);
            return new AuthSession(1L, username, "ADMIN", token);
        } catch (DuplicateKeyException e) {
            // 认领请求的用户名与既有其它用户撞名（ux_app_user_username 全表唯一）
            throw new ApiException(409, "USERNAME_TAKEN", "用户名已被占用");
        }
    }

    /** T4.93 C2 唯一认领入口：①已认领 409 SETUP_ALREADY_DONE；②码不对/空/未配置 403 SETUP_CODE_INVALID
     *  （常量时间比较）；③复用 {@link #claimAdminIfUnclaimed} 原子条件更新（码是它前面的闸，不是替代），
     *  0 行=并发被抢 → 409；④成功返回 ADMIN 会话。用户名/密码复用注册校验。 */
    public AuthSession setupClaimAdmin(String rawUsername, String rawPassword, String providedCode) {
        if (adminClaimed()) {
            throw new ApiException(409, "SETUP_ALREADY_DONE", "实例已完成初始化");
        }
        if (!setupCodeValid(providedCode)) {
            throw new ApiException(403, "SETUP_CODE_INVALID", "安装码不正确（在终端里 first-run 输出的 SETUP_CODE）");
        }
        String username = requireValidUsername(rawUsername);
        String password = requireValidPassword(rawPassword);
        AuthSession claimed = claimAdminIfUnclaimed(username, password);
        if (claimed == null) {
            throw new ApiException(409, "SETUP_ALREADY_DONE", "实例已完成初始化");
        }
        return claimed;
    }

    /** T4.93 C1 安装码校验：常量时间比较 UTF-8 字节；配置为空 ⇒ 恒 false（安全侧默认，K-183）。 */
    boolean setupCodeValid(String provided) {
        if (provided == null || provided.isEmpty() || setupCode.isEmpty()) {
            return false;
        }
        return ApiTokenFilter.constantTimeEquals(setupCode, provided);
    }

    /** 普通用户创建：撞唯一索引 → 409（唯一索引为并发最终闸门）。 */
    private AuthSession createUser(String username, String password, String role) {
        String token = tokenService.generate();
        String tokenHash = tokenService.sha256Hex(token);
        long userId = insertUser(username, passwordHasher.hash(password), role);
        issueSession(userId, tokenHash);
        return new AuthSession(userId, username, role, token);
    }

    /** INSERT 共用体（token 只进 user_session，旧列保留不写）；撞唯一索引 → 409。 */
    private long insertUser(String username, String passwordHash, String role) {
        try {
            return jdbc.queryForObject("INSERT INTO app_user (username, password_hash, role, token_hash,"
                    + " token_issued_at) VALUES (?, ?, ?, NULL, NULL) RETURNING id",
                    Long.class, username, passwordHash, role);
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "USERNAME_TAKEN", "用户名已被占用");
        }
    }

    public AuthSession login(String rawUsername, String rawPassword) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        String password = rawPassword == null ? "" : rawPassword.trim();
        if (username.isEmpty() || password.isEmpty()) {
            throw unauthorized();
        }
        List<Credentials> rows = jdbc.query("""
                SELECT id, username, password_hash, role, failed_login_attempts, locked_until
                FROM app_user WHERE lower(username) = lower(?)
                """,
                (rs, i) -> new Credentials(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getTimestamp(6)),
                username);
        Credentials user = rows.isEmpty() || UNCLAIMED_MARKER.equals(rows.get(0).passwordHash()) ? null : rows.get(0);
        // T4.82 时序抹平：user == null 时 verify 也付出一次 PBKDF2（假盐恒不匹配，防计时枚举）
        boolean matched = passwordHasher.verify(password, user == null ? null : user.passwordHash());
        if (user == null) { throw unauthorized(); }
        // T4.81：锁定判定——锁定期正确密码也 423，文案不提示密码对错（卡面刻意取舍）
        if (loginLockout.isLocked(user.lockedUntil())) {
            throw loginLockout.lockedException();
        }
        if (!matched) {
            if (loginLockout.recordFailure(user.userId())) {
                throw loginLockout.lockedException(); // 本次失败达到上限：直接 423（不回落 401）
            }
            throw unauthorized();
        }
        loginLockout.reset(user.userId()); // T4.81：成功登录清零/清空（签发会话之前）
        // T4.71：多设备会话——签发新会话（不覆盖旧行），同账号可同时持多个有效令牌
        String token = tokenService.generate();
        issueSession(user.userId(), tokenService.sha256Hex(token));
        return new AuthSession(user.userId(), user.username(), user.role(), token);
    }

    /** T4.71：logout 只删当前令牌的会话（API_TOKEN 无会话行 → 删 0 行）。 */
    public void logout(long userId, String rawToken) {
        String tokenHash = tokenService.sha256Hex(rawToken == null ? "" : rawToken);
        jdbc.update("DELETE FROM user_session WHERE user_id = ? AND token_hash = ?", userId, tokenHash);
    }

    public UserInfo me(long userId) {
        List<UserInfo> rows = jdbc.query(
                "SELECT id, username, role FROM app_user WHERE id = ?",
                (rs, i) -> new UserInfo(rs.getLong(1), rs.getString(2), rs.getString(3)), userId);
        if (rows.isEmpty()) {
            throw new ApiException(401, "UNAUTHORIZED", "账号不存在或已被删除");
        }
        return rows.get(0);
    }

    /** T4.70 C2：管理员是否已认领（口径 = id=1 且 password_hash <> '!'，认领标记反向判断）。 */
    public boolean adminClaimed() {
        Long claimed = jdbc.query(
                "SELECT id FROM app_user WHERE id = 1 AND password_hash <> ?",
                rs -> rs.next() ? rs.getLong(1) : null, UNCLAIMED_MARKER);
        return claimed != null;
    }

    /** T4.70 C2：注册开关当前值（@Value 配置项——改后重启生效，不做运行期热改）。 */
    public boolean registrationEnabled() {
        return registrationEnabled;
    }

    /** T4.70 C3：管理员建号（加人通道）。role 只接受 USER（ADMIN 由认领产生）；不带令牌；重名 409。 */
    public UserInfo createUserAsAdmin(String rawUsername, String rawPassword, String role) {
        String username = requireValidUsername(rawUsername);
        String password = requireValidPassword(rawPassword);
        if (role != null && !role.isBlank() && !"USER".equals(role)) {
            throw new ApiException(400, "INVALID_PARAM", "role 仅支持 USER（管理员由首次注册认领产生）");
        }
        requireUsernameFree(username);
        long userId = insertUser(username, passwordHasher.hash(password), "USER");
        return new UserInfo(userId, username, "USER");
    }

    /** T4.70 C3：成员列表（按 id 序）——不含 password_hash/token_hash 任何形态。 */
    public List<UserInfo> listUsers() {
        return jdbc.query("SELECT id, username, role FROM app_user ORDER BY id",
                (rs, i) -> new UserInfo(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    /** T4.70 C3：当前调用方必须是 ADMIN，否则 403 FORBIDDEN（首个权限失败码，与 400/409 同走 ApiException）。 */
    public void requireAdmin(long userId) {
        UserInfo info = me(userId);
        if (!"ADMIN".equals(info.role())) {
            throw new ApiException(403, "FORBIDDEN", "需要管理员权限");
        }
    }

    /** 会话签发共用体：INSERT user_session + 上限淘汰（超出 max-sessions 删 created_at 最旧；令牌不进日志）。 */
    private void issueSession(long userId, String tokenHash) {
        jdbc.update("INSERT INTO user_session (user_id, token_hash) VALUES (?, ?)", userId, tokenHash);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_session WHERE user_id = ?", Integer.class, userId);
        if (count != null && count > maxSessions) {
            int evicted = jdbc.update(
                    "DELETE FROM user_session WHERE user_id = ? AND id IN "
                            + "(SELECT id FROM user_session WHERE user_id = ? ORDER BY created_at, id LIMIT ?)",
                    userId, userId, count - maxSessions);
            if (evicted > 0) {
                log.atInfo().addKeyValue("userId", userId).addKeyValue("evicted", evicted)
                        .addKeyValue("maxSessions", maxSessions).log("会话数超上限：已淘汰最旧会话");
            }
        }
    }

    /** T4.71 C3：会话视图（不含令牌/hash 任何形态）。 */
    public record SessionInfo(long id, java.time.Instant createdAt, java.time.Instant lastUsedAt) {
    }

    /** T4.71 C3：某账号的会话列表（按 id 序）。 */
    public List<SessionInfo> listSessions(long userId) {
        return jdbc.query(
                "SELECT id, created_at, last_used_at FROM user_session WHERE user_id = ? ORDER BY id",
                (rs, i) -> new SessionInfo(rs.getLong(1),
                        rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()),
                userId);
    }

    private static String requireValidUsername(String raw) {
        String username = raw == null ? "" : raw.trim();
        if (username.length() < 3 || username.length() > 32
                || !username.matches("[A-Za-z0-9_\\-\u4e00-\u9fa5]+")) {
            throw new ApiException(400, "INVALID_PARAM",
                    "用户名必须为 3–32 个字符，仅限字母/数字/下划线/连字符/中文");
        }
        return username;
    }

    private static String requireValidPassword(String raw) {
        String password = raw == null ? "" : raw.trim();
        if (password.isEmpty() || password.length() < 8 || password.length() > 64) {
            throw new ApiException(400, "INVALID_PARAM", "密码必须为 8–64 个字符");
        }
        return password;
    }

    private void requireUsernameFree(String username) {
        Long taken = jdbc.query(
                "SELECT id FROM app_user WHERE lower(username) = lower(?)",
                rs -> rs.next() ? rs.getLong(1) : null, username);
        if (taken != null) {
            throw new ApiException(409, "USERNAME_TAKEN", "用户名已被占用");
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(401, "INVALID_CREDENTIALS", "用户名或密码错误");
    }
}
