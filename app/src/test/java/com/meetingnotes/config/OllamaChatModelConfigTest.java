package com.meetingnotes.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * T3.11/D-081：本地档独立预算——max_tokens=512、读超时=180s（默认值，均**可造红**：
 * 把配置类改回继承云档 8192/120s，这两条断言必红）；AsrHttpClientConfig 的云路由不在此验证范围。
 */
class OllamaChatModelConfigTest {

    @Test
    void localTimeoutFactoryUsesIndependentConnect5sAndRead180s() throws Exception {
        // Spring 6.2 的 SimpleClientHttpRequestFactory 只有 setter，断言走私有字段反射（同 AsrHttpClientConfigTest 口径）
        SimpleClientHttpRequestFactory factory = OllamaChatModelConfig.localTimeoutFactory(5, 180);
        var connect = SimpleClientHttpRequestFactory.class.getDeclaredField("connectTimeout");
        connect.setAccessible(true);
        var read = SimpleClientHttpRequestFactory.class.getDeclaredField("readTimeout");
        read.setAccessible(true);

        assertEquals(5000, connect.get(factory), "connect 5s（与云档一致，但通道独立）");
        assertEquals(180_000, read.get(factory), "读超时 = LLM_LOCAL_TIMEOUT_SEC（默认 180s，非云档 120s）");
    }

    @Test
    void localChatModelCarriesLocalMaxTokensNotCloud8192() {
        OllamaChatModelConfig config = new OllamaChatModelConfig();
        ChatModel model = config.localOllamaChatModel(
                "http://ollama:11434/v1", "qwen2.5:1.5b", 512, 180);

        OpenAiChatModel chatModel = assertInstanceOf(OpenAiChatModel.class, model);
        OpenAiChatOptions options = assertInstanceOf(OpenAiChatOptions.class, chatModel.getDefaultOptions());
        assertEquals(512, options.getMaxTokens(), "本地 max_tokens 必须来自 LLM_LOCAL_MAX_TOKENS，不许继承云档 8192");
        assertEquals("qwen2.5:1.5b", options.getModel());
        assertEquals(0.0, options.getTemperature());
    }
}
