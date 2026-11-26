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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.37a：AuthService——首注册认领 admin、再注册 USER、大小写 409、密码/用户名 400、
 * 登录成败 401、令牌轮换、logout 清空、开关 403（卡判据逐条）。
 * T4.81/P-29 后半：锁定优先判定（423 且不碰密码验证）、失败计数委托 LoginLockoutService、
 * 成功登录清零——真实 SQL 由 LoginLockoutDbTest（真 H2）钉住。
 * PasswordHasher/TokenService 用真实现（纯逻辑），只 mock JdbcTemplate。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private LoginLockoutService loginLockout;

    /** spy 保留真实哈希/校验行为，同时可 verify「锁定路径零调用」（T4.81）。 */
    private final PasswordHasher hasher = org.mockito.Mockito.spy(new PasswordHasher());
    private final TokenService tokens = new TokenService();

    /** T4.93：安装码测试值（setupClaimAdmin 专用）。 */
    static final String SETUP_CODE = "t493-setup-code";

    private AuthService newService(boolean registrationEnabled) {
        return new AuthService(jdbc, hasher, tokens, loginLockout, registrationEnabled, 10, SETUP_CODE);
    }

    private AuthService newService(boolean registrationEnabled, int maxSessions) {
        return new AuthService(jdbc, hasher, tokens, loginLockout, registrationEnabled, maxSessions, SETUP_CODE);
    }

    private AuthService newService(boolean registrationEnabled, int maxSessions, String setupCode) {
        return new AuthService(jdbc, hasher, tokens, loginLockout, registrationEnabled, maxSessions, setupCode);
    }

    private static ApiException ofCode(Runnable action, String code) {
        ApiException e = assertThrows(ApiException.class, action::run);
        assertEquals(code, e.getCode());
        return e;
    }

    /* ---- 注册：未初始化实例必须 403 SETUP_REQUIRED（T4.93：认领改道向导） ---- */

    /** adminClaimed 桩：null=未认领，1L=已认领（口径与 adminClaimedMirrorsClaimMarker 一致）。 */
    private void stubAdminClaimed(Long claimedId) {
        when(jdbc.query(contains("WHERE id = 1 AND password_hash <> ?"),
                any(ResultSetExtractor.class), eq("!"))).thenReturn(claimedId);
    }

    @Test
    void registerOnUnclaimedInstanceIs403SetupRequired() {
        stubAdminClaimed(null); // 未认领
        ApiException e = ofCode(() -> newService(true).register("Boss", "password123"), "SETUP_REQUIRED");
        assertEquals(403, e.getStatus());
        // 认领的条件更新绝不能被注册路径触达（T4.93：认领只走向导）
        verify(jdbc, never()).update(contains("WHERE id = 1 AND password_hash = ?"),
                anyString(), anyString(), anyString());
    }

    @Test
    void registerOnClaimedInstanceFollowsNormalPath() {
        stubAdminClaimed(1L); // 已认领 → 正常注册路径
        when(jdbc.query(contains("WHERE lower(username) = lower(?)"),
                any(ResultSetExtractor.class), eq("bob"))).thenReturn(null);
        when(jdbc.queryForObject(contains("INSERT INTO app_user"), eq(Long.class),
                eq("bob"), anyString(), eq("USER"))).thenReturn(5L);

        AuthService.AuthSession s = newService(true).register("bob", "password123");
        assertEquals(5L, s.userId());
        assertEquals("USER", s.role());
    }

    /* ---- T4.93 C2：setupClaimAdmin（唯一认领入口，必须带安装码） ---- */

    @Test
    void setupClaimAdminWithValidCodeClaimsId1AdminRole() {
        stubAdminClaimed(null);
        when(jdbc.update(contains("WHERE id = 1 AND password_hash = ?"),
                anyString(), anyString(), anyString())).thenReturn(1);

        AuthService.AuthSession s = newService(true).setupClaimAdmin("Boss", "password123", SETUP_CODE);
        assertEquals(1L, s.userId(), "向导认领 = id=1");
        assertEquals("ADMIN", s.role());
        assertEquals("Boss", s.username());
        assertNotNull(s.token());
        verify(jdbc).update(contains("WHERE id = 1 AND password_hash = ?"), eq("Boss"),
                anyString(), eq("!"));
        verify(jdbc).update(contains("INSERT INTO user_session (user_id, token_hash) VALUES (?, ?)"),
                eq(1L), anyString());
    }

    @Test
    void setupClaimAdminWithWrongCodeIs403() {
        stubAdminClaimed(null);
        ofCode(() -> newService(true).setupClaimAdmin("Boss", "password123", "wrong-code"), "SETUP_CODE_INVALID");
        ofCode(() -> newService(true).setupClaimAdmin("Boss", "password123", ""), "SETUP_CODE_INVALID");
        ofCode(() -> newService(true).setupClaimAdmin("Boss", "password123", null), "SETUP_CODE_INVALID");
        // 码不对：原子认领一次都不能发生
        verify(jdbc, never()).update(contains("WHERE id = 1 AND password_hash = ?"),
                anyString(), anyString(), anyString());
    }

    /** K-183：SETUP_CODE 未配置（空）⇒ 任何码都拒绝（安全侧默认）。 */
    @Test
    void setupClaimAdminWithUnconfiguredCodeAlwaysRejects() {
        stubAdminClaimed(null);
        AuthService svc = newService(true, 10, "");
        ofCode(() -> svc.setupClaimAdmin("Boss", "password123", SETUP_CODE), "SETUP_CODE_INVALID");
        ofCode(() -> svc.setupClaimAdmin("Boss", "password123", ""), "SETUP_CODE_INVALID");
    }

    @Test
    void setupClaimAdminOnClaimedInstanceIs409() {
        stubAdminClaimed(1L);
        ofCode(() -> newService(true).setupClaimAdmin("Boss", "password123", SETUP_CODE), "SETUP_ALREADY_DONE");
    }

    @Test
    void setupClaimAdminConcurrentLoserIs409() {
        stubAdminClaimed(null);
        // 先查后更窗口内被并发认领：条件更新 0 行 → 409 SETUP_ALREADY_DONE（原子闸，K-91）
        when(jdbc.update(contains("WHERE id = 1 AND password_hash = ?"),
                anyString(), anyString(), anyString())).thenReturn(0);
        ofCode(() -> newService(true).setupClaimAdmin("Boss", "password123", SETUP_CODE), "SETUP_ALREADY_DONE");
    }

    @Test
    void setupClaimUsernameConflictingWithOtherUserIs409() {
        stubAdminClaimed(null);
        // 认领请求的用户名撞其它用户 → 唯一索引 DuplicateKeyException → 409
        when(jdbc.update(contains("WHERE id = 1 AND password_hash = ?"),
                anyString(), anyString(), anyString()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("ux_app_user_username"));

        ofCode(() -> newService(true).setupClaimAdmin("admin", "password123", SETUP_CODE), "USERNAME_TAKEN");
    }

    @Test
    void setupClaimAdminValidatesParams() {
        stubAdminClaimed(null);
        AuthService svc = newService(true);
        ofCode(() -> svc.setupClaimAdmin("ab", "password123", SETUP_CODE), "INVALID_PARAM");   // 用户名过短
        ofCode(() -> svc.setupClaimAdmin("boss", "7chars!", SETUP_CODE), "INVALID_PARAM");     // 密码过短
    }

    /* ---- 注册：普通路径 ---- */

    @Test
    void secondRegisterCreatesUserIdRole() {
        // T4.93：admin 已认领 → 注册走普通路径
        stubAdminClaimed(1L);
        when(jdbc.query(contains("WHERE lower(username) = lower(?)"),
                any(ResultSetExtractor.class), eq("bob"))).thenReturn(null);
        when(jdbc.queryForObject(contains("INSERT INTO app_user"), eq(Long.class),
                eq("bob"), anyString(), eq("USER"))).thenReturn(5L);

        AuthService.AuthSession s = newService(true).register("bob", "password123");

        assertEquals(5L, s.userId());
        assertEquals("USER", s.role());
        verify(jdbc).update(contains("INSERT INTO user_session (user_id, token_hash) VALUES (?, ?)"),
                eq(5L), anyString());
    }

    @Test
    void duplicateUsernameCaseInsensitive409() {
        stubAdminClaimed(1L);
        when(jdbc.query(contains("WHERE lower(username) = lower(?)"),
                any(ResultSetExtractor.class), eq("BOB"))).thenReturn(5L);

        ofCode(() -> newService(true).register("BOB", "password123"), "USERNAME_TAKEN");
    }

    /** 卡面判据①：并发窗口下 INSERT 撞唯一索引（DuplicateKeyException）→ 409，绝不 500。 */
    @Test
    void concurrentInsertUniqueViolationIs409Not500() {
        stubAdminClaimed(1L);
        when(jdbc.query(contains("WHERE lower(username) = lower(?)"),
                any(ResultSetExtractor.class), eq("bob"))).thenReturn(null);
        when(jdbc.queryForObject(contains("INSERT INTO app_user"), eq(Long.class),
                eq("bob"), anyString(), eq("USER")))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("ux_app_user_username"));

        ApiException e = ofCode(() -> newService(true).register("bob", "password123"), "USERNAME_TAKEN");
        assertEquals(409, e.getStatus());
    }

    @Test
    void registrationDisabled403WhenAdminClaimed() {
        // 已认领 + 开关关 → 403
        stubAdminClaimed(1L);

        ofCode(() -> newService(false).register("bob", "password123"), "REGISTRATION_DISABLED");
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
    void loginIssuesIndependentSessions() {
        String realHash = hasher.hash("right-pass");
        when(jdbc.query(contains("FROM app_user WHERE lower(username) = lower(?)"),
                any(RowMapper.class), eq("alice")))
                .thenReturn(List.of(new AuthService.Credentials(2L, "alice", realHash, "USER", 0, null)));

        AuthService.AuthSession s1 = newService(true).login("alice", "right-pass");
        AuthService.AuthSession s2 = newService(true).login("alice", " right-pass ");

        assertEquals(2L, s1.userId());
        assertEquals("USER", s1.role());
        assertNotNull(s1.token());
        assertNotEquals(s1.token(), s2.token(), "两次登录各签发独立令牌");
        /* T4.71：多设备会话——每次登录 INSERT 一条 user_session，绝不覆盖/不碰 app_user.token_hash */
        verify(jdbc, org.mockito.Mockito.times(2))
                .update(contains("INSERT INTO user_session (user_id, token_hash) VALUES (?, ?)"),
                        eq(2L), anyString());
        verify(jdbc, never()).update(contains("UPDATE app_user SET token_hash"), anyString(), eq(2L));
    }

    @Test
    void loginFailuresAreUnified401() {
        AuthService svc = newService(true);
        // 用户不存在
        when(jdbc.query(contains("FROM app_user WHERE lower(username) = lower(?)"),
                any(RowMapper.class), eq("ghost"))).thenReturn(List.of());
        ofCode(() -> svc.login("ghost", "whatever1"), "INVALID_CREDENTIALS");
        // 密码错误（spy 的 hash 必须在 when() 之外先算，否则被拦截成未完成 stubbing）
        String wrongUserHash = hasher.hash("right-pass");
        when(jdbc.query(contains("FROM app_user WHERE lower(username) = lower(?)"),
                any(RowMapper.class), eq("alice")))
                .thenReturn(List.of(new AuthService.Credentials(2L, "alice", wrongUserHash, "USER", 0, null)));
        ofCode(() -> svc.login("alice", "wrong-pass"), "INVALID_CREDENTIALS");
        // 管理员未认领（占位 '!'）
        when(jdbc.query(contains("FROM app_user WHERE lower(username) = lower(?)"),
                any(RowMapper.class), eq("admin")))
                .thenReturn(List.of(new AuthService.Credentials(1L, "admin", "!", "ADMIN", 0, null)));
        ofCode(() -> svc.login("admin", "password123"), "INVALID_CREDENTIALS");
        // 空/缺参
        ofCode(() -> svc.login("  ", "password123"), "INVALID_CREDENTIALS");
        ofCode(() -> svc.login("alice", ""), "INVALID_CREDENTIALS");
    }

    /* ---- 注销 / me ---- */

    @Test
    void logoutDeletesOnlyCurrentTokenSession() {
        newService(true).logout(2L, "raw-device-token");
        verify(jdbc).update(contains("DELETE FROM user_session WHERE user_id = ? AND token_hash = ?"),
                eq(2L), eq(tokens.sha256Hex("raw-device-token")));
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

    /* ---- T4.70 C2：注册状态 ---- */

    @Test
    void adminClaimedMirrorsClaimMarker() {
        // 未认领：查询无行 → false；已认领：查询命中 → true
        when(jdbc.query(contains("WHERE id = 1 AND password_hash <> ?"),
                any(ResultSetExtractor.class), eq("!"))).thenReturn(null);
        assertEquals(false, newService(true).adminClaimed());

        when(jdbc.query(contains("WHERE id = 1 AND password_hash <> ?"),
                any(ResultSetExtractor.class), eq("!"))).thenReturn(1L);
        assertEquals(true, newService(true).adminClaimed());
    }

    /* ---- T4.70 C3：管理员建号 ---- */

    @Test
    void adminCreateUserInsertsWithoutToken() {
        when(jdbc.query(contains("WHERE lower(username) = lower(?)"),
                any(ResultSetExtractor.class), eq("carol"))).thenReturn(null);
        when(jdbc.queryForObject(contains("INSERT INTO app_user"), eq(Long.class),
                eq("carol"), anyString(), eq("USER"))).thenReturn(7L);

        AuthService.UserInfo info = newService(true).createUserAsAdmin("carol", "password123", "USER");

        assertEquals(7L, info.userId());
        assertEquals("carol", info.username());
        assertEquals("USER", info.role());
        verify(jdbc).queryForObject(contains("INSERT INTO app_user"), eq(Long.class),
                eq("carol"), anyString(), eq("USER"));
    }

    @Test
    void adminCreateUserDuplicateIs409() {
        when(jdbc.query(contains("WHERE lower(username) = lower(?)"),
                any(ResultSetExtractor.class), eq("carol"))).thenReturn(2L);
        ofCode(() -> newService(true).createUserAsAdmin("carol", "password123", "USER"), "USERNAME_TAKEN");
    }

    @Test
    void adminCreateUserValidatesAndRejectsAdminRole() {
        AuthService svc = newService(true);
        ofCode(() -> svc.createUserAsAdmin("ab", "password123", "USER"), "INVALID_PARAM");     // 用户名过短
        ofCode(() -> svc.createUserAsAdmin("carol", "7chars!", "USER"), "INVALID_PARAM");      // 密码过短（7 位）
        ofCode(() -> svc.createUserAsAdmin("carol", "password123", "ADMIN"), "INVALID_PARAM"); // 不开放提权
        ofCode(() -> svc.createUserAsAdmin("carol", "password123", "OWNER"), "INVALID_PARAM"); // 未知角色
    }

    @Test
    void adminCreateUserResponseCarriesNoPasswordOrToken() {
        when(jdbc.query(contains("WHERE lower(username) = lower(?)"),
                any(ResultSetExtractor.class), eq("carol"))).thenReturn(null);
        when(jdbc.queryForObject(contains("INSERT INTO app_user"), eq(Long.class),
                eq("carol"), anyString(), eq("USER"))).thenReturn(7L);

        AuthService.UserInfo info = newService(true).createUserAsAdmin("carol", "password123", "USER");

        String dumped = String.valueOf(info);
        assertTrue(!dumped.contains("password123") && !dumped.contains("token"),
                "返回体（UserInfo）不得携带密码或令牌：" + dumped);
    }

    /* ---- T4.70 C3：requireAdmin（权限闸） ---- */

    @Test
    void requireAdminPassesForAdminAnd403ForUser() {
        when(jdbc.query(contains("SELECT id, username, role FROM app_user WHERE id = ?"),
                any(RowMapper.class), eq(1L)))
                .thenReturn(List.of(new AuthService.UserInfo(1L, "boss", "ADMIN")));
        newService(true).requireAdmin(1L); // 不抛 = 通过

        when(jdbc.query(contains("SELECT id, username, role FROM app_user WHERE id = ?"),
                any(RowMapper.class), eq(2L)))
                .thenReturn(List.of(new AuthService.UserInfo(2L, "carol", "USER")));
        ApiException e = ofCode(() -> newService(true).requireAdmin(2L), "FORBIDDEN");
        assertEquals(403, e.getStatus());
    }

    /* ---- T4.71：会话上限淘汰 / 会话列表 ---- */

    @Test
    void sessionsEvictOldestOverLimit() {
        // COUNT=3 > max=2 → 删最旧的 3−2=1 条（id IN ... ORDER BY created_at LIMIT 1），保留最新 2 条
        stubAdminClaimed(1L);
        when(jdbc.query(contains("WHERE lower(username) = lower(?)"),
                any(ResultSetExtractor.class), eq("bob"))).thenReturn(null);
        when(jdbc.queryForObject(contains("INSERT INTO app_user"), eq(Long.class),
                eq("bob"), anyString(), eq("USER"))).thenReturn(5L);
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM user_session WHERE user_id = ?"),
                eq(Integer.class), eq(5L))).thenReturn(3);

        newService(true, 2).register("bob", "password123");

        verify(jdbc).update(contains("AND id IN (SELECT id FROM user_session WHERE user_id = ? "
                + "ORDER BY created_at, id LIMIT ?)"), eq(5L), eq(5L), eq(1));
    }

    @Test
    void sessionsUnderLimitAreNotPruned() {
        stubAdminClaimed(1L);
        when(jdbc.query(contains("WHERE lower(username) = lower(?)"),
                any(ResultSetExtractor.class), eq("bob"))).thenReturn(null);
        when(jdbc.queryForObject(contains("INSERT INTO app_user"), eq(Long.class),
                eq("bob"), anyString(), eq("USER"))).thenReturn(5L);
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM user_session WHERE user_id = ?"),
                eq(Integer.class), eq(5L))).thenReturn(2);

        newService(true, 2).register("bob", "password123");

        verify(jdbc, never()).update(contains("ORDER BY created_at, id LIMIT ?"));
    }

    @Test
    void listSessionsReturnsRowsWithoutTokenMaterial() {
        when(jdbc.query(contains("SELECT id, created_at, last_used_at FROM user_session WHERE user_id = ?"),
                any(RowMapper.class), eq(2L))).thenReturn(List.of(
                new AuthService.SessionInfo(11, java.time.Instant.parse("2026-11-22T01:00:00Z"),
                        java.time.Instant.parse("2026-11-22T02:00:00Z"))));

        List<AuthService.SessionInfo> rows = newService(true).listSessions(2L);

        assertEquals(1, rows.size());
        assertEquals(11L, rows.get(0).id());
        String dumped = String.valueOf(rows);
        assertTrue(!dumped.contains("token") && !dumped.contains("hash"),
                "会话视图不得携带令牌/hash：" + dumped);
    }

    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }

    /* ---- T4.81/P-29 后半：锁定优先判定 + 成功清零（真 SQL 在 LoginLockoutDbTest） ---- */

    /** 已锁定（locked_until 在未来）→ 423 LOCKED，且失败计数零调用。
     *  T4.82 起 verify 先于锁定判定执行（时序抹平），原「零验密」断言由 C3 的
     *  lockedUserStillPaysPbkdf2Cost（verify 恰 1 次）取代——本用例保留 423 + 不计失败两个断言。 */
    @Test
    void lockedAccountThrows423WithoutRecordingFailure() {
        AuthService svc = newService(true);
        String realHash = hasher.hash("right-pass");
        when(jdbc.query(contains("FROM app_user WHERE lower(username) = lower(?)"),
                any(RowMapper.class), eq("alice")))
                .thenReturn(List.of(new AuthService.Credentials(2L, "alice", realHash,
                        "USER", 2, java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(600)))));
        when(loginLockout.isLocked(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(loginLockout.lockedException()).thenReturn(
                new ApiException(423, "LOCKED", "账号已锁定，请 15 分钟后再试，或联系管理员解锁"));

        ApiException e = assertThrows(ApiException.class, () -> svc.login("alice", "right-pass"));
        assertEquals(423, e.getStatus());
        assertEquals("LOCKED", e.getCode());
        verify(loginLockout, never()).recordFailure(2L); // 锁定路径不计失败
    }

    /** 登录成功 → 失败计数清零被调一次（签发会话之前）。 */
    @Test
    void successfulLoginResetsFailureCounter() {
        AuthService svc = newService(true);
        String realHash = hasher.hash("right-pass");
        when(jdbc.query(contains("FROM app_user WHERE lower(username) = lower(?)"),
                any(RowMapper.class), eq("alice")))
                .thenReturn(List.of(new AuthService.Credentials(2L, "alice", realHash, "USER", 2,
                        java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(600)))));

        AuthService.AuthSession session = svc.login("alice", "right-pass");

        assertEquals(2L, session.userId());
        verify(loginLockout).reset(2L); // 成功登录必须清零
    }

    /* ---- T4.82：时序抹平——「用户不存在/锁定」也付出一次 PBKDF2（同成本路径） ---- */

    /** 用户不存在 → 401 INVALID_CREDENTIALS，但 verify(password, null) 恰好被调用 1 次（同成本路径）。 */
    @Test
    void missingUserStillPaysPbkdf2Cost() {
        AuthService svc = newService(true);
        when(jdbc.query(contains("FROM app_user WHERE lower(username) = lower(?)"),
                any(RowMapper.class), eq("nosuch_t482"))).thenReturn(List.of());

        ApiException e = assertThrows(ApiException.class, () -> svc.login("nosuch_t482", "whatever1"));
        assertEquals(401, e.getStatus());
        assertEquals("INVALID_CREDENTIALS", e.getCode());
        verify(hasher, times(1)).verify(eq("whatever1"), isNull());
    }

    /** 锁定用户 → 423 LOCKED，verify 仍被调用 1 次（先付成本再判锁定）。 */
    @Test
    void lockedUserStillPaysPbkdf2Cost() {
        AuthService svc = newService(true);
        String realHash = hasher.hash("right-pass");
        when(jdbc.query(contains("FROM app_user WHERE lower(username) = lower(?)"),
                any(RowMapper.class), eq("alice")))
                .thenReturn(List.of(new AuthService.Credentials(2L, "alice", realHash, "USER", 2,
                        java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(600)))));
        when(loginLockout.isLocked(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(loginLockout.lockedException()).thenReturn(
                new ApiException(423, "LOCKED", "账号已锁定，请 15 分钟后再试，或联系管理员解锁"));

        ApiException e = assertThrows(ApiException.class, () -> svc.login("alice", "right-pass"));
        assertEquals(423, e.getStatus());
        assertEquals("LOCKED", e.getCode());
        verify(hasher, times(1)).verify("right-pass", realHash);
    }
}
