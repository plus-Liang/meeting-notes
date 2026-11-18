package com.meetingnotes.web;

import com.meetingnotes.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.37a：AuthService——首注册认领 admin、再注册 USER、大小写 409、密码/用户名 400、
 * 登录成败 401、令牌轮换、logout 清空、开关 403（卡判据逐条）。
 * PasswordHasher/TokenService 用真实现（纯逻辑），只 mock JdbcTemplate。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private final PasswordHasher hasher = new PasswordHasher();
    private final TokenService tokens = new TokenService();

    private AuthService newService(boolean registrationEnabled) {
        return new AuthService(jdbc, hasher, tokens, registrationEnabled);
    }

    private static ApiException ofCode(Runnable action, String code) {
        ApiException e = assertThrows(ApiException.class, action::run);
        assertEquals(code, e.getCode());
        return e;
    }

    /* ---- 注册：管理员认领（T4.37a-fix：条件更新原子闸） ---- */

    @Test
    void firstRegisterClaimsAdminId1AdminRole() {
        // 条件更新影响 1 行 = 认领成功
        when(jdbc.update(contains("WHERE id = 1 AND password_hash = ?"),
                anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        AuthService.AuthSession s = newService(true).register("Boss", "password123");

        assertEquals(1L, s.userId(), "首注册 = 认领 id=1");
        assertEquals("ADMIN", s.role());
        assertEquals("Boss", s.username());
        assertNotNull(s.token());
        verify(jdbc).update(contains("WHERE id = 1 AND password_hash = ?"), eq("Boss"),
                anyString(), anyString(), eq("!"));
    }

    @Test
    void claimUsernameConflictingWithOtherUserIs409() {
        // 认领请求的用户名撞其它用户 → 唯一索引 DuplicateKeyException → 409
        when(jdbc.update(contains("WHERE id = 1 AND password_hash = ?"),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("ux_app_user_username"));

        ofCode(() -> newService(true).register("admin", "password123"), "USERNAME_TAKEN");
    }

    /* ---- 注册：普通路径 ---- */

    @Test
    void secondRegisterCreatesUserIdRole() {
        // 认领条件更新影响 0 行（admin 已被认领）→ 走普通注册
        when(jdbc.update(contains("WHERE id = 1 AND password_hash = ?"),
                anyString(), anyString(), anyString(), anyString())).thenReturn(0);
        when(jdbc.query(contains("WHERE lower(username) = lower(?)"),
                any(ResultSetExtractor.class), eq("bob"))).thenReturn(null);
        when(jdbc.queryForObject(contains("INSERT INTO app_user"), eq(Long.class),
                eq("bob"), anyString(), eq("USER"), anyString())).thenReturn(5L);

        AuthService.AuthSession s = newService(true).register("bob", "password123");

        assertEquals(5L, s.userId());
        assertEquals("USER", s.role());
    }

    @Test
    void duplicateUsernameCaseInsensitive409() {
        when(jdbc.update(contains("WHERE id = 1 AND password_hash = ?"),
                anyString(), anyString(), anyString(), anyString())).thenReturn(0);
        when(jdbc.query(contains("WHERE lower(username) = lower(?)"),
                any(ResultSetExtractor.class), eq("BOB"))).thenReturn(5L);

        ofCode(() -> newService(true).register("BOB", "password123"), "USERNAME_TAKEN");
    }

    /** 卡面判据①：并发窗口下 INSERT 撞唯一索引（DuplicateKeyException）→ 409，绝不 500。 */
    @Test
    void concurrentInsertUniqueViolationIs409Not500() {
        when(jdbc.update(contains("WHERE id = 1 AND password_hash = ?"),
                anyString(), anyString(), anyString(), anyString())).thenReturn(0);
        when(jdbc.query(contains("WHERE lower(username) = lower(?)"),
                any(ResultSetExtractor.class), eq("bob"))).thenReturn(null);
        when(jdbc.queryForObject(contains("INSERT INTO app_user"), eq(Long.class),
                eq("bob"), anyString(), eq("USER"), anyString()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("ux_app_user_username"));

        ApiException e = ofCode(() -> newService(true).register("bob", "password123"), "USERNAME_TAKEN");
        assertEquals(409, e.getStatus());
    }

    @Test
    void registrationDisabled403WhenAdminClaimed() {
        // 认领 0 行（已被占）+ 开关关 → 403
        when(jdbc.update(contains("WHERE id = 1 AND password_hash = ?"),
                anyString(), anyString(), anyString(), anyString())).thenReturn(0);

        ofCode(() -> newService(false).register("bob", "password123"), "REGISTRATION_DISABLED");
    }

    @Test
    void registrationDisabledDoesNotBlockAdminClaim() {
        when(jdbc.update(contains("WHERE id = 1 AND password_hash = ?"),
                anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        AuthService.AuthSession s = newService(false).register("admin", "password123");
        assertEquals(1L, s.userId(), "认领不受开关限制");
        assertEquals("ADMIN", s.role());
    }

    /* ---- 注册：参数校验 400 ---- */

    @Test
    void invalidPasswordsAndUsernames400() {
        AuthService svc = newService(true);
        ofCode(() -> svc.register("bob", "7chars!"), "INVALID_PARAM"); // 7 字
        ofCode(() -> svc.register("bob", " "), "INVALID_PARAM");       // 空
        ofCode(() -> svc.register("bob", "x".repeat(65)), "INVALID_PARAM"); // 超长
        ofCode(() -> svc.register("ab", "password123"), "INVALID_PARAM");   // 用户名 2 字
        ofCode(() -> svc.register("a".repeat(33), "password123"), "INVALID_PARAM"); // 超长
        ofCode(() -> svc.register("坏!名字", "password123"), "INVALID_PARAM");       // 非法字符
        ofCode(() -> svc.register(null, "password123"), "INVALID_PARAM");
    }

    /* ---- 登录 ---- */

    @Test
    void loginSuccessRotatesToken() {
        String realHash = hasher.hash("right-pass");
        when(jdbc.query(contains("SELECT id, username, password_hash, role FROM app_user WHERE lower(username) = lower(?)"),
                any(RowMapper.class), eq("alice")))
                .thenReturn(List.of(new AuthService.Credentials(2L, "alice", realHash, "USER")));

        AuthService.AuthSession s1 = newService(true).login("alice", "right-pass");
        AuthService.AuthSession s2 = newService(true).login("alice", " right-pass ");

        assertEquals(2L, s1.userId());
        assertEquals("USER", s1.role());
        assertNotNull(s1.token());
        assertNotEquals(s1.token(), s2.token(), "重登轮换：旧令牌被覆盖失效");
        verify(jdbc, org.mockito.Mockito.times(2))
                .update(contains("UPDATE app_user SET token_hash"), anyString(), eq(2L));
    }

    @Test
    void loginFailuresAreUnified401() {
        AuthService svc = newService(true);
        // 用户不存在
        when(jdbc.query(contains("SELECT id, username, password_hash, role FROM app_user WHERE lower(username) = lower(?)"),
                any(RowMapper.class), eq("ghost"))).thenReturn(List.of());
        ofCode(() -> svc.login("ghost", "whatever1"), "INVALID_CREDENTIALS");
        // 密码错误
        when(jdbc.query(contains("SELECT id, username, password_hash, role FROM app_user WHERE lower(username) = lower(?)"),
                any(RowMapper.class), eq("alice")))
                .thenReturn(List.of(new AuthService.Credentials(2L, "alice", hasher.hash("right-pass"), "USER")));
        ofCode(() -> svc.login("alice", "wrong-pass"), "INVALID_CREDENTIALS");
        // 管理员未认领（占位 '!'）
        when(jdbc.query(contains("SELECT id, username, password_hash, role FROM app_user WHERE lower(username) = lower(?)"),
                any(RowMapper.class), eq("admin")))
                .thenReturn(List.of(new AuthService.Credentials(1L, "admin", "!", "ADMIN")));
        ofCode(() -> svc.login("admin", "password123"), "INVALID_CREDENTIALS");
        // 空/缺参
        ofCode(() -> svc.login("  ", "password123"), "INVALID_CREDENTIALS");
        ofCode(() -> svc.login("alice", ""), "INVALID_CREDENTIALS");
    }

    /* ---- 注销 / me ---- */

    @Test
    void logoutClearsTokenHash() {
        newService(true).logout(2L);
        verify(jdbc).update(contains("SET token_hash = NULL"), eq(2L));
    }

    @Test
    void meReturnsUserInfoAnd404LikeMissing() {
        when(jdbc.query(contains("SELECT id, username, role FROM app_user WHERE id = ?"),
                any(RowMapper.class), eq(2L)))
                .thenReturn(List.of(new AuthService.UserInfo(2L, "alice", "USER")));
        AuthService.UserInfo info = newService(true).me(2L);
        assertEquals("alice", info.username());

        when(jdbc.query(contains("SELECT id, username, role FROM app_user WHERE id = ?"),
                any(RowMapper.class), eq(99L))).thenReturn(List.of());
        ApiException e = assertThrows(ApiException.class, () -> newService(true).me(99L));
        assertEquals(401, e.getStatus());
    }

    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}
