package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 账号业务（T4.37a）：注册（含 1 号账号认领）/ 登录（单令牌轮换）/ 注销 / 我的信息。
 *
 * <p>口径（卡面第 3/5 条）：
 * <ul>
 *   <li>用户名 trim 后 3–32 字符、字符集 {@code [A-Za-z0-9_\-\u4e00-\u9fa5]}、大小写不敏感唯一（409 USERNAME_TAKEN，
 *       **并发安全**：以唯一索引为最终闸门，唯一键冲突捕获为 409，K-91）；</li>
 *   <li>密码去首尾空白后 8–64 字符（空/超长 → 400 INVALID_PARAM）；</li>
 *   <li>1 号账号未认领（password_hash='!'）→ 首次注册即认领管理员（role=ADMIN，**不受注册开关控制**）——
 *       认领是**条件更新原子操作**（T4.37a-fix）：并发认领只有 1 个成功，其余按普通注册路径继续；</li>
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

        // T4.37a-fix：认领=条件更新原子闸（0 行=已被并发认领），赢者拿 admin 会话，输者走普通注册
        AuthSession claimed = claimAdminIfUnclaimed(username, password);
        if (claimed != null) {
            return claimed;
        }
        if (!registrationEnabled) {
            throw new ApiException(403, "REGISTRATION_DISABLED", "注册已关闭，请联系管理员");
        }
        requireUsernameFree(username); // 快路径查重；并发窗口由唯一索引兜底（DuplicateKeyException → 409）
        return createUser(username, password, "USER");
    }

    /** 首次注册 = 认领管理员：**条件更新** WHERE id=1 AND password_hash='!'（原子，K-91）。
     *  影响 1 行 → 认领成功返回 admin 会话；0 行 → 已被并发认领/已认领，返回 null 走普通注册。
     *  用户名与其它用户撞唯一索引 → DuplicateKeyException 统一 409。 */
    private AuthSession claimAdminIfUnclaimed(String username, String password) {
        String token = tokenService.generate();
        String tokenHash = tokenService.sha256Hex(token);
        try {
            int claimed = jdbc.update("""
                    UPDATE app_user SET username = ?, password_hash = ?, role = 'ADMIN',
                           token_hash = ?, token_issued_at = now()
                    WHERE id = 1 AND password_hash = ?
                    """, username, passwordHasher.hash(password), tokenHash, UNCLAIMED_MARKER);
            if (claimed == 0) {
                return null;
            }
            return new AuthSession(1L, username, "ADMIN", token);
        } catch (DuplicateKeyException e) {
            // 认领请求的用户名与既有其它用户撞名（ux_app_user_username 全表唯一）
            throw new ApiException(409, "USERNAME_TAKEN", "用户名已被占用");
        }
    }

    /** 普通用户创建：唯一索引为并发最终闸门——INSERT 撞 ux_app_user_username → 409（不吞其它完整性异常）。 */
    private AuthSession createUser(String username, String password, String role) {
        String token = tokenService.generate();
        String tokenHash = tokenService.sha256Hex(token);
        long userId = insertUser(username, passwordHasher.hash(password), role, tokenHash);
        return new AuthSession(userId, username, role, token);
    }

    /** INSERT 共用体：注册路径带 token_hash（注册即登录），管理员建号传 null（首次登录时才轮换令牌）。
     *  唯一索引为并发最终闸门——撞 ux_app_user_username → 409（不吞其它完整性异常）。 */
    private long insertUser(String username, String passwordHash, String role, String tokenHash) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO app_user (username, password_hash, role, token_hash, token_issued_at)
                    VALUES (?, ?, ?, ?, now()) RETURNING id
                    """, Long.class, username, passwordHash, role, tokenHash);
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "USERNAME_TAKEN", "用户名已被占用");
        }
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

    /* ---- T4.70 C2/C3：注册状态 + 管理员建号 ---- */

    /** T4.70 C2：管理员是否已认领。口径 = id=1 且 password_hash <> '!'（认领条件更新的反向判断）。
     *  ADMIN 只可能来自 1 号账号的首注册认领（T4.70 不开放提权建号），因此与
     *  EXISTS(SELECT 1 FROM app_user WHERE role='ADMIN') 等价——用认领标记作准，语义更直接。 */
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

    /** T4.70 C3：管理员建号（注册默认关闭后的唯一加人通道）。复用注册的校验与哈希；
     *  role 只接受缺省/USER——ADMIN 由 1 号账号首注册认领产生，不开放提权建号；
     *  建出的账号不带令牌（首次登录时才轮换），重名 → 409 USERNAME_TAKEN。 */
    public UserInfo createUserAsAdmin(String rawUsername, String rawPassword, String role) {
        String username = requireValidUsername(rawUsername);
        String password = requireValidPassword(rawPassword);
        if (role != null && !role.isBlank() && !"USER".equals(role)) {
            throw new ApiException(400, "INVALID_PARAM", "role 仅支持 USER（管理员由首次注册认领产生）");
        }
        requireUsernameFree(username);
        long userId = insertUser(username, passwordHasher.hash(password), "USER", null);
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
