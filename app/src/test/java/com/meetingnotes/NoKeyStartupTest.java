package com.meetingnotes;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.meetingnotes.worker.TranscriptionWorker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-035 验收：清空 LLM_BASE_URL / LLM_API_KEY / LLM_MODEL 后，真实应用上下文仍能启动，
 * 且 /actuator/health 正常返回 UP。
 *
 * <p>用 H2 内存库替换外部 PostgreSQL（仅测试范围），其余全部是生产配置：
 * 真实 application.yml、真实 MeetingNotesApplication、真实 Spring AI 自动配置集合。
 * 关闭 Flyway 与 JPA 建表是为了让测试不依赖外部服务，不影响被测的 LLM 开关逻辑。
 *
 * <p>T4.2（E2E 纳入全量后必须）：**Mock 掉 TranscriptionWorker**——本测试上下文连的是
 * 真实 Redis（BLPOP 消费者），不加隔离会与运行中容器的 worker 抢真实队列：任务被本测试
 * 的 worker 偷走后写进 H2，真实任务卡 PENDING（E2E 假红）。worker 的单测覆盖在
 * TranscriptionWorkerTest（纯 Mockito）；本类只验证"无 key 可启动"，mock 掉不影响断言。
 */
@SpringBootTest(classes = MeetingNotesApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "LLM_ENABLED=",
                "LLM_BASE_URL=",
                "LLM_API_KEY=",
                "LLM_MODEL=",
                "spring.datasource.url=jdbc:h2:mem:nokey",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "management.health.redis.enabled=false",
                // T2.4：H2/无外部依赖环境关闭 minio/asr 探活（否则整体 DOWN），并放宽 readiness 组成员校验
                "management.health.minio.enabled=false",
                "management.health.asr.enabled=false",
                "management.endpoint.health.validate-group-membership=false"
        })
class NoKeyStartupTest {

    /** T4.2：防止测试上下文的 worker BLPOP 真实队列、偷走 E2E/实机任务写进 H2（假红/卡 PENDING）。 */
    @MockBean
    private TranscriptionWorker transcriptionWorker;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate restTemplate;

    @Value("${app.llm.enabled:}")
    private String llmEnabled;

    @Test
    void startsWithoutAnyApiKeyAndHealthIsUp() {
        assertEquals("false", llmEnabled, "无 key 时 app.llm.enabled 应为 false");
        assertTrue(context.getBeansOfType(ChatModel.class).isEmpty(),
                "无 key 时不应创建任何 ChatModel Bean（chat 自动配置必须跳过）");

        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "/actuator/health 应返回 200");
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("\"status\":\"UP\""),
                "/actuator/health 应为 UP，实际: " + response.getBody());

        System.out.println("[NoKeyStartupTest] app.llm.enabled = " + llmEnabled);
        System.out.println("[NoKeyStartupTest] ChatModel beans = " + context.getBeansOfType(ChatModel.class).size());
        System.out.println("[NoKeyStartupTest] /actuator/health = " + response.getBody());
    }
}
