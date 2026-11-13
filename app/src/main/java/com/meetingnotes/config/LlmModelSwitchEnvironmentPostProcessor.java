package com.meetingnotes.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D-035：不填任何 API key 时应用也必须能启动。
 *
 * <p>问题：Spring AI 1.1.8 的 {@code OpenAiChatAutoConfiguration} 默认匹配
 * {@code spring.ai.model.chat=openai}（{@code matchIfMissing=true}），其
 * {@code OpenAIAutoConfigurationUtil.resolveConnectionProperties} 会对 base-url 做
 * {@code Assert.hasText}；而零成本默认配置里 {@code spring.ai.openai.base-url} 是空串，
 * 于是完整应用上下文直接启动失败。填一个假的默认地址能启动但一调用就 5xx，更糟（D-035 已排除）。
 *
 * <p>做法：在配置数据加载完成之后、自动配置条件求值之前，计算一个 {@code app.llm.enabled} 布尔值，
 * 并用 Spring AI 官方的「模型开关」属性把不需要的 OpenAI 自动配置关掉：
 * <ul>
 *   <li>没有 key → {@code spring.ai.model.chat=none}，chat 自动配置整体退场；摘要走第二/三档（T3.x）。</li>
 *   <li>embedding / image / moderation / audio.speech 同样共用空的公共 base-url，一律关掉。</li>
 *   <li>{@code spring.ai.model.audio.transcription} 保持默认 openai，由 {@code ASR_BASE_URL} 供地址
 *       （本地 ASR，零成本，见 D-034）。</li>
 * </ul>
 *
 * <p>优先级：显式 {@code LLM_ENABLED} → 自动判断（{@code LLM_API_KEY} + {@code LLM_BASE_URL}
 * + {@code LLM_MODEL} 三者都有值才算启用）。已显式设置的 {@code spring.ai.model.*} 不会被覆盖。
 */
public class LlmModelSwitchEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
     * 必须晚于 {@code ConfigDataEnvironmentPostProcessor}（HIGHEST_PRECEDENCE + 10），
     * 否则读不到 application.yml 里的 {@code app.llm.enabled: ${LLM_ENABLED:}}。
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    private static final String PROPERTY_SOURCE = "meetingNotesLlmModelSwitch";

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean enabled = isCloudLlmEnabled(environment);

        Map<String, Object> props = new LinkedHashMap<>();
        // 把「自动判断」的结果固化成普通属性，供 @Value / 三档选择器消费
        props.put("app.llm.enabled", Boolean.toString(enabled));
        putIfAbsent(environment, props, "spring.ai.model.chat", enabled ? "openai" : "none");
        putIfAbsent(environment, props, "spring.ai.model.embedding", "none");
        putIfAbsent(environment, props, "spring.ai.model.image", "none");
        putIfAbsent(environment, props, "spring.ai.model.moderation", "none");
        putIfAbsent(environment, props, "spring.ai.model.audio.speech", "none");

        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, props));
    }

    private static boolean isCloudLlmEnabled(ConfigurableEnvironment environment) {
        String explicit = environment.getProperty("app.llm.enabled");
        if (StringUtils.hasText(explicit)) {
            return Boolean.parseBoolean(explicit.trim());
        }
        return StringUtils.hasText(environment.getProperty("LLM_API_KEY"))
                && StringUtils.hasText(environment.getProperty("LLM_BASE_URL"))
                && StringUtils.hasText(environment.getProperty("LLM_MODEL"));
    }

    private static void putIfAbsent(ConfigurableEnvironment environment,
                                    Map<String, Object> props, String key, String value) {
        if (!environment.containsProperty(key)) {
            props.put(key, value);
        }
    }
}
