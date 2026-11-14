package com.meetingnotes.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * ASR 调用超时（T2.2 / D-058）：给 Spring AI 的 RestClient 统一定超时。
 *
 * <p>connect-timeout-sec（默认 5s）：连接/首字节阶段，停 ASR 时快速失败，不等 DNS/连接挂死；
 * timeout-sec（默认 900s）：单次转写请求总超时——按 RTF ≈ 0.1，60 分钟音频约 6 分钟，必须不被误杀。
 * 该定制作用于所有 RestClient.Builder（含 LLM 通道）：连接 5s 对两者都合理，
 * 读 900s 是上界（LLM 摘要远短于此）。超时值全部走环境变量（禁止魔法数，N11）。
 */
@Configuration
public class AsrHttpClientConfig {

    @Bean
    public RestClientCustomizer asrTimeoutCustomizer(
            @Value("${app.asr.connect-timeout-sec}") long connectTimeoutSec,
            @Value("${app.asr.timeout-sec}") long readTimeoutSec) {
        return builder -> builder.requestFactory(timeoutFactory(connectTimeoutSec, readTimeoutSec));
    }

    /** 包私有以便单测断言超时值确实生效（T2.2）。 */
    static ClientHttpRequestFactory timeoutFactory(long connectTimeoutSec, long readTimeoutSec) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) (connectTimeoutSec * 1000));
        factory.setReadTimeout((int) (readTimeoutSec * 1000));
        return factory;
    }
}
