package com.meetingnotes.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * ASR 探活（T2.4 / D-059）：GET {ASR_BASE_URL}/health + 2s 独立超时——
 * <b>禁止</b>复用 900s 的 ASR 业务总超时（app.asr.timeout-sec）。异常兜成 DOWN，不抛到 context。
 * 测试环境可用 management.health.asr.enabled=false 关闭（NoKeyStartupTest）。
 */
@Component("asr")
@ConditionalOnProperty(name = "management.health.asr.enabled", havingValue = "true", matchIfMissing = true)
public class AsrHealthIndicator implements HealthIndicator {

    /** D-059：探活超时固定 2s（连接与读各 2s），独立于 900s 业务超时。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final RestClient restClient;
    private final String healthUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public AsrHealthIndicator(RestClient.Builder builder,
                              @Value("${spring.ai.openai.audio.transcription.base-url}") String asrBaseUrl) {
        this(builder.requestFactory(probeFactory()).build(), asrBaseUrl);
    }

    /** 探活专用工厂：连接/读各 2s（D-059），独立于 900s 业务超时。 */
    static SimpleClientHttpRequestFactory probeFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) PROBE_TIMEOUT.toMillis());
        factory.setReadTimeout((int) PROBE_TIMEOUT.toMillis());
        return factory;
    }

    /** 供单测注入 mock 的 RestClient。 */
    AsrHealthIndicator(RestClient restClient, String asrBaseUrl) {
        this.restClient = restClient;
        this.healthUrl = asrBaseUrl + "/health";
    }

    @Override
    public Health health() {
        try {
            restClient.get().uri(healthUrl).retrieve().toBodilessEntity();
            return Health.up().withDetail("url", healthUrl).build();
        } catch (Exception e) {
            return Health.down().withDetail("url", healthUrl)
                    .withException(e).build();
        }
    }
}
