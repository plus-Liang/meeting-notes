package com.meetingnotes.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void customizerInstallsFactoryIntoRestClientBuilder() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.requestFactory(any())).thenReturn(builder);

        RestClientCustomizer customizer = new AsrHttpClientConfig()
                .asrTimeoutCustomizer(5, 900);
        customizer.customize(builder);

        verify(builder).requestFactory(any(ClientHttpRequestFactory.class));
    }
}
