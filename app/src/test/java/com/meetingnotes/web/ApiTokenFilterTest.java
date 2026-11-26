package com.meetingnotes.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingnotes.common.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HexFormat;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T4.37a：双凭据认证——API_TOKEN→1（管理员座位）、账号令牌→app_user.token_hash 对应 id、
 * 无/错→401 统一口径、login/register 两条精确豁免（logout/me 仍要认证）、UserContext 必清（卡判据）。
 * standalone MockMvc：不拉起全上下文（不碰 DB），只验 filter 行为。
 */
class ApiTokenFilterTest {

    private static final String TOKEN = "test-token-123";
    private static final String USER_TOKEN = "user-token-abc";

    private MockMvc mvc;
    private JdbcTemplate jdbc;

    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/tasks")
        public String tasks() {
            return String.valueOf(UserContext.current()); // 回显当前用户 id
        }

        @GetMapping("/api/v1/tasks/5/audio")
        public String audio() {
            return "audio"; // T4.35b：真实验签在 AudioStreamController；filter 层只验豁免面
        }

        @PostMapping("/api/v1/auth/login")
        public String login() {
            return "login";
        }

        @PostMapping("/api/v1/auth/register")
        public String register() {
            return "register";
        }

        @PostMapping("/api/v1/auth/logout")
        public String logout() {
            return "logout";
        }

        @GetMapping("/api/v1/setup/status")
        public String setupStatus() {
            return "setup-status";
        }

        @GetMapping("/api/v1/setup/model")
        public String setupModel() {
            return "setup-model";
        }

        @PostMapping("/api/v1/setup/admin")
        public String setupAdmin() {
            return "setup-admin";
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = Mockito.mock(JdbcTemplate.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new ApiTokenFilter(TOKEN, jdbc, new ObjectMapper()))
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /* ---- 401 统一口径 ---- */

    @Test
    void missingTokenIsRejectedWithUnifiedJsonBody() throws Exception {
        mvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value(containsStringAccountMessage()));
    }

    private static org.hamcrest.Matcher<String> containsStringAccountMessage() {
        return org.hamcrest.Matchers.containsString("账号令牌或 API_TOKEN");
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/tasks").header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void lowercaseBearerPrefixRejected() throws Exception {
        mvc.perform(get("/api/v1/tasks").header("Authorization", "bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void constantTimeEqualsIsExact() {
        org.junit.jupiter.api.Assertions.assertTrue(ApiTokenFilter.constantTimeEquals("Bearer a", "Bearer a"));
        org.junit.jupiter.api.Assertions.assertFalse(ApiTokenFilter.constantTimeEquals("Bearer a", "bearer a"));
        org.junit.jupiter.api.Assertions.assertFalse(ApiTokenFilter.constantTimeEquals("Bearer a", "Bearer ab"));
    }

    /* ---- 双凭据 ---- */

    @Test
    void apiTokenPassesAsAdminUserId1() throws Exception {
        // API_TOKEN = 1 号账号（管理员座位）
        mvc.perform(get("/api/v1/tasks").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("1"));
    }

    @Test
    void accountTokenPassesWithItsUserId() throws Exception {
        // T4.71：账号令牌改查 user_session（join app_user），命中即续期 last_used_at
        when(jdbc.query(contains("FROM user_session s JOIN app_user u ON u.id = s.user_id WHERE s.token_hash = ?"),
                any(RowMapper.class), eq(sha256Hex(USER_TOKEN)))).thenReturn(List.of(2L));

        mvc.perform(get("/api/v1/tasks").header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("2"));
        Mockito.verify(jdbc).update(contains("UPDATE user_session SET last_used_at = now()"),
                eq(sha256Hex(USER_TOKEN)));
    }

    @Test
    void unknownAccountTokenIsRejected() throws Exception {
        when(jdbc.query(contains("FROM user_session s JOIN app_user u ON u.id = s.user_id WHERE s.token_hash = ?"),
                any(RowMapper.class), eq(sha256Hex("ghost-token")))).thenReturn(List.of());
        mvc.perform(get("/api/v1/tasks").header("Authorization", "Bearer ghost-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userContextIsClearedAfterRequest() throws Exception {
        mvc.perform(get("/api/v1/tasks").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, UserContext::current,
                "finally 必须 clear，防止线程池串号");
    }

    /* ---- 豁免面 ---- */

    @Test
    void authLoginAndRegisterAreExempt() throws Exception {
        mvc.perform(post("/api/v1/auth/login")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/register")).andExpect(status().isOk());
    }

    @Test
    void authLogoutAndMeStillRequireAuth() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        // 未知账号令牌也不放行 logout
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void audioPathIsExemptOnlyForGetWithoutBearer() throws Exception {
        mvc.perform(get("/api/v1/tasks/5/audio"))
                .andExpect(status().isOk()); // 免 Bearer（验签在 controller）
    }

    @Test
    void audioTokenIssueStillRequiresBearer() throws Exception {
        mvc.perform(get("/api/v1/tasks/5/audio-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/tasks/5/audio-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nearbyPathsAreNotExempt() throws Exception {
        mvc.perform(get("/api/v1/tasks/5"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/loginx"))
                .andExpect(status().isUnauthorized()); // 前缀不豁免（精确路径口径）
    }

    @Test
    void audioPathNonNumericOrWrongMethodNotExempt() throws Exception {
        mvc.perform(get("/api/v1/tasks/abc/audio"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tasks/5/audiox"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tasks/5/audio/extra"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/tasks/5/audio"))
                .andExpect(status().isUnauthorized()); // 只豁免 GET
    }

    /* ---- T4.93 C5：setup 三条精确豁免 ---- */

    @Test
    void setupWizardEndpointsAreExempt() throws Exception {
        mvc.perform(get("/api/v1/setup/status")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/setup/model")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/setup/admin")).andExpect(status().isOk());
    }

    @Test
    void setupNearbyPathsAndMethodsAreNotExempt() throws Exception {
        // 前缀不豁免（精确路径口径）；GET 打 POST 端点、POST 打 GET 端点都不放行（K-214 口径）
        mvc.perform(get("/api/v1/setup/admin")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/setup/statusx")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/setup/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/setup/model")).andExpect(status().isUnauthorized());
    }
}
