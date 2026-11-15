package com.meetingnotes.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 *
 * <p>F-21（T4.12）：显式 {@code LLM_ENABLED=true} 但云档 key 没配齐 → 此处直接 fail-fast
 * 并点名缺失键（原行为是拖到 OpenAI 自动配置里抛间接报错）。只拦"必崩"的矛盾配置，
 * 不改 D-039 显式优先语义。
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
        if (enabled) {
            // F-21（T4.12）：显式开启云档但 key 没配齐 → 在最早的阶段 fail-fast，替代下游
            // "OpenAI base URL must be set" 这类间接报错。不改 D-039 显式优先语义（enabled 判定原样）。
            assertCloudKeysPresent(environment);
        }

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

    /**
     * F-21 启动预检：云档启用时逐个点名缺失键并给出修法（可读文案，fail-fast）。
     * 读的是原始 {@code LLM_*} 键（与 {@link #isCloudLlmEnabled} 的自动判断同源），
     * 不看 {@code spring.ai.openai.*} 的 yml 默认值（那里 api-key 有 not-needed 兜底，会掩盖"没配"）。
     */
    static void assertCloudKeysPresent(ConfigurableEnvironment environment) {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(environment.getProperty("LLM_BASE_URL"))) {
            missing.add("LLM_BASE_URL");
        }
        if (!StringUtils.hasText(environment.getProperty("LLM_API_KEY"))) {
            missing.add("LLM_API_KEY");
        }
        if (!StringUtils.hasText(environment.getProperty("LLM_MODEL"))) {
            missing.add("LLM_MODEL");
        }
        if (missing.isEmpty()) {
            return;
        }
        throw new IllegalStateException("启动失败（F-21 预检）：云档 LLM 已启用，但以下配置键缺失："
                + String.join("、", missing)
                + "。修复二选一：① 在 .env 补齐这些键（DeepSeek 示例：LLM_BASE_URL=https://api.deepseek.com、"
                + "LLM_API_KEY=sk-...、LLM_MODEL=deepseek-flash；换智谱还需 LLM_CHAT_COMPLETIONS_PATH=/chat/completions）；"
                + "② 没有云 key 就把 LLM_ENABLED 留空或设为 false——无 key 也能完整跑通"
                + "（本地 ASR + EXTRACTIVE 摘要，D-035/D-039）");
    }
}
