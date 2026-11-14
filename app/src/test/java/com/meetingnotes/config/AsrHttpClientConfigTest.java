package com.meetingnotes.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T2.2 / D-058：ASR 超时配置必须真的生效（连接 5s / 读 900s，全部来自环境变量，无魔法数）。 */
class AsrHttpClientConfigTest {

    @Test
    void timeoutFactoryAppliesConfiguredValues() {
        ClientHttpRequestFactory factory = AsrHttpClientConfig.timeoutFactory(5, 900);

        SimpleClientHttpRequestFactory simple = assertInstanceOf(SimpleClientHttpRequestFactory.class, factory);
        // 该类只有 setter（无 getter），私有字段即生效值（毫秒）
        assertEquals(5000, (int) org.springframework.test.util.ReflectionTestUtils.getField(simple, "connectTimeout"),
                "连接超时 = connect-timeout-sec × 1000");
        assertEquals(900_000, (int) org.springframework.test.util.ReflectionTestUtils.getField(simple, "readTimeout"),
                "读超时 = timeout-sec × 1000（长音频 60min 不被误杀的上界）");
    }

    @Test
    void customizerInstallsFactoryIntoRestClientBuilder() throws Exception {
        // F-2（T3.2）：不只 verify any() —— 捕获真正装进 builder 的工厂，端到端断言装配方向：
        // 转写 URI 必须拿到 900s、chat URI 必须拿到 120s（customizer 把两参数传反时必须红）
        org.mockito.ArgumentCaptor<ClientHttpRequestFactory> captor =
                org.mockito.ArgumentCaptor.forClass(ClientHttpRequestFactory.class);
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.requestFactory(any())).thenReturn(builder);

        RestClientCustomizer customizer = new AsrHttpClientConfig()
                .asrTimeoutCustomizer(5, 900, 120);
        customizer.customize(builder);

        verify(builder).requestFactory(captor.capture());
        ClientHttpRequestFactory installed = captor.getValue();
        var routing = assertInstanceOf(AsrHttpClientConfig.UriRoutingClientHttpRequestFactory.class, installed,
                "builder 必须安装按 URI 路由的工厂");
        var asrUri = new java.net.URI("http://asr:8000/v1/audio/transcriptions");
        var chatUri = new java.net.URI("http://api.example.com/v1/chat/completions");
        assertEquals(900_000, (int) org.springframework.test.util.ReflectionTestUtils.getField(routing.select(asrUri), "readTimeout"),
                "转写 URI 装配后必须路由到 900s 工厂");
        assertEquals(120_000, (int) org.springframework.test.util.ReflectionTestUtils.getField(routing.select(chatUri), "readTimeout"),
                "chat URI 装配后必须路由到 120s 工厂");
    }

    /* ---- T3.1/D-068：超时按 URI 路由，两条路径互不串 ---- */

    @Test
    void transcriptionPathUsesLongTimeoutFactoryAndChatUsesShort() throws Exception {
        // F-2（T3.2）：路由方向必须真断言——assertSame 到两个不同实例，再校验各自 readTimeout 值。
        // 旧写法 assertEquals(select(asr), select(asr)) 是自比较，映射写反仍然全绿。
        ClientHttpRequestFactory longFactory = AsrHttpClientConfig.timeoutFactory(5, 900);
        ClientHttpRequestFactory shortFactory = AsrHttpClientConfig.timeoutFactory(5, 120);
        var routing = new AsrHttpClientConfig.UriRoutingClientHttpRequestFactory(longFactory, shortFactory);

        var asrUri = new java.net.URI("http://asr:8000/v1/audio/transcriptions");
        var chatUri = new java.net.URI("http://api.example.com/v1/chat/completions");

        assertSame(longFactory, routing.select(asrUri), "转写路径必须选长超时工厂（方向断言）");
        assertSame(shortFactory, routing.select(chatUri), "chat 路径必须选短超时工厂（方向断言）");
        assertNotSame(routing.select(asrUri), routing.select(chatUri),
                "两条路径不得共用同一个工厂（900s 与 120s 互不串）");
        // 值断言兜底：即使传进来两个相同配置的实例也能区分"长/短"是否真的生效
        assertEquals(900_000, (int) org.springframework.test.util.ReflectionTestUtils.getField(routing.select(asrUri), "readTimeout"),
                "转写工厂读超时必须 900s");
        assertEquals(120_000, (int) org.springframework.test.util.ReflectionTestUtils.getField(routing.select(chatUri), "readTimeout"),
                "chat 工厂读超时必须 120s（LLM_TIMEOUT_SEC 默认）");
    }

    @Test
    void chatPathExplicitlyUsesConfiguredShortTimeout() {
        // chat 工厂的超时值来自 LLM_TIMEOUT_SEC（默认 120），断言显式覆盖也生效
        var chatFactory = AsrHttpClientConfig.timeoutFactory(5, 300);
        assertEquals(300_000, (int) org.springframework.test.util.ReflectionTestUtils.getField(chatFactory, "readTimeout"),
                "chat 读超时应等于 LLM_TIMEOUT_SEC 显式覆盖值 × 1000");
    }
}
