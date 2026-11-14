package com.meetingnotes.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/**
 * 第二档 LOCAL 的独立 ChatModel（T3.3 ② / T3.11 独立预算）：base-url = {@code OLLAMA_BASE_URL}、
 * model = {@code OLLAMA_MODEL}、max_tokens = {@code LLM_LOCAL_MAX_TOKENS}（默认 512）、
 * 读超时 = {@code LLM_LOCAL_TIMEOUT_SEC}（默认 180s）。
 *
 * <p>为什么本地和云不一样（D-081，一个类里说清）：云档 8192/120s 是给推理模型（deepseek-flash
 * 思考链吃 token）配的；本地 1.5B/CPU 没有独立思考链，8192 只会让它生成到 120s 触顶（K-32 实测
 * 124.9s 失败、512 时 16.8s 正常）。所以本地档**独立预算 + 独立超时通道**：
 * <ul>
 *   <li>**独立 bean**（{@code localOllamaChatModel}），不覆盖/污染 CLOUD 的 {@code openAiChatModel}；</li>
 *   <li>**独立超时通道**：不复用注入的 {@link RestClient.Builder}（那被 {@code AsrHttpClientConfig}
 *       定制成云 120s 路由），自己建 connect 5s / read {@code LLM_LOCAL_TIMEOUT_SEC} 的工厂——
 *       {@code AsrHttpClientConfig} 一个字不改，云档回归可验 120s 没被串线；</li>
 *   <li>completions-path 固定 {@code /chat/completions}——base-url 已带 {@code /v1}（D-034 的坑）；
 *       本地档**不做内部重试**（maxAttempts=1），"再来一次"的语义交给级联降级。</li>
 * </ul>
 * {@code OLLAMA_ENABLED=false}（默认）时这个 bean 根本不存在，无 key 也能启动（D-035/K-04）。
 */
@Configuration
@ConditionalOnProperty(name = "app.llm.ollama.enabled", havingValue = "true")
public class OllamaChatModelConfig {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatModelConfig.class);

    @Bean(name = "localOllamaChatModel")
    public ChatModel localOllamaChatModel(
            @Value("${app.llm.ollama.base-url}") String baseUrl,
            @Value("${app.llm.ollama.model}") String model,
            @Value("${app.llm.ollama.max-tokens}") Integer maxTokens,
            @Value("${app.llm.ollama.timeout-sec}") long timeoutSec) {
        log.info("本地档预算：max_tokens={} / timeout={}s（与云档 8192/120s 相互独立，D-081）", maxTokens, timeoutSec);
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey("ollama-no-key")
                .completionsPath("/chat/completions")
                .restClientBuilder(RestClient.builder()
                        .requestFactory(localTimeoutFactory(5, timeoutSec)))
                .build();
        OpenAiChatOptions options = localChatOptions(model, maxTokens);
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
    }

    /** 本地档选项（package-private 供单测断言 maxTokens 生效，防"改回继承云档"的回归）。 */
    static OpenAiChatOptions localChatOptions(String model, Integer maxTokens) {
        return OpenAiChatOptions.builder()
                .model(model)
                .maxTokens(maxTokens)
                .temperature(0.0)
                .build();
    }

    /**
     * 本地档独立超时通道（T3.11/D-081）：connect 5s、read = {@code LLM_LOCAL_TIMEOUT_SEC}（默认 180s）。
     * 不碰 {@code AsrHttpClientConfig} 的 120s/900s 路由（package-private 供单测断言两个超时值）。
     */
    static SimpleClientHttpRequestFactory localTimeoutFactory(long connectTimeoutSec, long readTimeoutSec) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) (connectTimeoutSec * 1000));
        factory.setReadTimeout((int) (readTimeoutSec * 1000));
        return factory;
    }
}
