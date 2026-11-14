package com.meetingnotes.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/**
 * 第二档 LOCAL 的独立 ChatModel（T3.3 ②）：base-url = {@code OLLAMA_BASE_URL}、model = {@code OLLAMA_MODEL}。
 *
 * <p>三条关键约束：
 * <ul>
 *   <li>**独立 bean**（{@code localOllamaChatModel}），不覆盖/污染 CLOUD 的 {@code openAiChatModel}；</li>
 *   <li>completions-path 固定 {@code /chat/completions}——base-url 已带 {@code /v1}，
 *       再拼 {@code /v1/chat/completions} 会变成 {@code /v1/v1/...}（同 D-034 的坑）；</li>
 *   <li>复用注入的 {@link RestClient.Builder}（已被 {@code AsrHttpClientConfig} 定制）→ chat 走 120s 路由，
 *       不吃转写的 900s（D-068）；本地档**不做内部重试**（maxAttempts=1），"再来一次"的语义交给级联降级。</li>
 * </ul>
 * {@code OLLAMA_ENABLED=false}（默认）时这个 bean 根本不存在，无 key 也能启动（D-035/K-04）。
 */
@Configuration
@ConditionalOnProperty(name = "app.llm.ollama.enabled", havingValue = "true")
public class OllamaChatModelConfig {

    @Bean(name = "localOllamaChatModel")
    public ChatModel localOllamaChatModel(
            RestClient.Builder restClientBuilder,
            @Value("${app.llm.ollama.base-url}") String baseUrl,
            @Value("${app.llm.ollama.model}") String model,
            @Value("${spring.ai.openai.chat.options.max-tokens}") Integer maxTokens) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey("ollama-no-key")
                .completionsPath("/chat/completions")
                .restClientBuilder(restClientBuilder)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .maxTokens(maxTokens)
                .temperature(0.0)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
    }
}
