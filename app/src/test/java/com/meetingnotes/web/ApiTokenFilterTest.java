package com.meetingnotes.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T4.6：/api/v1/** Bearer 认证——401 统一口径（UNAUTHORIZED + WWW-Authenticate）、常量时间比较、豁免面。
 * standalone MockMvc：不拉起全上下文（不碰 DB/Redis），只验 filter 行为。
 */
class ApiTokenFilterTest {

    private static final String TOKEN = "test-token-123";

    private MockMvc mvc;

    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/tasks")
        public String tasks() {
            return "ok";
        }

        @GetMapping("/api/v1/tasks/5/audio")
        public String audio() {
            return "audio"; // T4.35b：真实验签在 AudioStreamController；filter 层只验豁免面
        }
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new ApiTokenFilter(TOKEN, new ObjectMapper()))
                .build();
    }

    @Test
    void missingTokenIsRejectedWithUnifiedJsonBody() throws Exception {
        mvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/tasks").header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void correctTokenPasses() throws Exception {
        mvc.perform(get("/api/v1/tasks").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void lowercaseBearerPrefixRejected() throws Exception {
        // "bearer xxx" 不是合法的 Bearer 方案写法（RFC 7235 scheme 大小写不敏感，但与配置值逐字节常量比较不一致）
        mvc.perform(get("/api/v1/tasks").header("Authorization", "bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void constantTimeEqualsIsExact() {
        org.junit.jupiter.api.Assertions.assertTrue(ApiTokenFilter.constantTimeEquals("Bearer a", "Bearer a"));
        org.junit.jupiter.api.Assertions.assertFalse(ApiTokenFilter.constantTimeEquals("Bearer a", "bearer a"));
        org.junit.jupiter.api.Assertions.assertFalse(ApiTokenFilter.constantTimeEquals("Bearer a", "Bearer ab"));
    }

    /* ---- T4.35b（K-46 认证面收窄）：只豁免 GET 且精确 ^/api/v1/tasks/\d+/audio$ 一条 ---- */

    @Test
    void audioPathIsExemptOnlyForGetWithoutBearer() throws Exception {
        mvc.perform(get("/api/v1/tasks/5/audio"))
                .andExpect(status().isOk()); // 免 Bearer（验签在 controller）
    }

    @Test
    void audioTokenIssueStillRequiresBearer() throws Exception {
        mvc.perform(get("/api/v1/tasks/5/audio-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/tasks/5/audio-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nearbyPathsAreNotExempt() throws Exception {
        mvc.perform(get("/api/v1/tasks/5"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void audioPathNonNumericOrWrongMethodNotExempt() throws Exception {
        mvc.perform(get("/api/v1/tasks/abc/audio"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tasks/5/audiox"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tasks/5/audio/extra"))
                .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/tasks/5/audio"))
                .andExpect(status().isUnauthorized()); // 只豁免 GET
    }
}
