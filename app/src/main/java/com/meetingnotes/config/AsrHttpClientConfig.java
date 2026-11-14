package com.meetingnotes.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.net.URI;

/**
 * HTTP 超时路由（T2.2/D-058 + T3.1/D-068）：按请求路径给不同读超时——
 * <ul>
 *   <li>转写（/v1/audio/transcriptions）→ read 900s（长音频 60min 不被误杀）；</li>
 *   <li>其它（chat 摘要等）→ read {@code app.llm.timeout-sec}（默认 120s）——
 *       LLM 挂死时最多拖住 worker 120s，而不是 900s（单线程 worker 的硬前提）；</li>
 *   <li>连接一律 5s。</li>
 * </ul>
 * 超时值全部走环境变量（禁止魔法数，N11）。
 */
@Configuration
public class AsrHttpClientConfig {

    @Bean
    public RestClientCustomizer asrTimeoutCustomizer(
            @Value("${app.asr.connect-timeout-sec}") long connectTimeoutSec,
            @Value("${app.asr.timeout-sec}") long asrReadTimeoutSec,
            @Value("${app.llm.timeout-sec}") long chatReadTimeoutSec) {
        return builder -> builder.requestFactory(
                new UriRoutingClientHttpRequestFactory(
                        timeoutFactory(connectTimeoutSec, asrReadTimeoutSec),
                        timeoutFactory(connectTimeoutSec, chatReadTimeoutSec)));
    }

    /** 包私有以便单测断言超时值确实生效（T2.2）。 */
    static ClientHttpRequestFactory timeoutFactory(long connectTimeoutSec, long readTimeoutSec) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) (connectTimeoutSec * 1000));
        factory.setReadTimeout((int) (readTimeoutSec * 1000));
        return factory;
    }

    /**
     * 按 URI 路径选择工厂：转写用长超时，其余（chat）用短超时。
     * 选择逻辑抽成 {@code select}（package-private）供单测断言"互不串"。
     */
    static final class UriRoutingClientHttpRequestFactory implements ClientHttpRequestFactory {

        static final String TRANSCRIPTION_PATH = "/v1/audio/transcriptions";

        private final ClientHttpRequestFactory transcriptionFactory;
        private final ClientHttpRequestFactory chatFactory;

        UriRoutingClientHttpRequestFactory(ClientHttpRequestFactory transcriptionFactory,
                                           ClientHttpRequestFactory chatFactory) {
            this.transcriptionFactory = transcriptionFactory;
            this.chatFactory = chatFactory;
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws java.io.IOException {
            return select(uri).createRequest(uri, method);
        }

        ClientHttpRequestFactory select(URI uri) {
            String path = uri == null || uri.getPath() == null ? "" : uri.getPath();
            return path.contains(TRANSCRIPTION_PATH) ? transcriptionFactory : chatFactory;
        }
    }
}
