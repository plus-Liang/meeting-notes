package com.meetingnotes;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.StringUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T0.5：云 LLM 通道最小冒烟测试。
 *
 * <p>只加载 chat 链路必需的自动配置，不拉起真实应用上下文（后者依赖 PG / Redis / Flyway，属 T1.x）。
 * 地址 / 模型名 / key 全部来自环境变量，代码里不写死。
 *
 * <p>这是「真实云调用」测试，需要有效的 LLM_API_KEY；不设 key 时会明确失败，
 * 不用 @Disabled / assumeTrue 把它伪装成通过（见 AGENT.md §7、tasks.md 测试要求）。
 * 取值见 .env.example。
 */
@SpringBootTest(classes = LlmSmokeTest.MinimalContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LlmSmokeTest {

    private static final String FIXED_TEXT = """
            项目采用本地自建 ASR 容器做语音转写，实测 RTF 约 0.11，音频不出本机。
            摘要走三档降级：第一档云 LLM、第二档本地 Ollama、第三档抽取式兜底。
            所有路径、端口、密钥、并发数和模型名都通过环境变量注入，不填任何 key 也能跑通全流程。
            """;

    @Autowired
    private ChatModel chatModel;

    @Value("${LLM_API_KEY:}")
    private String apiKey;

    @Value("${LLM_BASE_URL:}")
    private String baseUrl;

    @Value("${LLM_MODEL:}")
    private String model;

    @Value("${spring.ai.openai.chat.completions-path}")
    private String completionsPath;

    @Value("${app.llm.enabled:}")
    private String llmEnabled;

    @Test
    void summarizeFixedTextIntoThreeBullets() {
        assertTrue(StringUtils.hasText(apiKey), "LLM_API_KEY 未设置：请用环境变量提供云 LLM key");
        assertTrue(StringUtils.hasText(baseUrl), "LLM_BASE_URL 未设置：请用环境变量提供云 LLM 地址");
        assertTrue(StringUtils.hasText(model), "LLM_MODEL 未设置：请用环境变量提供模型名");
        assertEquals("true", llmEnabled, "D-035 自动判断应判定云 LLM 已启用");

        String prompt = "请把下面这段文字总结成 3 条中文要点，每条一行，不要输出其它内容：\n" + FIXED_TEXT;
        String summary = chatModel.call(prompt);

        assertNotNull(summary, "ChatModel 返回 null");
        assertFalse(summary.isBlank(), "ChatModel 返回空文本");
        long bullets = summary.lines().filter(line -> !line.isBlank()).count();
        assertTrue(bullets >= 3, "期望至少 3 条要点，实际 " + bullets + " 行：" + summary);

        System.out.println("[LlmSmokeTest] base-url   = " + baseUrl);
        System.out.println("[LlmSmokeTest] path       = " + completionsPath);
        System.out.println("[LlmSmokeTest] model      = " + model);
        System.out.println("[LlmSmokeTest] llmEnabled = " + llmEnabled);
        System.out.println("[LlmSmokeTest] bullets    = " + bullets);
        System.out.println("[LlmSmokeTest] summary    =\n" + summary);
    }

    /** 只导入 chat 链路必需的自动配置；条件开关 spring.ai.model.chat 由 D-035 的 EPP 计算。 */
    @SpringBootConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            SpringAiRetryAutoConfiguration.class,
            RestClientAutoConfiguration.class,
            WebClientAutoConfiguration.class,
            ToolCallingAutoConfiguration.class,
            OpenAiChatAutoConfiguration.class
    })
    static class MinimalContext {
    }
}
