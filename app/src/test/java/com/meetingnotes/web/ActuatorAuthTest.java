package com.meetingnotes.web;

import com.meetingnotes.MeetingNotesApplication;
import com.meetingnotes.worker.TranscriptionWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.89/P-28：/actuator/** 默认拒绝匿名、任意有效凭据放行（复用 ApiTokenFilter.authenticate）。
 * 上下文配置同 NoKeyStartupTest（H2 + 关 Flyway/redis/minio/asr 探活），凭据经 property 注入。
 */
@SpringBootTest(classes = MeetingNotesApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.allow-weak-api-token=true",
                "app.security.api-token=t489-actuator-test-token-0123456789abcdef",
                "LLM_ENABLED=",
                "LLM_BASE_URL=",
                "LLM_API_KEY=",
                "LLM_MODEL=",
                "spring.datasource.url=jdbc:h2:mem:actuatorauth",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "management.health.redis.enabled=false",
                "management.health.minio.enabled=false",
                "management.health.asr.enabled=false",
                "management.endpoint.health.validate-group-membership=false",
                "management.prometheus.metrics.export.enabled=true",
                "management.endpoint.prometheus.enabled=true"
        })
@org.springframework.test.context.jdbc.Sql(
        statements = {
                // 错 token 走账号令牌查询：H2 需有 app_user/user_session 空表（否则缺表 500 而非 401）
                "CREATE TABLE IF NOT EXISTS app_user(id BIGINT PRIMARY KEY)",
                "CREATE TABLE IF NOT EXISTS user_session(user_id BIGINT, token_hash VARCHAR(64))"
        },
        executionPhase = org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ActuatorAuthTest {

    private static final String[] PATHS = {
            "/actuator/health", "/actuator/health/readiness", "/actuator/prometheus",
            "/actuator/metrics", "/actuator/metrics/jvm.memory.used", "/actuator/info"
    };

    @MockBean
    private TranscriptionWorker transcriptionWorker;

    @Autowired
    private TestRestTemplate restTemplate;

    @Value("${app.security.api-token}")
    private String apiToken;

    private ResponseEntity<String> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.set("Authorization", "Bearer " + bearer);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void anonymousActuatorIs401WithoutDetails() {
        for (String path : PATHS) {
            ResponseEntity<String> r = get(path, null);
            assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode(), path + " 匿名应 401");
            assertEquals("Bearer", r.getHeaders().getFirst("WWW-Authenticate"), path + " 应带 WWW-Authenticate");
            assertNotNull(r.getBody());
            assertTrue(r.getBody().contains("UNAUTHORIZED"), path + " 统一错误码: " + r.getBody());
            assertTrue(!r.getBody().contains("components") && !r.getBody().contains("jvm")
                    && !r.getBody().contains("\"url\""), path + " 401 body 不得泄露细节: " + r.getBody());
        }
    }

    @Test
    void wrongTokenIs401() {
        for (String path : PATHS) {
            assertEquals(HttpStatus.UNAUTHORIZED, get(path, "wrong-token-0123456789abcdef0123456789").getStatusCode(),
                    path + " 错 token 应 401");
        }
    }

    @Test
    void validTokenActuatorIs200() {
        for (String path : PATHS) {
            ResponseEntity<String> r = get(path, apiToken);
            assertEquals(HttpStatus.OK, r.getStatusCode(), path + " 带 token 应 200");
            assertNotNull(r.getBody());
            assertTrue(!r.getBody().isBlank(), path + " body 非空");
        }
        ResponseEntity<String> health = get("/actuator/health", apiToken);
        assertTrue(health.getBody().contains("\"status\":\"UP\""), "带 token health 应 UP");
        ResponseEntity<String> prom = get("/actuator/prometheus", apiToken);
        assertTrue(prom.getBody().contains("meetingnotes_"), "prometheus 应含业务指标: 前 200 字="
                + prom.getBody().substring(0, Math.min(200, prom.getBody().length())));
    }
}
