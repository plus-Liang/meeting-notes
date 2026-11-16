package com.meetingnotes.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.16b / D-107：云档 temperature 必须默认 0.0——同内容两次结构化要可复现
 * （未固定时实测待办 0 vs 5 条、摘要相似度 0.47）。
 *
 * <p>为什么不像 {@link OllamaChatModelConfig#localChatOptions} 那样抽静态方法断言：
 * 本地档 bean 是我们手写的（构造可抽）；**云档 bean 由 Spring AI 自动装配从
 * {@code spring.ai.openai.chat.options.*} 属性构造**，代码里没有手写构造——再抽一个静态方法
 * 只会得到"有测试的死代码 + 与 yml 双源漂移"。所以按 K-51 在**真实消费点**断言：
 * 属性 → {@link OpenAiChatAutoConfiguration} → {@code OpenAiChatModel.defaultOptions}；
 * 另用一条测试把 yml 的默认值钉死（防手滑改默认）。
 */
class CloudChatTemperatureTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestClientAutoConfiguration.class,
                    org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration.class,
                    org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration.class,
                    OpenAiChatAutoConfiguration.class));

    @Test
    void cloudTemperaturePropertyDrivesDefaultOptions() {
        runner.withPropertyValues(
                "spring.ai.model.chat=openai",
                "spring.ai.openai.api-key=test-key",
                "spring.ai.openai.chat.options.model=deepseek-flash",
                "spring.ai.openai.chat.options.max-tokens=8192",
                "spring.ai.openai.chat.options.temperature=0.0")
                .run(ctx -> {
                    OpenAiChatModel model = ctx.getBean(OpenAiChatModel.class);
                    assertEquals(Double.valueOf(0.0), model.getDefaultOptions().getTemperature(),
                            "云档 temperature 必须是 0.0（T4.16b/D-107 可复现性）");
                });
    }

    @Test
    void applicationYmlPinsTemperatureDefaultToZero() throws Exception {
        String yml = new String(getClass().getResourceAsStream("/application.yml")
                .readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(yml.contains("temperature: ${LLM_TEMPERATURE:0.0}"),
                "yml 必须把云档 temperature 默认钉在 0.0（LLM_TEMPERATURE 可覆盖）");
    }
}
