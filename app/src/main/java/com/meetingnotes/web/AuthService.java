package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 账号业务（T4.37a）：注册（含 1 号账号认领）/ 登录（单令牌轮换）/ 注销 / 我的信息。
 *
 * <p>口径（卡面第 3/5 条）：
 * <ul>
 *   <li>用户名 trim 后 3–32 字符、字符集 {@code [A-Za-z0-9_\-\u4e00-\u9fa5]}、大小写不敏感唯一（409 USERNAME_TAKEN）；</li>
 *   <li>密码去首尾空白后 8–64 字符（空/超长 → 400 INVALID_PARAM）；</li>
 *   <li>1 号账号未认领（password_hash='!'）→ 首次注册即认领管理员（role=ADMIN，**不受注册开关控制**）；</li>
 *   <li>其余注册受 {@code AUTH_REGISTRATION_ENABLED} 控制（关闭 → 403 REGISTRATION_DISABLED），新用户 role=USER；</li>
 *   <li>登录失败统一 401 INVALID_CREDENTIALS（不泄露用户名是否存在）；</li>
 *   <li>登录成功轮换令牌（旧令牌立即失效）；logout 清空 token_hash。</li>
 * </ul>
 */
@Service
public class AuthService {

    /** 占位密码哈希：1 号账号未认领的标记（V8 迁移写入）。 */
    static final String UNCLAIMED_MARKER = "!";

    private final JdbcTemplate jdbc;
    private final PasswordHasher passwordHasher;
    private final TokenService tokenService;
    private final boolean registrationEnabled;

    public AuthService(JdbcTemplate jdbc,
                       PasswordHasher passwordHasher,
                       TokenService tokenService,
                       @Value("${app.auth.registration-enabled}") boolean registrationEnabled) {
        this.jdbc = jdbc;
        this.passwordHasher = passwordHasher;
        this.tokenService = tokenService;
        this.registrationEnabled = registrationEnabled;
    }

    /** 统一响应（对外 snake_case）：token 明文只在这里出现一次。 */
    public record AuthSession(long userId, String username, String role, String token) {
    }

    public record UserInfo(long userId, String username, String role) {
    }

    /** login 查询中间态：password_hash 不出服务（包内可见，供测试构造）。 */
    record Credentials(long userId, String username, String passwordHash, String role) {
    }

    /* ---- 注册 ---- */

    public AuthSession register(String rawUsername, String rawPassword) {
        String username = requireValidUsername(rawUsername);
        String password = requireValidPassword(rawPassword);

        String adminHash = jdbc.query(
                "SELECT password_hash FROM app_user WHERE id = 1",
                rs -> rs.next() ? rs.getString(1) : null);
        if (UNCLAIMED_MARKER.equals(adminHash)) {
            return claimAdmin(username, password);
        }
        if (!registrationEnabled) {
            throw new ApiException(403, "REGISTRATION_DISABLED", "注册已关闭，请联系管理员");
        }
        requireUsernameFree(username);
        return createUser(username, password, "USER", -1L);
    }

    /** 首次注册 = 认领管理员：写入 id=1、role=ADMIN，返回 token（忽略注册开关）。 */
    private AuthSession claimAdmin(String username, String password) {
        Long taken = jdbc.query(
                "SELECT id FROM app_user WHERE id <> 1 AND lower(username) = lower(?)",
                rs -> rs.next() ? rs.getLong(1) : null, username);
        if (taken != null) {
            throw new ApiException(409, "USERNAME_TAKEN", "用户名已被占用");
        }
        return createUser(username, password, "ADMIN", 1L);
    }

    private AuthSession createUser(String username, String password, String role, long fixedId) {
        String token = tokenService.generate();
        String hash = tokenService.sha256Hex(token);
        String passwordHash = passwordHasher.hash(password);
        long userId;
        if (fixedId > 0) {
            jdbc.update("""
                    UPDATE app_user SET username = ?, password_hash = ?, role = ?,
                           token_hash = ?, token_issued_at = now()
                    WHERE id = ?
                    """, username, passwordHash, role, hash, fixedId);
            userId = fixedId;
        } else {
            userId = jdbc.queryForObject("""
                    INSERT INTO app_user (username, password_hash, role, token_hash, token_issued_at)
                    VALUES (?, ?, ?, ?, now()) RETURNING id
                    """, Long.class, username, passwordHash, role, hash);
        }
        return new AuthSession(userId, username, role, token);
    }

    /* ---- 登录 / 注销 / me ---- */

    public AuthSession login(String rawUsername, String rawPassword) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        String password = rawPassword == null ? "" : rawPassword.trim();
        if (username.isEmpty() || password.isEmpty()) {
            throw unauthorized();
        }
        List<Credentials> rows = jdbc.query("""
                SELECT id, username, password_hash, role FROM app_user WHERE lower(username) = lower(?)
                """,
                (rs, i) -> new Credentials(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                username);
        if (rows.isEmpty() || UNCLAIMED_MARKER.equals(rows.get(0).passwordHash())) {
            throw unauthorized();
        }
        Credentials user = rows.get(0);
        if (!passwordHasher.verify(password, user.passwordHash())) {
            throw unauthorized();
        }
        // 单令牌轮换：新令牌覆盖 token_hash，旧令牌立即失效
        String token = tokenService.generate();
        jdbc.update("UPDATE app_user SET token_hash = ?, token_issued_at = now() WHERE id = ?",
                tokenService.sha256Hex(token), user.userId());
        return new AuthSession(user.userId(), user.username(), user.role(), token);
    }

    /** logout：清空 token_hash（该令牌立即 401）。 */
    public void logout(long userId) {
        jdbc.update("UPDATE app_user SET token_hash = NULL, token_issued_at = NULL WHERE id = ?", userId);
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

    /* ---- 校验 ---- */

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
